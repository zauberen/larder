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

(ns com.larder.mealplan-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.database.db :as db]
            [com.larder.grocery :as grocery]
            [com.larder.ingredients :as ing]
            [com.larder.mealplan :as mp]
            [com.larder.pantry :as pantry]
            [com.larder.recipes :as recipes]
            [com.larder.test-util :as tu]
            [com.larder.web-test :refer [login]])
  (:import (java.time LocalDate)))

(use-fixtures :each tu/temp-env-fixture)

(defn- d [s] (LocalDate/parse s))

(defn- recipe! [title servings lines & [status]]
  (recipes/create! (first (recipes/parse-form {:title title :servings (str servings) :ingredients (str/join "\n" lines)}))
                   (or status "published") 1))

(defn- req [method uri & {:as opts}]
  (let [resp (larder/app (apply tu/request method uri (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(deftest calendar-dates-test
  (is (= (d "2026-11-09") (first (mp/week-of (d "2026-11-13")))) "weeks start on Monday")
  (is (= (d "2026-11-15") (last (mp/week-of (d "2026-11-15")))))
  (let [grid (mp/month-grid (d "2026-11-13"))]
    (is (= (d "2026-10-26") (first grid)))
    (is (= (d "2026-12-06") (last grid)))
    (is (zero? (mod (count grid) 7)))))

(deftest entries-api-test
  (let [sid (login "admin")
        r (recipe! "Soup" 4 ["1 l water"])]
    (testing "dropping a recipe on a day returns that day's cell"
      (let [resp (req :post "/api/meal-plan/entries" :sid sid :form {:recipe_id r :date "2026-11-13"})]
        (is (str/includes? (:text resp) "id=\"day-2026-11-13\""))
        (is (str/includes? (:text resp) "Soup"))))
    (let [[entry] (mp/entries-on (d "2026-11-13"))]
      (is (= 4.0 (:servings entry)) "servings default to the recipe's")
      (testing "a day can hold several recipes"
        (req :post "/api/meal-plan/entries" :sid sid :form {:recipe_id r :date "2026-11-13"})
        (is (= [0 1] (map :position (mp/entries-on (d "2026-11-13"))))))
      (testing "moving returns both days, the old one out of band"
        (let [resp (req :post (str "/api/meal-plan/entries/" (:id entry) "/move") :sid sid :form {:date "2026-11-14"})]
          (is (re-find #"^<div class=\"mp-day(?: [^\"]*)?\" data-day=\"2026-11-14\"" (:text resp))
              "the new day comes first (it may also be today)")
          (is (re-find #"data-day=\"2026-11-13\"[^>]*hx-swap-oob=\"true\"|hx-swap-oob=\"true\"[^>]*data-day=\"2026-11-13\"" (:text resp)))
          (is (= "2026-11-14" (:date (mp/by-id (:id entry)))))))
      (testing "servings per entry"
        (req :post (str "/api/meal-plan/entries/" (:id entry) "/servings") :sid sid :form {:servings "6"})
        (is (= 6.0 (:servings (mp/by-id (:id entry))))))
      (testing "removing"
        (req :post (str "/api/meal-plan/entries/" (:id entry) "/delete") :sid sid)
        (is (nil? (mp/by-id (:id entry))))))
    (testing "bad input"
      (is (= 400 (:status (req :post "/api/meal-plan/entries" :sid sid :form {:recipe_id r :date "someday"}))))
      (is (= 400 (:status (req :post "/api/meal-plan/entries" :sid sid :form {:recipe_id 999 :date "2026-11-13"})))))
    (testing "visitors can't plan"
      (is (= 302 (:status (req :post "/api/meal-plan/entries" :form {:recipe_id r :date "2026-11-13"})))))))

(deftest page-test
  (let [sid (login "admin")
        r (recipe! "Soup" 4 ["1 l water"])
        _ (recipe! "Secret" 2 ["1 egg"] "draft")]
    (mp/add! r (d "2026-11-13"))
    (let [week (:text (req :get "/app/meal-plan" :sid sid :query "date=2026-11-13"))]
      (is (str/includes? week "Nov 9 – 15, 2026"))
      (is (str/includes? week "data-recipe-id"))
      (is (not (str/includes? week "Secret")) "only published recipes are in the bar")
      (is (str/includes? week "None in pantry"))
      (is (str/includes? week "id=\"day-2026-11-13\"")))
    (let [month (:text (req :get "/app/meal-plan" :sid sid :query "view=month&date=2026-11-13"))]
      (is (str/includes? month "November 2026"))
      (is (str/includes? month "day-2026-10-26")))))

(deftest add-to-grocery-test
  (let [soup (recipe! "Soup" 4 ["2 cups milk" "1 onion" "1 tsp salt" "1 dragon fruit"])
        bake (recipe! "Bake" 2 ["250 ml milk" "2 eggs" "flour, for dusting"])
        from (d "2026-11-09") to (d "2026-11-15")]
    (db/exe-db-stmt ["update ingredients set always_have = 1 where id = ?" (ing/match "salt")])
    (db/exe-db-stmt ["update ingredients set default_quantity = 1, default_unit = 'kg' where id = ?" (ing/match "flour")])
    (mp/add! soup (d "2026-11-10") 8)            ; doubled: 4 cups milk, 2 onions
    (mp/add! bake (d "2026-11-11"))              ; 250 ml milk, 2 eggs
    (let [cooked (mp/add! bake (d "2026-11-12"))]
      (db/exe-db-stmt ["update meal_plan_entries set cooked_at = 1 where id = ?" cooked]))
    (mp/add! bake (d "2026-11-20"))              ; outside the range
    (pantry/add! {:ingredient_id (ing/match "milk") :quantity 500.0 :unit "ml" :source "manual"})
    (pantry/add! {:ingredient_id (ing/match "egg") :quantity nil :unit nil :source "manual"})
    (grocery/add-ingredient! {:ingredient_id (ing/match "onion") :quantity 1.0 :unit "each"})
    (let [result (mp/add-to-grocery! from to 1)
          items (into {} (map (juxt #(or (:ingredient_name %) (:text %)) (juxt :quantity :unit :origin)) (grocery/open-items)))]
      (testing "need minus pantry minus the list, in the first line's unit"
        ;; 4 cups (946.35 ml) + 250 ml - 500 ml = 696.35 ml = 2.94 cups
        (is (< (Math/abs (- 2.943 (first (items "milk")))) 0.01))
        (is (= ["cup" "mealplan"] (rest (items "milk"))))
        (is (= [2.0 "each" "manual"] (items "onion")) "1 listed + 1 more"))
      (testing "an unknown pantry amount counts as covering"
        (is (nil? (items "egg"))))
      (testing "an amount that can't be summed adds the default purchase size once"
        (is (= [1.0 "kg" "mealplan"] (items "flour"))))
      (testing "always-have, cooked and out-of-range entries are skipped; unmatched lines go to Misc"
        (is (nil? (items "salt")))
        (is (= [nil nil "mealplan"] (items "1 dragon fruit"))))
      (is (= 4 (:added result)))
      (testing "running it again adds nothing"
        (is (zero? (:added (mp/add-to-grocery! from to 1))))))))

(deftest made-from-plan-test
  (let [sid (login "admin")
        r (recipe! "Soup" 4 ["1 l water"])
        e (mp/add! r (d "2026-11-13"))]
    (is (str/includes? (:text (req :get (str "/api/recipes/" r "/made") :sid sid :query (str "entry=" e "&servings=4")))
                       (str "name=\"entry\" type=\"hidden\" value=\"" e "\"")))
    (let [resp (req :post (str "/api/recipes/" r "/made") :sid sid :form {:entry e :servings "4"})]
      (is (str/includes? (get-in resp [:headers "HX-Trigger"]) "recipe-cooked")))
    (is (some? (:cooked_at (mp/by-id e))))
    (is (= e (:meal_plan_entry_id (first (db/run-db-query ["select * from cook_log"])))))
    (is (str/includes? (:text (req :get "/app/meal-plan" :sid sid :query "date=2026-11-13")) "Cooked"))))
