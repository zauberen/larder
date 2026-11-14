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

(ns com.larder.ingredients-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.database.db :as db]
            [com.larder.database.migration :as m]
            [com.larder.ingredients :as ing]
            [com.larder.recipes :as recipes]
            [com.larder.test-util :as tu]
            [com.larder.web-test :refer [login]]))

(use-fixtures :each tu/temp-env-fixture)

(defn- parsed [line] (select-keys (ing/parse-line line) [:quantity :unit :name :note]))

(deftest parse-line-test
  (doseq [[line q u n note]
   [["2 cups flour" 2.0 "cup" "flour" nil]
    ["1 1/2 cups milk" 1.5 "cup" "milk" nil]
    ["1½ tsp baking soda" 1.5 "tsp" "baking soda" nil]
    ["½ cup sugar" 0.5 "cup" "sugar" nil]
    ["3/4 lb ground beef" 0.75 "lb" "ground beef" nil]
    ["2.5 kg potatoes, peeled and cubed" 2.5 "kg" "potatoes" "peeled and cubed"]
    ["3 eggs" 3.0 "each" "eggs" nil]
    ["2-3 cloves garlic, minced" 3.0 "clove" "garlic" "minced"]
    ["1 to 2 tbsp olive oil" 2.0 "tbsp" "olive oil" nil]
    ["2 Tablespoons of butter" 2.0 "tbsp" "butter" nil]
    ["1 (14 oz) can diced tomatoes" 1.0 "can" "diced tomatoes" "14 oz"]
    ["8 fl. oz. cream" 8.0 "fl oz" "cream" nil]
    ["- 2 large eggs (room temperature)" 2.0 "each" "large eggs" "room temperature"]
    ["Salt and pepper to taste" nil nil "Salt and pepper" "to taste"]
    ["fresh basil, for garnish" nil nil "fresh basil" "for garnish"]
    ["2 large onions" 2.0 "each" "large onions" nil]
    ["1 l stock" 1.0 "l" "stock" nil]
    ["4 slices bread" 4.0 "slice" "bread" nil]]]
    (is (= {:quantity q :unit u :name n :note note} (parsed line)) line)))

(deftest original-text-kept-test
  (is (= "2 cups flour" (:original_text (ing/parse-line "  2 cups flour  ")))))

(deftest match-test
  (testing "the seed set is there, with densities"
    (let [flour (ing/by-id (ing/match "flour"))]
      (is (= "mass" (:dimension flour)))
      (is (= 0.53 (:density flour)))))
  (testing "case, plurals and aliases are ignored"
    (is (= (ing/match "flour") (ing/match "All-Purpose Flour")))
    (is (= (ing/match "egg") (ing/match "Eggs")))
    (is (= (ing/match "lemon") (ing/match "lemons")))
    (is (some? (ing/match "chicken stock"))))
  (testing "unknown names don't match"
    (is (nil? (ing/match "dragon fruit")))
    (is (nil? (ing/match "  ")))))

(deftest seed-runs-once-test
  (let [flour (ing/match "flour")]
    (db/exe-db-stmt ["delete from ingredients where id = ?" flour])
    (m/run-migrations (db/get-db-spec))
    (is (nil? (ing/match "flour")) "a deleted seed ingredient stays deleted")))

(deftest recipe-lines-are-matched-on-save-test
  (let [uid 1
        id (recipes/create! (first (recipes/parse-form {:title "Cake" :ingredients "2 cups flour\n3 eggs\n1 cup dragon fruit"}))
                            "draft" uid)
        lines (:ingredients (recipes/by-id id))]
    (is (= ["flour" "egg" nil] (map :ingredient_name lines)))
    (is (= [2.0 3.0 1.0] (map :quantity lines)))
    (is (= ["cup" "each" "cup"] (map :unit lines)))
    (is (= ["dragon fruit"] (map :name (recipes/unmatched-lines id))))))

(defn- post [sid uri & {:as opts}]
  (let [resp (larder/app (apply tu/request :post uri :sid sid (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(deftest picker-test
  (let [sid (login "admin")
        a (recipes/create! (first (recipes/parse-form {:title "A" :ingredients "1 cup scallions\n2 dragon fruits"})) "draft" 1)
        b (recipes/create! (first (recipes/parse-form {:title "B" :ingredients "3 scallions"})) "draft" 1)
        [scallions dragon] (recipes/unmatched-lines a)]
    (testing "the editor and detail view show unmatched lines"
      (is (str/includes? (tu/body-str (larder/app (tu/request :get (str "/app/recipes/" a "/edit") :sid sid))) "Match ingredients"))
      (is (str/includes? (tu/body-str (larder/app (tu/request :get (str "/app/recipes/" a) :sid sid))) "Not matched")))
    (testing "creating an ingredient under another name keeps the line's name as an alias"
      (let [resp (post sid (str "/api/recipes/" a "/lines/" (:id scallions) "/create")
                       :form {:name "Green onion" :dimension "count"})]
        (is (str/includes? (:text resp) "Created Green onion"))
        (is (= (ing/match "green onions") (ing/match "scallion")))))
    (testing "other recipes' lines are rematched"
      (is (empty? (recipes/unmatched-lines b))))
    (testing "choosing an existing ingredient adds an alias"
      (let [lemon (ing/match "lemon")
            resp (post sid (str "/api/recipes/" a "/lines/" (:id dragon) "/match") :form {:ingredient_id lemon})]
        (is (str/includes? (:text resp) "Matched to lemon"))
        (is (= lemon (ing/match "dragon fruit")))
        (is (empty? (recipes/unmatched-lines a)))))
    (testing "duplicates and bad input are refused"
      (let [c (recipes/create! (first (recipes/parse-form {:title "C" :ingredients "1 kiwi"})) "draft" 1)
            line (first (recipes/unmatched-lines c))]
        (is (str/includes? (:text (post sid (str "/api/recipes/" c "/lines/" (:id line) "/create")
                                        :form {:name "FLOUR" :dimension "mass"}))
                           "already an ingredient"))
        (is (str/includes? (:text (post sid (str "/api/recipes/" c "/lines/" (:id line) "/create")
                                        :form {:name "Kiwi" :dimension "weight"}))
                           "Choose mass"))
        (is (str/includes? (:text (post sid (str "/api/recipes/" c "/lines/" (:id line) "/match") :form {:ingredient_id "999"}))
                           "Choose an ingredient"))))))
