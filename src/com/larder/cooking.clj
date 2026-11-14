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

(ns com.larder.cooking
  "Made it: taking what a recipe used out of the pantry, and the cook log."
  (:require [com.larder.database.db :as db]
            [com.larder.grocery :as grocery]
            [com.larder.ingredients :as ingredients]
            [com.larder.pantry :as pantry]
            [com.larder.units :as units]
            [com.larder.util :as u]))

(def ^:private eps 1e-9)

(defn deduct!
  "Take QUANTITY of UNIT of ingredient ING-ID out of the pantry, oldest rows first. Rows
  that reach zero are removed. Rows without a quantity, or whose unit doesn't convert,
  are left alone. Returns the amount (in UNIT) that couldn't be taken."
  [ing-id quantity unit]
  (let [density (:density (ingredients/by-id ing-id))]
    (loop [rows (pantry/rows-for ing-id) left (double quantity)]
      (if (or (empty? rows) (<= left eps))
        (max 0.0 left)
        (let [row (first rows)
              need-in-row (when (:quantity row) (units/convert left unit (:unit row) density))]
          (cond
            (nil? need-in-row) (recur (rest rows) left)
            (<= (:quantity row) (+ need-in-row eps))
            (do (pantry/delete! [(:id row)])
                (recur (rest rows) (- left (units/convert (:quantity row) (:unit row) unit density))))
            :else
            (do (pantry/set-amount! (:id row) (- (:quantity row) need-in-row) (:unit row))
                0.0)))))))

(defn ran-out!
  "Remove every pantry row of ING-ID."
  [ing-id]
  (db/exe-db-stmt ["delete from pantry_items where ingredient_id = ?" ing-id]))

(defn log!
  "Record a cook. Returns the log id."
  [{:keys [recipe-id entry-id servings user-id]}]
  (db/insert! ["insert into cook_log (recipe_id, meal_plan_entry_id, servings, user_id, cooked_at) values (?, ?, ?, ?, ?)"
               recipe-id entry-id servings user-id (u/now-ms)]))

(defn made!
  "Apply a Made it confirmation. ITEMS are {:ingredient_id :quantity :unit :ran-out?
  :always?} (one per recipe line, amounts already scaled and edited). Always-have
  ingredients are never touched; ran-out ingredients lose every row (and go on the
  grocery list at their default purchase size when ADD-TO-GROCERY?); the rest have
  their amount deducted. Returns {:log-id :ran-out [ingredient ids] :added-to-grocery n}."
  [{:keys [recipe-id entry-id servings user-id items add-to-grocery? origin]}]
  (let [items (remove :always? items)
        out (distinct (keep #(when (:ran-out? %) (:ingredient_id %)) items))
        out-set (set out)]
    (doseq [{:keys [ingredient_id quantity unit]} items
            :when (and quantity unit (pos? quantity) (not (out-set ingredient_id)))]
      (deduct! ingredient_id quantity unit))
    (doseq [id out] (ran-out! id))
    (let [added (if add-to-grocery?
                  (count (mapv (fn [id]
                                 (let [ing (ingredients/by-id id)]
                                   (grocery/add-ingredient! {:ingredient_id id :quantity (:default_quantity ing)
                                                             :unit (:default_unit ing) :origin (or origin "recipe")
                                                             :added_by user-id})))
                               out))
                  0)]
      (when entry-id
        (db/exe-db-stmt ["update meal_plan_entries set cooked_at = ? where id = ?" (u/now-ms) entry-id]))
      {:log-id (log! {:recipe-id recipe-id :entry-id entry-id :servings servings :user-id user-id})
       :ran-out (vec out)
       :added-to-grocery added})))

(defn history
  "The cook log of RECIPE-ID, newest first."
  [recipe-id]
  (db/run-db-query [(str "select c.*, u.username from cook_log c left join users u on u.id = c.user_id"
                         " where c.recipe_id = ? order by c.cooked_at desc") recipe-id]))
