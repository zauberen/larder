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

(ns com.larder.status-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.database.db :as db]
            [com.larder.grocery :as grocery]
            [com.larder.ingredients :as ing]
            [com.larder.pantry :as pantry]
            [com.larder.recipes :as recipes]
            [com.larder.status :as status]
            [com.larder.test-util :as tu]
            [com.larder.web-test :refer [login]]))

(use-fixtures :each tu/temp-env-fixture)

(defn- stock! [name q unit]
  (pantry/add! {:ingredient_id (ing/match name) :quantity q :unit unit :source "manual"}))

(defn- recipe! [title lines & [status]]
  (recipes/create! (first (recipes/parse-form {:title title :ingredients (str/join "\n" lines)})) (or status "published") 1))

(defn- line-statuses [id]
  (let [snap (status/snapshot)]
    (mapv #(:status (status/line-status snap %)) (:ingredients (recipes/by-id id)))))

(deftest line-status-test
  (testing "Have: stock converts and covers the amount"
    (stock! "flour" 1.0 "kg")
    (is (= [:have] (line-statuses (recipe! "A" ["2 cups flour"]))) "density bridges cups and kg"))
  (testing "Low: converts but not enough"
    (stock! "milk" 100.0 "ml")
    (is (= [:low] (line-statuses (recipe! "B" ["1 cup milk"])))))
  (testing "rows add up"
    (stock! "milk" 200.0 "ml")
    (is (= [:have] (line-statuses (recipe! "C" ["1 cup milk"])))))
  (testing "Have, amount unknown: a row without a quantity, or one that can't convert"
    (stock! "egg" nil nil)
    (is (= [:unknown] (line-statuses (recipe! "D" ["3 eggs"]))))
    (stock! "garlic" 1.0 "head")
    (is (= [:unknown] (line-statuses (recipe! "E" ["2 cloves garlic"])))))
  (testing "Missing: no stock, or unmatched"
    (is (= [:missing :missing] (line-statuses (recipe! "F" ["1 tbsp honey" "1 dragon fruit"])))))
  (testing "always-have counts as Have without stock"
    (db/exe-db-stmt ["update ingredients set always_have = 1 where id = ?" (ing/match "salt")])
    (is (= [:have] (line-statuses (recipe! "G" ["1 tsp salt"])))))
  (testing "a line without an amount is Have when there is stock"
    (is (= [:have] (line-statuses (recipe! "H" ["flour, for dusting"])))))
  (testing "remaining is shown in the pantry's unit"
    (let [snap (status/snapshot)
          line (first (:ingredients (recipes/by-id (recipe! "I" ["1 cup flour"]))))]
      (is (= {:quantity 1.0 :unit "kg"} (:remaining (status/line-status snap line)))))))

(deftest recipe-status-test
  (stock! "flour" 1.0 "kg")
  (stock! "egg" nil nil)
  (let [ready (recipe! "Ready" ["1 cup flour" "2 eggs"])
        some (recipe! "Some" ["1 cup flour" "1 cup sugar"])
        none (recipe! "None" ["1 cup sugar" "1 dragon fruit"])
        empty (recipe! "Empty" [])
        tags (status/recipe-statuses (status/snapshot) [ready some none empty])]
    (is (= {ready :ready some :some none :none empty nil} tags))))

(defn- req [method uri & {:as opts}]
  (let [resp (larder/app (apply tu/request method uri (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(deftest tags-on-pages-test
  (stock! "flour" 1.0 "kg")
  (let [ready (recipe! "Bread" ["2 cups flour"])
        _ (recipe! "Cake" ["2 cups flour" "1 cup sugar"])
        sid (login "admin")]
    (testing "the public dashboard and app list show Pantry ready only"
      (doseq [body [(:text (req :get "/")) (:text (req :get "/app" :sid sid))]]
        (is (= 1 (count (re-seq #"Pantry ready" body))))
        (is (not (str/includes? body "Some in pantry")))))
    (testing "visitors see the tag but no amounts"
      (let [body (:text (req :get (str "/recipes/" ready)))]
        (is (str/includes? body "Pantry ready"))
        (is (not (str/includes? body "in pantry<")))
        (is (not (str/includes? body "Have")))))
    (testing "the app detail shows each line's status and stock"
      (let [body (:text (req :get (str "/app/recipes/" ready) :sid sid))]
        (is (str/includes? body ">Have<"))
        (is (str/includes? body "1 kg in pantry"))))))

(deftest grocery-from-recipe-test
  (stock! "flour" 1.0 "kg")
  (stock! "milk" 100.0 "ml")
  (db/exe-db-stmt ["update ingredients set default_quantity = 2, default_unit = 'kg' where id = ?" (ing/match "sugar")])
  (let [id (recipe! "Cake" ["2 cups flour" "1 cup milk" "1 cup sugar" "3 eggs" "1 dragon fruit"])
        sid (login "admin")
        lines (:ingredients (recipes/by-id id))
        line-of #(first (filter (fn [l] (str/includes? (:original_text l) %)) lines))]
    (testing "the grocery icon adds one ingredient: the default purchase size, else the recipe amount"
      (req :post (str "/api/recipes/" id "/lines/" (:id (line-of "sugar")) "/grocery") :sid sid)
      (req :post (str "/api/recipes/" id "/lines/" (:id (line-of "eggs")) "/grocery") :sid sid)
      (let [items (grocery/open-items)]
        (is (= #{["sugar" 2.0 "kg" "recipe"] ["egg" 3.0 "each" "recipe"]}
               (set (map (juxt :ingredient_name :quantity :unit :origin) items))))))
    (testing "Add missing adds Missing and Low lines not on the list yet; unmatched lines go to Misc"
      (let [resp (req :post (str "/api/recipes/" id "/grocery") :sid sid)]
        (is (str/includes? (:text resp) "Added 2 items"))
        (is (str/includes? (:text resp) "2 already on it")))
      (let [items (grocery/open-items)]
        (is (some #(= ["milk" 1.0 "cup"] ((juxt :ingredient_name :quantity :unit) %)) items) "Low lines too")
        (is (some #(= "1 dragon fruit" (:text %)) items))
        (is (not (some #(= "flour" (:ingredient_name %)) items)) "Have lines are skipped")
        (is (= 2.0 (:quantity (first (filter #(= "sugar" (:ingredient_name %)) items)))) "not doubled")))
    (testing "again: nothing new"
      (is (str/includes? (:text (req :post (str "/api/recipes/" id "/grocery") :sid sid)) "already on the grocery list")))))
