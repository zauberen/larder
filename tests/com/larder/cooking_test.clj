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

(ns com.larder.cooking-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.cooking :as cooking]
            [com.larder.database.db :as db]
            [com.larder.grocery :as grocery]
            [com.larder.ingredients :as ing]
            [com.larder.pantry :as pantry]
            [com.larder.recipes :as recipes]
            [com.larder.test-util :as tu]
            [com.larder.util :as u]
            [com.larder.web-test :refer [login]]))

(use-fixtures :each tu/temp-env-fixture)

(defn- stock! [name q unit & [age-days]]
  (pantry/add! {:ingredient_id (ing/match name) :quantity q :unit unit :source "manual"
                :added_at (- (u/now-ms) (* (or age-days 0) 24 3600 1000))}))

(defn- rows [name] (map (juxt :quantity :unit) (pantry/rows-for (ing/match name))))

(defn- approx-rows [name] (map (fn [[q unit]] [(some-> q (* 100) Math/round (/ 100.0)) unit]) (rows name)))

(deftest deduct-test
  (testing "oldest rows first; rows reaching zero are removed"
    (stock! "flour" 300.0 "g" 2)
    (stock! "flour" 1.0 "kg" 1)
    (is (zero? (cooking/deduct! (ing/match "flour") 500.0 "g")))
    (is (= [[0.8 "kg"]] (approx-rows "flour"))))
  (testing "volume from mass through the density"
    (stock! "sugar" 1.0 "kg")
    (cooking/deduct! (ing/match "sugar") 1.0 "cup")
    (is (= [[0.8 "kg"]] (approx-rows "sugar"))))
  (testing "rows without a quantity or in units that don't convert are left alone"
    (stock! "egg" nil nil 3)
    (stock! "egg" 1.0 "package" 2)
    (stock! "egg" 6.0 "each" 1)
    (is (zero? (cooking/deduct! (ing/match "egg") 4.0 "each")))
    (is (= [[nil nil] [1.0 "package"] [2.0 "each"]] (rows "egg"))))
  (testing "taking more than there is empties what converts and reports the rest"
    (is (= 3.0 (cooking/deduct! (ing/match "egg") 5.0 "each")))
    (is (= [[nil nil] [1.0 "package"]] (rows "egg")))))

(deftest made-test
  (stock! "flour" 1.0 "kg")
  (stock! "milk" 1.0 "l")
  (stock! "butter" 250.0 "g")
  (db/exe-db-stmt ["update ingredients set always_have = 1 where id = ?" (ing/match "salt")])
  (stock! "salt" 500.0 "g")
  (db/exe-db-stmt ["update ingredients set default_quantity = 500, default_unit = 'g' where id = ?" (ing/match "butter")])
  (let [item (fn [name q unit & [out?]] {:ingredient_id (ing/match name) :quantity q :unit unit :ran-out? out?
                                          :always? (= name "salt")})
        result (cooking/made! {:recipe-id nil :servings 4 :user-id 1 :add-to-grocery? true
                               :items [(item "flour" 200.0 "g") (item "milk" 2.0 "cup")
                                       (item "butter" 50.0 "g" true) (item "salt" 1.0 "tsp")]})]
    (is (= [[0.8 "kg"]] (approx-rows "flour")))
    (is (= [[0.53 "l"]] (approx-rows "milk")))
    (testing "Ran out removes every row and adds the default purchase size to the grocery list"
      (is (empty? (rows "butter")))
      (is (= [(ing/match "butter")] (:ran-out result)))
      (is (= [["butter" 500.0 "g"]] (map (juxt :ingredient_name :quantity :unit) (grocery/open-items)))))
    (testing "always-have ingredients aren't deducted"
      (is (= [[500.0 "g"]] (rows "salt"))))
    (testing "the cook is logged"
      (is (= 1 (count (db/run-db-query ["select * from cook_log where user_id = 1 and servings = 4.0"])))))))

(defn- req [method uri & {:as opts}]
  (let [resp (larder/app (apply tu/request method uri (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(deftest made-dialog-test
  (stock! "flour" 1.0 "kg")
  (stock! "egg" 12.0 "each")
  (let [id (recipes/create! (first (recipes/parse-form {:title "Pancakes" :servings "4"
                                                        :ingredients "200 g flour\n2 eggs\n1 dragon fruit"}))
                            "published" 1)
        lines (:ingredients (recipes/by-id id))
        [flour-line egg-line] lines
        sid (login "admin")]
    (testing "the dialog lists matched lines with amounts scaled to the servings"
      (let [body (:text (req :get (str "/api/recipes/" id "/made") :sid sid :query "servings=8"))]
        (is (str/includes? body "value=\"400\""))
        (is (str/includes? body "value=\"4\""))
        (is (str/includes? body "Ran out"))
        (is (str/includes? body "1 line isn"))))
    (testing "a bad amount keeps the dialog open"
      (let [resp (req :post (str "/api/recipes/" id "/made") :sid sid
                      :form {:servings "4" (keyword (str "q_" (:id flour-line))) "lots" (keyword (str "u_" (:id flour-line))) "g"})]
        (is (str/includes? (:text resp) "flour: The quantity must be a number"))
        (is (= [[1.0 "kg"]] (rows "flour")))))
    (testing "confirming deducts the edited amounts and handles Ran out"
      (let [resp (req :post (str "/api/recipes/" id "/made") :sid sid
                      :form {:servings "4"
                             (keyword (str "q_" (:id flour-line))) "250" (keyword (str "u_" (:id flour-line))) "g"
                             (keyword (str "q_" (:id egg-line))) "2" (keyword (str "u_" (:id egg-line))) "each"
                             (keyword (str "out_" (:id egg-line))) "1"
                             :add_to_grocery "1"})]
        (is (str/includes? (get-in resp [:headers "HX-Trigger"]) "pantry-changed"))
        (is (str/includes? (:text resp) "Made Pancakes"))
        (is (str/includes? (:text resp) "1 ran out"))
        (is (= [[0.75 "kg"]] (approx-rows "flour")))
        (is (empty? (rows "egg")) "Ran out wins over the arithmetic")
        (is (= "egg" (:ingredient_name (first (grocery/open-items)))) "what ran out goes on the grocery list")
        (is (= 1 (count (cooking/history id))))))
    (testing "visitors can't"
      (is (= 302 (:status (req :post (str "/api/recipes/" id "/made"))))))))
