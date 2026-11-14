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

(ns com.larder.units
  "Units and conversion. Quantities convert freely within mass and within volume, between
  the two only with an ingredient's density (grams per millilitre), and count units only
  to themselves."
  (:require [clojure.string :as str]))

(def mass
  "Grams per unit."
  {"g" 1.0 "kg" 1000.0 "oz" 28.349523125 "lb" 453.59237})

(def volume
  "Millilitres per unit (US customary measures)."
  {"ml" 1.0 "l" 1000.0 "tsp" 4.92892159375 "tbsp" 14.78676478125 "cup" 236.5882365
   "fl oz" 29.5735295625 "pint" 473.176473 "quart" 946.352946 "gallon" 3785.411784})

(def count-units
  ["each" "can" "bunch" "clove" "package" "piece" "slice" "stick" "head" "sprig" "jar"
   "bottle" "bag" "box" "pinch" "dash"])

(def all-units
  "Canonical units in the order unit selects list them."
  (concat ["g" "kg" "oz" "lb" "ml" "l" "tsp" "tbsp" "cup" "fl oz" "pint" "quart" "gallon"] count-units))

(def dimensions ["mass" "volume" "count"])

(defn dimension
  "\"mass\", \"volume\" or \"count\" for canonical UNIT, nil for unknown units."
  [unit]
  (cond (contains? mass unit) "mass"
        (contains? volume unit) "volume"
        (some #{unit} count-units) "count"))

(def ^:private aliases
  "Lower-case spellings -> canonical unit. Plurals of count units are added below."
  (merge
   {"g" "g" "gr" "g" "gram" "g" "grams" "g" "gramme" "g" "grammes" "g"
    "kg" "kg" "kgs" "kg" "kilo" "kg" "kilos" "kg" "kilogram" "kg" "kilograms" "kg"
    "oz" "oz" "ounce" "oz" "ounces" "oz"
    "lb" "lb" "lbs" "lb" "pound" "lb" "pounds" "lb"
    "ml" "ml" "mls" "ml" "milliliter" "ml" "milliliters" "ml" "millilitre" "ml" "millilitres" "ml"
    "l" "l" "liter" "l" "liters" "l" "litre" "l" "litres" "l"
    "tsp" "tsp" "tsps" "tsp" "teaspoon" "tsp" "teaspoons" "tsp"
    "tbsp" "tbsp" "tbsps" "tbsp" "tbs" "tbsp" "tbl" "tbsp" "tablespoon" "tbsp" "tablespoons" "tbsp"
    "cup" "cup" "cups" "cup" "c" "cup"
    "fl oz" "fl oz" "fl. oz" "fl oz" "fl. oz." "fl oz" "floz" "fl oz" "fluid ounce" "fl oz" "fluid ounces" "fl oz"
    "pint" "pint" "pints" "pint" "pt" "pint" "pts" "pint"
    "quart" "quart" "quarts" "quart" "qt" "quart" "qts" "quart"
    "gallon" "gallon" "gallons" "gallon" "gal" "gallon" "gals" "gallon"
    "ea" "each" "pkg" "package" "pkgs" "package" "packet" "package" "packets" "package"
    "pack" "package" "packs" "package" "pieces" "piece" "pc" "piece" "pcs" "piece"
    "boxes" "box" "bunches" "bunch" "pinches" "pinch" "dashes" "dash"}
   (into {} (for [u count-units] [u u]))
   (into {} (for [u count-units :when (not (#{"box" "bunch" "pinch" "dash" "each"} u))] [(str u "s") u]))))

(defn normalize
  "The canonical unit for a spelling such as \"Tablespoons\" or \"fl. oz.\", or nil."
  [s]
  (when s
    (let [k (-> (str/lower-case (str/trim s)) (str/replace #"\s+" " "))]
      (or (get aliases k) (get aliases (str/replace k #"\.$" ""))))))

(def alias-pattern
  "Regex alternation of every unit spelling, longest first, for the line parser."
  (str/join "|" (map #(java.util.regex.Pattern/quote %) (sort-by (comp - count) (keys aliases)))))

(defn convert
  "QTY of FROM expressed in TO, or nil when they can't be converted. DENSITY (grams per
  millilitre) bridges mass and volume."
  [qty from to & [density]]
  (when (and qty from to)
    (let [df (dimension from) dt (dimension to)]
      (cond
        (= from to) (double qty)
        (and (= df dt) (not= "count" df))
        (let [table (if (= "mass" df) mass volume)]
          (/ (* qty (table from)) (table to)))
        (and density (pos? density) (= #{"mass" "volume"} (hash-set df dt)))
        (if (= "volume" df)
          (/ (* qty (volume from) density) (mass to))
          (/ (/ (* qty (mass from)) density) (volume to)))
        :else nil))))

(defn format-qty
  "A quantity for display: whole numbers without decimals, common fractions as such,
  otherwise up to two decimals."
  [q]
  (when q
    (let [q (double q)
          whole (long (Math/floor q))
          frac (- q whole)
          nice {0.25 "¼" 0.5 "½" 0.75 "¾" 0.333 "⅓" 0.667 "⅔" 0.125 "⅛"}
          match (some (fn [[v s]] (when (< (Math/abs (- frac v)) 0.01) s)) nice)]
      (cond
        (< frac 0.005) (str whole)
        (> frac 0.995) (str (inc whole))
        match (if (zero? whole) match (str whole match))
        :else (-> (format "%.2f" q) (str/replace #"0+$" "") (str/replace #"\.$" ""))))))
