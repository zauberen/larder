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

(ns com.larder.units-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.larder.units :as units]))

(defn- approx= [a b] (and a b (< (Math/abs (- (double a) (double b))) 0.01)))

(deftest normalize-test
  (is (= "tbsp" (units/normalize "Tablespoons")))
  (is (= "fl oz" (units/normalize "fl. oz.")))
  (is (= "cup" (units/normalize "C")))
  (is (= "can" (units/normalize "cans")))
  (is (= "package" (units/normalize "pkg")))
  (is (nil? (units/normalize "handful"))))

(deftest convert-test
  (testing "within mass and within volume"
    (is (approx= 453.59 (units/convert 1 "lb" "g")))
    (is (approx= 16 (units/convert 1 "lb" "oz")))
    (is (approx= 3 (units/convert 1 "tbsp" "tsp")))
    (is (approx= 16 (units/convert 1 "cup" "tbsp")))
    (is (approx= 4 (units/convert 1 "gallon" "quart")))
    (is (approx= 1.5 (units/convert 1500 "ml" "l"))))
  (testing "mass and volume only with a density"
    (is (nil? (units/convert 1 "cup" "g")))
    (is (approx= 125.39 (units/convert 1 "cup" "g" 0.53)))
    (is (approx= 1 (units/convert 125.39 "g" "cup" 0.53))))
  (testing "count units only to themselves"
    (is (= 3.0 (units/convert 3 "can" "can")))
    (is (nil? (units/convert 3 "can" "each")))
    (is (nil? (units/convert 3 "can" "g" 1.0))))
  (testing "missing values"
    (is (nil? (units/convert nil "g" "kg")))
    (is (nil? (units/convert 1 "g" nil)))))

(deftest format-qty-test
  (is (= "2" (units/format-qty 2.0)))
  (is (= "1½" (units/format-qty 1.5)))
  (is (= "⅓" (units/format-qty (/ 1.0 3))))
  (is (= "1.38" (units/format-qty 1.375001)))
  (is (= "0.1" (units/format-qty 0.1))))
