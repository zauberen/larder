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

(ns com.larder.pantry
  "Pantry stock: one row per purchase or manual entry. Stock of an ingredient is the sum
  of its rows (see `com.larder.status` for what that means for a recipe)."
  (:require [clojure.string :as str]
            [com.larder.database.db :as db]
            [com.larder.units :as units]
            [com.larder.util :as u]))

(def sources ["grocery" "manual" "mcp"])

(def max-quantity 1000000)

(defn parse-amount
  "[quantity unit errors] from form strings. Both blank means an unknown amount; a
  quantity needs a unit."
  [quantity unit]
  (let [q-str (str/trim (str quantity))
        q (when-not (str/blank? q-str) (parse-double (str/replace q-str "," ".")))
        unit (some-> unit str/trim not-empty units/normalize)]
    (cond
      (and (seq q-str) (nil? q)) [nil nil ["The quantity must be a number."]]
      (and q (not (< 0 q max-quantity))) [nil nil ["The quantity must be above zero."]]
      (and (not (str/blank? (str unit))) (nil? unit)) [nil nil ["Unknown unit."]]
      (and q (nil? unit)) [nil nil ["Choose a unit for the quantity."]]
      :else [q (when q unit) []])))

(defn add!
  "Add a pantry row. Returns its id."
  [{:keys [ingredient_id quantity unit source added_by added_at]}]
  {:pre [(some #{source} sources)]}
  (db/insert! ["insert into pantry_items (ingredient_id, quantity, unit, added_at, source, added_by) values (?, ?, ?, ?, ?, ?)"
               ingredient_id quantity (when quantity unit) (or added_at (u/now-ms)) source added_by]))

(def ^:private select-rows
  (str "select p.*, i.name as ingredient_name, i.always_have from pantry_items p"
       " join ingredients i on i.id = p.ingredient_id"))

(defn by-id
  [id]
  (first (db/run-db-query [(str select-rows " where p.id = ?") id])))

(defn search
  "Rows matching FILTERS: :q (ingredient name, or one of its aliases), :source, :from
  and :to (ISO dates, inclusive). Ordered by ingredient, then oldest first."
  [{:keys [q source from to]}]
  (let [q (some-> q str/trim not-empty str/lower-case)
        like (when q (str "%" (str/replace q #"[\\%_]" "\\\\$0") "%"))
        from-ms (u/day-start-ms from)
        to-ms (some-> (u/day-start-ms to) (+ (* 24 3600 1000)))
        where (cond-> []
                q (conj [(str "(lower(i.name) like ? escape '\\' or exists (select 1 from ingredient_aliases a"
                              " where a.ingredient_id = i.id and lower(a.alias) like ? escape '\\'))") like like])
                (some #{source} sources) (conj ["p.source = ?" source])
                from-ms (conj ["p.added_at >= ?" from-ms])
                to-ms (conj ["p.added_at < ?" to-ms]))]
    (db/run-db-query (into [(str select-rows
                                 (when (seq where) (str " where " (str/join " and " (map first where))))
                                 " order by lower(i.name), p.added_at, p.id")]
                           (mapcat rest where)))))

(defn set-amount!
  [id quantity unit]
  (db/exe-db-stmt ["update pantry_items set quantity = ?, unit = ? where id = ?" quantity (when quantity unit) id]))

(defn delete!
  "Delete the rows with IDS. Returns how many were deleted."
  [ids]
  (if (empty? ids)
    0
    (first (db/exe-db-stmt (into [(str "delete from pantry_items where id in (" (str/join "," (repeat (count ids) "?")) ")")]
                                 ids)))))

(defn rows-for
  "Pantry rows of ingredient ING-ID, oldest first."
  [ing-id]
  (db/run-db-query ["select * from pantry_items where ingredient_id = ? order by added_at, id" ing-id]))

(defn default-unit
  "The unit to offer for a new row of ING: its default purchase unit, else by dimension."
  [ing]
  (or (:default_unit ing)
      (case (:dimension ing) "mass" "g" "volume" "ml" "each")))
