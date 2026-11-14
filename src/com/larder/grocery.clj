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

(ns com.larder.grocery
  "The grocery list. Grocery items name an ingredient and go into the pantry when bought;
  misc items are free text. Completed items stay for a day so they can be undone."
  (:require [clojure.string :as str]
            [com.larder.database.db :as db]
            [com.larder.ingredients :as ingredients]
            [com.larder.pantry :as pantry]
            [com.larder.units :as units]
            [com.larder.util :as u]))

(def sections ["grocery" "misc"])
(def origins ["manual" "recipe" "mealplan" "mcp"])

(def keep-done-ms
  "Completed items are cleared after this long."
  (* 24 3600 1000))

(def ^:private select-items
  (str "select g.*, i.name as ingredient_name, i.density, i.default_quantity as ingredient_default_quantity,"
       " i.default_unit as ingredient_default_unit, i.dimension, s.name as store_name"
       " from grocery_items g left join ingredients i on i.id = g.ingredient_id"
       " left join stores s on s.id = g.store_id"))

(defn label
  "What an item is called: its ingredient's name or its text."
  [item]
  (or (:ingredient_name item) (:text item)))

(defn by-id
  [id]
  (first (db/run-db-query [(str select-items " where g.id = ?") id])))

(defn clear-old-done!
  "Remove items completed more than `keep-done-ms` ago."
  []
  (db/exe-db-stmt ["delete from grocery_items where done_at is not null and done_at < ?" (- (u/now-ms) keep-done-ms)]))

(defn open-items
  "Open items, by store then name."
  []
  (db/run-db-query [(str select-items " where g.done_at is null"
                         " order by s.name is not null, lower(s.name), lower(coalesce(i.name, g.text)), g.id")]))

(defn done-items
  "Items completed within the last day, newest first."
  []
  (clear-old-done!)
  (db/run-db-query [(str select-items " where g.done_at is not null order by g.done_at desc, g.id desc")]))

(defn- open-item-for
  [ing-id]
  (first (db/run-db-query [(str select-items " where g.done_at is null and g.section = 'grocery' and g.ingredient_id = ?"
                                " order by g.id limit 1") ing-id])))

(defn add-ingredient!
  "Add ingredient ING-ID with an optional default QUANTITY and UNIT. When the ingredient
  already has an open item, that item's amount is raised instead (if the units convert,
  using the ingredient's density; an item without an amount takes the new one). Units
  that don't convert get a separate item. Returns {:id :merged? bool}."
  [{:keys [ingredient_id quantity unit store_id origin added_by]}]
  {:pre [(some #{(or origin "manual")} origins)]}
  (let [existing (open-item-for ingredient_id)
        density (:density (ingredients/by-id ingredient_id))
        unit (when quantity unit)
        set-store! (fn [id] (when (and store_id (nil? (:store_id existing)))
                              (db/exe-db-stmt ["update grocery_items set store_id = ? where id = ?" store_id id])))]
    (cond
      (and existing (nil? quantity))
      (do (set-store! (:id existing)) {:id (:id existing) :merged? true})

      (and existing (nil? (:quantity existing)))
      (do (db/exe-db-stmt ["update grocery_items set quantity = ?, unit = ? where id = ?" quantity unit (:id existing)])
          (set-store! (:id existing))
          {:id (:id existing) :merged? true})

      (and existing (units/convert quantity unit (:unit existing) density))
      (do (db/exe-db-stmt ["update grocery_items set quantity = ? where id = ?"
                           (+ (:quantity existing) (units/convert quantity unit (:unit existing) density)) (:id existing)])
          (set-store! (:id existing))
          {:id (:id existing) :merged? true})

      :else
      {:id (db/insert! ["insert into grocery_items (section, ingredient_id, quantity, unit, store_id, origin, added_by, added_at) values ('grocery', ?, ?, ?, ?, ?, ?, ?)"
                        ingredient_id quantity unit store_id (or origin "manual") added_by (u/now-ms)])
       :merged? false})))

(defn add-misc!
  "Add a free-text item. Returns its id."
  [{:keys [text store_id origin added_by]}]
  (db/insert! ["insert into grocery_items (section, text, store_id, origin, added_by, added_at) values ('misc', ?, ?, ?, ?, ?)"
               (str/trim text) store_id (or origin "manual") added_by (u/now-ms)]))

(defn purchase-default
  "[quantity unit] to prefill the size dialog: the item's default amount, else the
  ingredient's default purchase size, else no quantity and a unit suiting the ingredient."
  [item]
  (cond
    (:quantity item) [(:quantity item) (:unit item)]
    (:ingredient_default_quantity item) [(:ingredient_default_quantity item) (:ingredient_default_unit item)]
    :else [nil (pantry/default-unit {:default_unit (:ingredient_default_unit item) :dimension (:dimension item)})]))

(defn complete!
  "Mark open ITEM done. A grocery item adds a pantry row with QUANTITY and UNIT (both nil
  for Skip amount: amount unknown) recorded as coming from PANTRY-SOURCE (default
  grocery). The item is claimed first, so a double submit adds one row. Returns
  {:completed? bool :pantry-id id-or-nil}."
  [item quantity unit user-id & [pantry-source]]
  (let [[claimed] (db/exe-db-stmt ["update grocery_items set done_at = ?, purchased_quantity = ?, purchased_unit = ? where id = ? and done_at is null"
                                   (u/now-ms) quantity (when quantity unit) (:id item)])]
    (if (zero? claimed)
      {:completed? false :pantry-id nil}
      (let [pantry-id (when (= "grocery" (:section item))
                        (pantry/add! {:ingredient_id (:ingredient_id item) :quantity quantity :unit unit
                                      :source (or pantry-source "grocery") :added_by user-id}))]
        (when pantry-id
          (db/exe-db-stmt ["update grocery_items set pantry_item_id = ? where id = ?" pantry-id (:id item)]))
        {:completed? true :pantry-id pantry-id}))))

(defn undo!
  "Reopen completed ITEM and remove the pantry row its completion created."
  [item]
  (when-let [pid (:pantry_item_id item)]
    (pantry/delete! [pid]))
  (db/exe-db-stmt ["update grocery_items set done_at = null, purchased_quantity = null, purchased_unit = null, pantry_item_id = null where id = ?"
                   (:id item)]))

(defn set-store!
  [id store-id]
  (db/exe-db-stmt ["update grocery_items set store_id = ? where id = ?" store-id id]))

(defn delete!
  [id]
  (db/exe-db-stmt ["delete from grocery_items where id = ?" id]))

(defn clear-done!
  "Remove every completed item now."
  []
  (db/exe-db-stmt ["delete from grocery_items where done_at is not null"]))

(defn amount-text
  "\"2 cups\" for an item's default amount, or nil."
  [quantity unit]
  (when quantity (str (units/format-qty quantity) " " unit)))

(defn open-item-for-ingredient?
  [ing-id]
  (boolean (seq (db/run-db-query ["select 1 from grocery_items where done_at is null and section = 'grocery' and ingredient_id = ?" ing-id]))))

(defn open-misc-text?
  "True when an open misc item reads TEXT (ignoring case)."
  [text]
  (boolean (seq (db/run-db-query ["select 1 from grocery_items where done_at is null and section = 'misc' and lower(text) = lower(?)" (str/trim text)]))))
