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

(ns com.larder.status
  "Pantry status of recipe ingredients and recipes, computed per request.

  One ingredient line:
    :have     stock converts to the line's unit and covers it, the ingredient is
              always-have, or the line has no amount and there is stock
    :low      stock converts and is above zero but below the amount needed
    :unknown  stock exists but can't be converted or has no quantity (Have, amount
              unknown)
    :missing  no stock, or the line isn't matched to an ingredient

  A recipe: :ready (every line :have or :unknown), :none (every line :missing), :some
  (anything in between), nil for a recipe without ingredient lines."
  (:require [clojure.string :as str]
            [com.larder.database.db :as db]
            [com.larder.units :as units]))

(defn snapshot
  "The pantry and ingredient data status needs: {:stock {ing-id [rows oldest first]}
  :ingredients {ing-id ingredient}}. Take one per request."
  []
  {:stock (group-by :ingredient_id
                    (db/run-db-query ["select ingredient_id, quantity, unit from pantry_items order by added_at, id"]))
   :ingredients (into {} (map (juxt :id identity))
                      (db/run-db-query ["select id, name, density, always_have, default_quantity, default_unit, dimension from ingredients"]))})

(defn stock-total
  "Total stock of ROWS in UNIT (with DENSITY), counting only rows that convert.
  Returns [total convertible-rows other-rows]."
  [rows unit density]
  (reduce (fn [[total conv other] row]
            (if-let [q (and (:quantity row) unit (units/convert (:quantity row) (:unit row) unit density))]
              [(+ total q) (inc conv) other]
              [total conv (inc other)]))
          [0.0 0 0] rows))

(defn remaining
  "What is in stock for ROWS, for display: {:quantity :unit} in the unit of the oldest
  row with an amount (rows in other units added when they convert), or nil."
  [rows density]
  (when-let [unit (some #(when (:quantity %) (:unit %)) rows)]
    (let [[total] (stock-total rows unit density)]
      {:quantity total :unit unit})))

(defn line-status
  "{:status :remaining :always?} for recipe LINE (a recipe_ingredients row), with the
  amount needed scaled by SCALE (default 1)."
  [{:keys [stock ingredients]} line & [scale]]
  (let [ing (get ingredients (:ingredient_id line))
        rows (get stock (:ingredient_id line))
        needed (some-> (:quantity line) (* (or scale 1)))
        density (:density ing)]
    (cond
      (nil? ing) {:status :missing}
      (= 1 (:always_have ing)) {:status :have :always? true}
      (empty? rows) {:status :missing}
      :else
      (let [[total conv other] (stock-total rows (:unit line) density)
            rem (remaining rows density)]
        {:remaining rem
         :status (cond
                   (nil? needed) :have
                   (and (pos? conv) (>= (+ total 1e-9) needed)) :have
                   (pos? other) :unknown
                   (pos? total) :low
                   :else :missing)}))))

(defn recipe-status
  "The recipe tag for LINES (recipe_ingredients rows)."
  [snap lines & [scale]]
  (when (seq lines)
    (let [ss (map #(:status (line-status snap % scale)) lines)]
      (cond
        (every? #{:have :unknown} ss) :ready
        (every? #{:missing} ss) :none
        :else :some))))

(defn recipe-statuses
  "recipe id -> tag for RECIPE-IDS, with one query for their lines."
  [snap recipe-ids]
  (if (empty? recipe-ids)
    {}
    (let [lines (group-by :recipe_id
                          (db/run-db-query (into [(str "select recipe_id, ingredient_id, quantity, unit from recipe_ingredients where recipe_id in ("
                                                       (str/join "," (repeat (count recipe-ids) "?")) ")")]
                                                 recipe-ids)))]
      (into {} (for [id recipe-ids] [id (recipe-status snap (get lines id))])))))

(def tag-labels {:ready "Pantry ready" :some "Some in pantry" :none "None in pantry"})

(def line-labels {:have "Have" :low "Low" :unknown "Have, amount unknown" :missing "Missing"})

(defn grocery-amount
  "[quantity unit] to put on the grocery list for LINE: the ingredient's default purchase
  size, else the recipe amount (scaled), else nothing."
  [{:keys [ingredients]} line & [scale]]
  (let [ing (get ingredients (:ingredient_id line))]
    (cond
      (:default_quantity ing) [(:default_quantity ing) (:default_unit ing)]
      (:quantity line) [(* (:quantity line) (or scale 1)) (:unit line)]
      :else [nil nil])))
