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

(ns com.larder.grocery-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.database.db :as db]
            [com.larder.global-settings :as gs]
            [com.larder.grocery :as grocery]
            [com.larder.ingredients :as ing]
            [com.larder.pantry :as pantry]
            [com.larder.ratelimit :as rl]
            [com.larder.stores :as stores]
            [com.larder.test-util :as tu]
            [com.larder.users :as users]
            [com.larder.util :as u]
            [com.larder.web-test :refer [login]]))

(use-fixtures :each tu/temp-env-fixture)

(defn- req [method uri & {:as opts}]
  (let [resp (larder/app (apply tu/request method uri (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(defn- add [ing-name q unit & [origin]]
  (grocery/add-ingredient! {:ingredient_id (ing/match ing-name) :quantity q :unit unit :origin (or origin "manual")}))

(deftest merge-test
  (testing "the same ingredient raises the open item's amount when units convert"
    (let [{a :id} (add "flour" 500.0 "g")
          {b :id merged? :merged?} (add "flour" 1.0 "kg")]
      (is (= a b))
      (is merged?)
      (is (= [1500.0 "g"] ((juxt :quantity :unit) (grocery/by-id a))))))
  (testing "density bridges volume and mass"
    (let [{a :id} (add "milk" 1.0 "l")]
      (add "milk" 103.0 "g")
      (is (< (Math/abs (- 1.1 (:quantity (grocery/by-id a)))) 0.001))))
  (testing "units that don't convert get their own item"
    (let [{a :id} (add "egg" 6.0 "each")
          {b :id merged? :merged?} (add "egg" 1.0 "package")]
      (is (not= a b))
      (is (not merged?))))
  (testing "an item without an amount takes the new one; no amount changes nothing"
    (let [{a :id} (add "butter" nil nil)]
      (add "butter" 250.0 "g")
      (is (= [250.0 "g"] ((juxt :quantity :unit) (grocery/by-id a))))
      (add "butter" nil nil)
      (is (= 250.0 (:quantity (grocery/by-id a))))))
  (testing "completed items aren't merged into"
    (let [{a :id} (add "rice" 1.0 "kg")]
      (grocery/complete! (grocery/by-id a) 1.0 "kg" nil)
      (is (not= a (:id (add "rice" 1.0 "kg")))))))

(deftest purchase-default-test
  (let [{a :id} (add "sugar" 2.0 "cup")
        {b :id} (add "flour" nil nil)
        {c :id} (add "milk" nil nil)]
    (db/exe-db-stmt ["update ingredients set default_quantity = 1.5, default_unit = 'kg' where id = ?" (ing/match "flour")])
    (is (= [2.0 "cup"] (grocery/purchase-default (grocery/by-id a))) "the item's own amount first")
    (is (= [1.5 "kg"] (grocery/purchase-default (grocery/by-id b))) "then the ingredient's purchase size")
    (is (= [nil "ml"] (grocery/purchase-default (grocery/by-id c))) "else a unit for the ingredient")))

(deftest complete-undo-test
  (let [{a :id} (add "flour" 1.0 "kg")
        item (grocery/by-id a)
        {:keys [pantry-id completed?]} (grocery/complete! item 1.0 "kg" 1)]
    (is completed?)
    (is (= [1.0 "kg" "grocery"] ((juxt :quantity :unit :source) (pantry/by-id pantry-id))))
    (testing "a second completion adds nothing"
      (is (not (:completed? (grocery/complete! item 1.0 "kg" 1))))
      (is (= 1 (count (pantry/search {})))))
    (testing "undo reopens the item and removes its pantry row"
      (grocery/undo! (grocery/by-id a))
      (is (nil? (:done_at (grocery/by-id a))))
      (is (empty? (pantry/search {}))))
    (testing "completed items are cleared after a day"
      (grocery/complete! (grocery/by-id a) nil nil 1)
      (db/exe-db-stmt ["update grocery_items set done_at = ? where id = ?" (- (u/now-ms) grocery/keep-done-ms 1000) a])
      (is (empty? (grocery/done-items)))
      (is (nil? (grocery/by-id a))))))

(deftest app-page-test
  (let [sid (login "admin")]
    (testing "adding a grocery item with a new ingredient and a new store"
      (let [resp (req :post "/api/grocery" :sid sid :form {:section "grocery" :ingredient "Tahini" :quantity "1" :unit "jar" :store "Corner Shop"})]
        (is (= "grocery-changed" (get-in resp [:headers "HX-Trigger"])))
        (let [[item] (grocery/open-items)]
          (is (= ["Tahini" 1.0 "jar" "Corner Shop" "manual" 1] ((juxt :ingredient_name :quantity :unit :store_name :origin :added_by) item))))))
    (testing "adding a misc item"
      (req :post "/api/grocery" :sid sid :form {:section "misc" :text "Paper towels" :store ""})
      (is (= ["Paper towels"] (map :text (filter #(= "misc" (:section %)) (grocery/open-items))))))
    (testing "validation"
      (is (str/includes? (:text (req :post "/api/grocery" :sid sid :form {:section "misc" :text " "})) "Enter an item"))
      (is (str/includes? (:text (req :post "/api/grocery" :sid sid :form {:section "grocery" :ingredient "Rice" :quantity "2"})) "Choose a unit")))
    (let [body (:text (req :get "/app/grocery" :sid sid))]
      (is (str/includes? body "Tahini"))
      (is (str/includes? body "Corner Shop"))
      (is (str/includes? body "Paper towels")))
    (testing "changing an item's store and removing an item"
      (let [item (first (filter :ingredient_id (grocery/open-items)))]
        (req :post (str "/api/grocery/items/" (:id item) "/store") :sid sid :form {:store_id ""})
        (is (nil? (:store_id (grocery/by-id (:id item)))))
        (req :post (str "/api/grocery/items/" (:id item) "/delete") :sid sid)
        (is (nil? (grocery/by-id (:id item))))))
    (testing "stores"
      (req :post "/api/stores" :sid sid :form {:name "Market" :notes "Saturdays"})
      (is (= "Saturdays" (:notes (stores/by-name "market"))))
      (req :post (str "/api/stores/" (:id (stores/by-name "Market")) "/delete") :sid sid)
      (is (nil? (stores/by-name "Market"))))
    (testing "visitors can't add"
      (is (= 302 (:status (req :post "/api/grocery" :form {:section "misc" :text "Spam"}))))
      (is (= 1 (count (grocery/open-items)))))))

(deftest size-dialog-test
  (let [sid (login "admin")
        {a :id} (add "flour" 2.0 "kg")
        {b :id} (add "milk" 1.0 "l")
        m (grocery/add-misc! {:text "Foil"})]
    (testing "the dialog is prefilled from the item's amount"
      (let [body (:text (req :get (str "/api/grocery/items/" a "/complete") :sid sid))]
        (is (str/includes? body "How much flour"))
        (is (str/includes? body "value=\"2\""))
        (is (re-find #"<option selected=\"selected\" value=\"kg\">|<option value=\"kg\" selected" body))))
    (testing "Confirm puts the purchase into the pantry"
      (let [resp (req :post (str "/api/grocery/items/" a "/complete") :sid sid :form {:quantity "1.5" :unit "kg" :confirm "1"})]
        (is (str/includes? (get-in resp [:headers "HX-Trigger"]) "grocery-changed"))
        (is (some? (:done_at (grocery/by-id a))))
        (is (= [1.5 "kg"] ((juxt :quantity :unit) (first (pantry/search {:q "flour"})))))))
    (testing "a bad amount keeps the dialog open"
      (let [resp (req :post (str "/api/grocery/items/" b "/complete") :sid sid :form {:quantity "-1" :unit "l"})]
        (is (str/includes? (:text resp) "above zero"))
        (is (nil? (:done_at (grocery/by-id b))))))
    (testing "Skip amount adds the item with an unknown amount"
      (req :post (str "/api/grocery/items/" b "/complete") :sid sid :form {:quantity "" :unit "l" :skip "1"})
      (let [row (first (pantry/search {:q "milk"}))]
        (is (some? row))
        (is (nil? (:quantity row)))))
    (testing "misc items complete with no pantry row"
      (req :post (str "/api/grocery/items/" m "/complete") :sid sid)
      (is (some? (:done_at (grocery/by-id m))))
      (is (= 2 (count (pantry/search {})))))
    (testing "the completed list offers undo, which removes the pantry row"
      (is (str/includes? (:text (req :get "/app/grocery" :sid sid)) "Completed (3)"))
      (req :post (str "/api/grocery/items/" a "/undo") :sid sid)
      (is (nil? (:done_at (grocery/by-id a))))
      (is (empty? (pantry/search {:q "flour"}))))
    (testing "Clear completed"
      (req :post "/api/grocery/clear-completed" :sid sid)
      (is (empty? (grocery/done-items))))))

(deftest access-levels-test
  (let [{a :id} (add "flour" 1.0 "kg")
        token (gs/share-token)
        visitor-complete (fn [& [t]] (req :post (str "/api/grocery/items/" a "/complete")
                                          :form (cond-> {:skip "1"} t (assoc :t t))))]
    (testing "share link (the default)"
      (is (= "share" (gs/grocery-access)))
      (is (not (str/includes? (:text (req :get "/")) "Grocery list")) "no tab")
      (is (= 404 (:status (req :get "/grocery"))))
      (is (= 404 (:status (req :get "/g/not-the-token"))))
      (let [page (req :get (str "/g/" token))]
        (is (= 200 (:status page)))
        (is (str/includes? (:text page) "flour"))
        (is (not (str/includes? (:text page) "Add groceries")) "no add controls"))
      (is (= 404 (:status (visitor-complete))) "completion needs the token")
      (is (= 404 (:status (visitor-complete "wrong"))))
      (is (= 200 (:status (req :get (str "/api/grocery/items/" a "/complete") :query (str "t=" token)))))
      (is (= 200 (:status (visitor-complete token))))
      (is (some? (:done_at (grocery/by-id a))))
      (is (= 200 (:status (req :post (str "/api/grocery/items/" a "/undo") :form {:t token}))))
      (is (nil? (:done_at (grocery/by-id a)))))
    (testing "open"
      (gs/put! "grocery_access" "open")
      (is (str/includes? (:text (req :get "/")) "Grocery list"))
      (is (= 200 (:status (req :get "/grocery"))))
      (is (= 200 (:status (visitor-complete))))
      (grocery/undo! (grocery/by-id a)))
    (testing "login required"
      (gs/put! "grocery_access" "login")
      (is (= 404 (:status (req :get (str "/g/" token)))))
      (is (= 404 (:status (req :get "/grocery"))))
      (is (= 404 (:status (visitor-complete token))))
      (let [sid (login "admin")]
        (is (= 200 (:status (req :get (str "/g/" token) :sid sid))))
        (is (= 200 (:status (req :post (str "/api/grocery/items/" a "/complete") :sid sid :form {:skip "1"}))))))
    (testing "regenerating the link invalidates the old one"
      (gs/put! "grocery_access" "share")
      (let [admin (login "admin")]
        (req :post "/api/site/grocery-link" :sid admin)
        (is (not= token (gs/share-token)))
        (is (= 404 (:status (req :get (str "/g/" token)))))
        (is (= 200 (:status (req :get (str "/g/" (gs/share-token))))))
        (is (= 43 (count (gs/share-token))) "32 random bytes")))
    (testing "only admins change access"
      (users/create! "cook" "password1" "user")
      (is (= 403 (:status (req :post "/api/site/grocery-access" :sid (login "cook") :form {:grocery_access "open"}))))
      (is (= 200 (:status (req :post "/api/site/grocery-access" :sid (login "admin") :form {:grocery_access "open"}))))
      (is (= "open" (gs/grocery-access)))
      (is (str/includes? (:text (req :get "/app/settings/site" :sid (login "admin"))) "/g/")))))

(deftest public-rate-limit-test
  (gs/put! "grocery_access" "open")
  (with-redefs [rl/limits (assoc rl/limits :grocery-public [3 60000])]
    (let [ids (vec (for [_ (range 5)] (grocery/add-misc! {:text "x"})))
          hit #(:status (req :post (str "/api/grocery/items/" % "/complete") :remote-addr "10.9.9.9"))]
      (is (= [200 200 200 429] (map hit (take 4 ids))))
      (is (= 200 (:status (req :post (str "/api/grocery/items/" (last ids) "/complete") :sid (login "admin"))))
          "signed-in users aren't limited"))))
