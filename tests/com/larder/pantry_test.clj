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

(ns com.larder.pantry-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.ingredients :as ing]
            [com.larder.pantry :as pantry]
            [com.larder.recipes :as recipes]
            [com.larder.test-util :as tu]
            [com.larder.util :as u]
            [com.larder.web-test :refer [login]]))

(use-fixtures :each tu/temp-env-fixture)

(defn- post [sid uri & {:as opts}]
  (let [resp (larder/app (apply tu/request :post uri :sid sid (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(defn- get-page [uri & opts]
  (let [resp (larder/app (apply tu/request :get uri opts))]
    (assoc resp :text (tu/body-str resp))))

(def day (* 24 3600 1000))

(deftest parse-amount-test
  (is (= [2.5 "kg" []] (pantry/parse-amount "2,5" "kg")))
  (is (= [nil nil []] (pantry/parse-amount "" "")) "unknown amounts are allowed")
  (is (= [nil nil []] (pantry/parse-amount "" "kg")) "a unit alone is dropped")
  (is (seq (last (pantry/parse-amount "lots" "g"))))
  (is (seq (last (pantry/parse-amount "-1" "g"))))
  (is (seq (last (pantry/parse-amount "2" ""))) "a quantity needs a unit")
  (is (seq (last (pantry/parse-amount "2" "handful")))))

(deftest search-test
  (let [flour (ing/match "flour") milk (ing/match "milk")
        now (u/now-ms)
        a (pantry/add! {:ingredient_id flour :quantity 1000.0 :unit "g" :source "grocery" :added_at (- now (* 10 day))})
        b (pantry/add! {:ingredient_id milk :quantity 1.0 :unit "l" :source "manual" :added_at now})
        ids #(map :id (pantry/search %))]
    (is (= [a b] (ids {})))
    (is (= [a] (ids {:q "FLO"})))
    (is (= [a] (ids {:q "plain"})) "aliases are searched too")
    (is (= [b] (ids {:source "manual"})))
    (is (= [b] (ids {:from (u/format-date (- now day))})))
    (is (= [a] (ids {:to (u/format-date (- now (* 5 day)))})))
    (is (= [a b] (ids {:from "nonsense"})) "bad dates are ignored")))

(deftest add-test
  (let [sid (login "admin")]
    (testing "an existing ingredient by name or alias"
      (post sid "/api/pantry" :form {:ingredient "Plain flour" :quantity "1.5" :unit "kg"})
      (let [[row] (pantry/search {})]
        (is (= ["flour" 1.5 "kg" "manual"] ((juxt :ingredient_name :quantity :unit :source) row)))
        (is (= 1 (:added_by row)))))
    (testing "a new name creates the ingredient, guessing its dimension from the unit"
      (let [rid (recipes/create! (first (recipes/parse-form {:title "Salad" :ingredients "1 bunch watercress"})) "draft" 1)
            resp (post sid "/api/pantry" :form {:ingredient "Watercress" :quantity "" :unit ""})]
        (is (= "pantry-changed" (get-in resp [:headers "HX-Trigger"])))
        (is (= "count" (:dimension (ing/by-id (ing/match "watercress")))))
        (is (empty? (recipes/unmatched-lines rid)) "unmatched recipe lines are rematched")
        (is (nil? (:quantity (first (pantry/search {:q "watercress"})))) "unknown amount")))
    (testing "validation"
      (is (str/includes? (:text (post sid "/api/pantry" :form {:ingredient "" :quantity "2" :unit "g"})) "Enter an ingredient"))
      (is (str/includes? (:text (post sid "/api/pantry" :form {:ingredient "Rice" :quantity "2" :unit ""})) "Choose a unit")))))

(deftest page-and-filters-test
  (let [sid (login "admin")]
    (pantry/add! {:ingredient_id (ing/match "flour") :quantity 500.0 :unit "g" :source "grocery"})
    (pantry/add! {:ingredient_id (ing/match "milk") :quantity 1.0 :unit "l" :source "manual"})
    (let [body (:text (get-page "/app/pantry" :sid sid))]
      (is (str/includes? body "flour"))
      (is (str/includes? body "data-select-all")))
    (let [body (:text (get-page "/app/pantry" :sid sid :query "q=mil&source=manual"))]
      (is (str/includes? body "milk"))
      (is (not (str/includes? body ">flour<"))))
    (is (= 302 (:status (get-page "/app/pantry"))) "visitors are sent to log in")))

(deftest in-place-edit-test
  (let [sid (login "admin")
        id (pantry/add! {:ingredient_id (ing/match "flour") :quantity 500.0 :unit "g" :source "manual"})]
    (let [resp (post sid (str "/api/pantry/" id) :form {:quantity "2" :unit "lb"})]
      (is (str/includes? (:text resp) (str "pantry-" id)))
      (is (= [2.0 "lb"] ((juxt :quantity :unit) (pantry/by-id id)))))
    (testing "an invalid amount is flagged and not saved"
      (let [resp (post sid (str "/api/pantry/" id) :form {:quantity "-3" :unit "lb"})]
        (is (str/includes? (:text resp) "aria-invalid=\"true\""))
        (is (= 2.0 (:quantity (pantry/by-id id))))))
    (testing "clearing the quantity makes the amount unknown"
      (post sid (str "/api/pantry/" id) :form {:quantity "" :unit "lb"})
      (is (= [nil nil] ((juxt :quantity :unit) (pantry/by-id id)))))))

(deftest bulk-delete-test
  (let [sid (login "admin")
        flour (ing/match "flour")
        ids (vec (for [_ (range 3)] (pantry/add! {:ingredient_id flour :quantity 1.0 :unit "kg" :source "manual"})))
        resp (post sid "/api/pantry/delete" :form {:ids (take 2 ids) :filters "q=flour"})]
    (is (= [(last ids)] (map :id (pantry/search {}))))
    (is (str/includes? (:text resp) "pantry-table"))))

(deftest ingredient-dialog-test
  (let [sid (login "admin")
        flour (ing/match "flour")]
    (is (str/includes? (:text (get-page (str "/app/ingredients/" flour "/edit") :sid sid)) "Default purchase size"))
    (testing "saving"
      (let [resp (post sid (str "/api/ingredients/" flour)
                       :form {:name "Flour" :dimension "mass" :density "0.55" :default_quantity "1.5"
                              :default_unit "kg" :always_have "1" :aliases "plain flour, AP flour"})
            saved (ing/by-id flour)]
        (is (= "ingredients-changed" (get-in resp [:headers "HX-Trigger"])))
        (is (= ["Flour" 0.55 1.5 "kg" 1] ((juxt :name :density :default_quantity :default_unit :always_have) saved)))
        (is (= ["AP flour" "plain flour"] (ing/aliases flour)))
        (is (nil? (ing/match "all-purpose flour")) "removed aliases no longer match")))
    (testing "validation"
      (is (str/includes? (:text (post sid (str "/api/ingredients/" flour) :form {:name "Flour" :dimension "mass" :aliases "milk"}))
                         "already names another"))
      (is (str/includes? (:text (post sid (str "/api/ingredients/" flour) :form {:name "Sugar" :dimension "mass"}))
                         "already an ingredient"))
      (is (str/includes? (:text (post sid (str "/api/ingredients/" flour) :form {:name "Flour" :dimension "mass" :default_quantity "2"}))
                         "Choose a unit")))
    (is (= 404 (:status (get-page "/app/ingredients/9999/edit" :sid sid))))))
