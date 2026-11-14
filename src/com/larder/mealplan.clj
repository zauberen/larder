;; Larder - a household recipe, pantry and grocery list manager.
;; Copyright (C) 2025-2026 zauberen
;;
;; This program is free software: you can redistribute it and/or modify
;; it under the terms of the GNU Affero General Public License as published
;; by the Free Software Foundation, either version 3 of the License, or
;; (at your option) any later version.
;;
;; This program is distributed in the hope that it will be useful,
;; but WITHOUT ANY WARRANTY; without even the implied warranty of
;; MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
;; GNU Affero General Public License for more details.
;;
;; You should have received a copy of the GNU Affero General Public License
;; along with this program.  If not, see <https://www.gnu.org/licenses/>.

(ns com.larder.mealplan
  "The meal plan: recipes on calendar days, and the grocery list for a range of days."
  (:require [clojure.string :as str]
            [com.larder.database.db :as db]
            [com.larder.grocery :as grocery]
            [com.larder.status :as status]
            [com.larder.units :as units])
  (:import (java.time DayOfWeek LocalDate)
           (java.time.temporal TemporalAdjusters)))

(def week-start
  "First day of the week in the calendar."
  DayOfWeek/MONDAY)

(defn parse-date
  "LocalDate for an ISO date string, or nil."
  ^LocalDate [s]
  (try (LocalDate/parse (str s)) (catch Exception _ nil)))

(defn today ^LocalDate [] (LocalDate/now))

(defn week-of
  "The 7 dates of the week containing DATE."
  [^LocalDate date]
  (let [start (.with date (TemporalAdjusters/previousOrSame week-start))]
    (mapv #(.plusDays start %) (range 7))))

(defn month-grid
  "Whole weeks covering DATE's month (5 or 6 rows of 7 dates)."
  [^LocalDate date]
  (let [first-day (.withDayOfMonth date 1)
        last-day (.with date (TemporalAdjusters/lastDayOfMonth))
        start (.with first-day (TemporalAdjusters/previousOrSame week-start))
        end (.with last-day (TemporalAdjusters/nextOrSame (.minus week-start 1)))]
    (vec (take-while #(not (.isAfter ^LocalDate % end)) (iterate #(.plusDays ^LocalDate % 1) start)))))

;;; Entries

(def ^:private select-entries
  (str "select e.*, r.title, r.servings as recipe_servings, r.status as recipe_status, r.image_path"
       " from meal_plan_entries e join recipes r on r.id = e.recipe_id"))

(defn entries-between
  "Entries from FROM to TO (inclusive LocalDates), by date and position."
  [from to]
  (db/run-db-query [(str select-entries " where e.date >= ? and e.date <= ? order by e.date, e.position, e.id")
                    (str from) (str to)]))

(defn entries-on
  [date]
  (entries-between date date))

(defn by-id
  [id]
  (first (db/run-db-query [(str select-entries " where e.id = ?") id])))

(defn- next-position
  [date]
  (inc (or (:p (first (db/run-db-query ["select max(position) as p from meal_plan_entries where date = ?" (str date)]))) -1)))

(defn add!
  "Plan RECIPE-ID on DATE for SERVINGS (default: the recipe's). Returns the entry id."
  [recipe-id date & [servings]]
  (let [servings (or servings (:servings (first (db/run-db-query ["select servings from recipes where id = ?" recipe-id]))))]
    (db/insert! ["insert into meal_plan_entries (date, recipe_id, servings, position) values (?, ?, ?, ?)"
                 (str date) recipe-id servings (next-position date)])))

(defn move!
  "Move entry ID to the end of DATE."
  [id date]
  (db/exe-db-stmt ["update meal_plan_entries set date = ?, position = ? where id = ?" (str date) (next-position date) id]))

(defn set-servings!
  [id servings]
  (db/exe-db-stmt ["update meal_plan_entries set servings = ? where id = ?" servings id]))

(defn delete!
  [id]
  (db/exe-db-stmt ["delete from meal_plan_entries where id = ?" id]))

(defn scale
  "Factor for an entry's servings over its recipe's."
  [entry]
  (let [s (:servings entry) rs (:recipe_servings entry)]
    (if (and s rs (pos? s) (pos? rs)) (/ (double s) rs) 1.0)))

;;; Grocery list for a range

(defn- lines-of
  [recipe-ids]
  (if (empty? recipe-ids)
    {}
    (group-by :recipe_id
              (db/run-db-query (into [(str "select * from recipe_ingredients where recipe_id in ("
                                           (str/join "," (repeat (count recipe-ids) "?")) ") order by position")]
                                     recipe-ids)))))

(defn needs
  "What the uncooked ENTRIES need: {:ingredients {ing-id {:quantity q :unit u} or
  {:unconvertible true}} :unmatched [line texts]}. Amounts of an ingredient are summed in
  the unit of its first line; a line without an amount, or one that doesn't convert,
  makes the ingredient unconvertible."
  [snap entries]
  (let [entries (remove :cooked_at entries)
        lines (lines-of (distinct (map :recipe_id entries)))]
    (reduce
     (fn [acc entry]
       (reduce
        (fn [acc line]
          (if-let [ing-id (:ingredient_id line)]
            (let [density (get-in snap [:ingredients ing-id :density])
                  q (some-> (:quantity line) (* (scale entry)))
                  cur (get-in acc [:ingredients ing-id])]
              (assoc-in acc [:ingredients ing-id]
                        (cond
                          (:unconvertible cur) cur
                          (nil? q) {:unconvertible true}
                          (nil? cur) {:quantity q :unit (:unit line)}
                          :else (if-let [c (units/convert q (:unit line) (:unit cur) density)]
                                  (update cur :quantity + c)
                                  {:unconvertible true}))))
            (update acc :unmatched (fnil conj []) (:original_text line))))
        acc (get lines (:recipe_id entry))))
     {:ingredients {} :unmatched []} entries)))

(defn- open-grocery-for
  [ing-id]
  (db/run-db-query ["select quantity, unit from grocery_items where done_at is null and section = 'grocery' and ingredient_id = ?" ing-id]))

(defn add-to-grocery!
  "Add what the uncooked entries between FROM and TO need beyond the pantry and the open
  grocery list. Stock or list items with an unknown amount count as covering the need.
  Unconvertible needs add the ingredient once at its default purchase size when it is
  neither in stock nor listed; unmatched lines become misc items. Returns
  {:added n :covered n}."
  [from to user-id & [origin]]
  (let [origin (or origin "mealplan")
        snap (status/snapshot)
        {:keys [ingredients unmatched]} (needs snap (entries-between from to))
        results
        (vec
         (for [[ing-id need] ingredients
               :let [ing (get-in snap [:ingredients ing-id])
                     rows (get-in snap [:stock ing-id])
                     listed (open-grocery-for ing-id)]
               :when (not= 1 (:always_have ing))]
           (if (:unconvertible need)
             (if (or (seq rows) (seq listed))
               :covered
               (do (grocery/add-ingredient! {:ingredient_id ing-id :quantity (:default_quantity ing)
                                             :unit (:default_unit ing) :origin origin :added_by user-id})
                   :added))
             (let [{:keys [quantity unit]} need
                   density (:density ing)
                   [stock _ unknown-stock] (status/stock-total rows unit density)
                   [on-list _ unknown-list] (status/stock-total listed unit density)
                   net (- quantity stock on-list)]
               (if (or (<= net 1e-9) (pos? unknown-stock) (pos? unknown-list))
                 :covered
                 (do (grocery/add-ingredient! {:ingredient_id ing-id :quantity net :unit unit
                                               :origin origin :added_by user-id})
                     :added))))))
        misc (vec (for [text (distinct unmatched)]
                    (if (grocery/open-misc-text? text)
                      :covered
                      (do (grocery/add-misc! {:text text :origin origin :added_by user-id}) :added))))
        all (concat results misc)]
    {:added (count (filter #{:added} all))
     :covered (count (filter #{:covered} all))}))
