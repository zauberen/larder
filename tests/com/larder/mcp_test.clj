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

(ns com.larder.mcp-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.api-keys :as api-keys]
            [com.larder.database.db :as db]
            [com.larder.global-settings :as gs]
            [com.larder.grocery :as grocery]
            [com.larder.ingredients :as ing]
            [com.larder.mealplan :as mp]
            [com.larder.pantry :as pantry]
            [com.larder.recipes :as recipes]
            [com.larder.test-util :as tu]
            [com.larder.users :as users]
            [com.larder.web-test :refer [login]]))

(use-fixtures :each tu/temp-env-fixture)

(def ^:dynamic *token* nil)

(defn post-mcp
  "POST BODY (a map, encoded as JSON) to /mcp with TOKEN (default *token*)."
  [body & {:keys [token headers]}]
  (let [bytes (.getBytes (json/write-str body) "UTF-8")
        resp (larder/app (tu/request :post "/mcp" :bytes bytes
                                     :headers (merge {"content-type" "application/json"
                                                      "accept" "application/json, text/event-stream"
                                                      "origin" "https://claude.ai"}
                                                     (when-let [t (or token *token*)] {"authorization" (str "Bearer " t)})
                                                     headers)))
        text (tu/body-str resp)]
    (assoc resp :text text :json (when (seq text) (json/read-str text)))))

(defn call
  "Call tool NAME with ARGS. Returns [structured-result error-text]."
  [name args]
  (let [{:keys [json]} (post-mcp {:jsonrpc "2.0" :id 1 :method "tools/call" :params {:name name :arguments args}})
        result (get json "result")]
    (if (get result "isError")
      [nil (get-in result ["content" 0 "text"])]
      [(get result "structuredContent") nil])))

(defmacro with-key [& body]
  `(binding [*token* (api-keys/create! 1 "test")] ~@body))

;;; Transport and auth

(deftest auth-test
  (testing "no token: 401 pointing at the resource metadata"
    (let [resp (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"})]
      (is (= 401 (:status resp)))
      (is (str/includes? (get-in resp [:headers "WWW-Authenticate"]) "resource_metadata=\"http://larder.example.com:8085/.well-known/oauth-protected-resource\""))))
  (testing "a wrong or malformed key"
    (is (= 401 (:status (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"} :token "lk_abcdefgh_nope"))))
    (let [k (api-keys/create! 1 "real")]
      (is (= 401 (:status (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"} :token (str (subs k 0 (- (count k) 2)) "xx")))))))
  (testing "a valid key works and records its use"
    (let [k (api-keys/create! 1 "laptop")]
      (is (= 200 (:status (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"} :token k))))
      (is (some? (:last_used_at (first (filter #(= "laptop" (:name %)) (api-keys/for-user 1))))))
      (testing "keys are stored hashed"
        (is (empty? (db/run-db-query ["select 1 from api_keys where key_hash = ?" k]))))
      (testing "the user's enabled flag is checked on every call"
        (users/create! "spare" "password1" "admin")
        (users/set-enabled! 1 false)
        (is (= 401 (:status (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"} :token k))))
        (users/set-enabled! 1 true))
      (testing "admins can turn API keys off"
        (gs/put! "mcp_api_keys" "0")
        (is (= 401 (:status (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"} :token k))))
        (gs/put! "mcp_api_keys" "1"))
      (testing "revoked keys stop working"
        (api-keys/revoke! (:id (first (filter #(= "laptop" (:name %)) (api-keys/for-user 1)))))
        (is (= 401 (:status (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"} :token k))))))))

(deftest protocol-test
  (with-key
    (testing "initialize negotiates the version and declares tools only"
      (let [{:keys [json status]} (post-mcp {:jsonrpc "2.0" :id 1 :method "initialize"
                                             :params {:protocolVersion "2025-06-18" :capabilities {} :clientInfo {:name "t" :version "1"}}})]
        (is (= 200 status))
        (is (= "2025-06-18" (get-in json ["result" "protocolVersion"])))
        (is (= {"tools" {"listChanged" false}} (get-in json ["result" "capabilities"])))
        (is (= "larder" (get-in json ["result" "serverInfo" "name"]))))
      (is (= "2025-11-25" (get-in (post-mcp {:jsonrpc "2.0" :id 2 :method "initialize" :params {:protocolVersion "1999-01-01"}})
                                  [:json "result" "protocolVersion"]))))
    (testing "notifications get 202 with no body"
      (let [resp (post-mcp {:jsonrpc "2.0" :method "notifications/initialized"})]
        (is (= 202 (:status resp)))
        (is (= "" (:text resp)))))
    (testing "tools/list has every tool with a schema"
      (let [listed (get-in (post-mcp {:jsonrpc "2.0" :id 3 :method "tools/list"}) [:json "result" "tools"])]
        (is (= 26 (count listed)))
        (is (= #{"search_recipes" "get_recipe" "add_recipe" "update_recipe" "import_recipe_url" "resolve_ingredients"
                 "create_ingredient" "review_ingredients" "update_ingredients" "merge_ingredients" "list_pantry" "add_pantry_items" "update_pantry_item" "remove_pantry_items"
                 "get_meal_plan" "set_meal_plan_entries" "remove_meal_plan_entries" "list_recipes_by_pantry_status"
                 "mark_recipe_made" "list_grocery" "add_grocery_items" "complete_grocery_item" "assign_stores"
                 "add_missing_for_meal_plan" "list_stores" "add_store"}
               (set (map #(get % "name") listed))))
        (is (every? #(= "object" (get-in % ["inputSchema" "type"])) listed))))
    (testing "errors"
      (is (= -32601 (get-in (post-mcp {:jsonrpc "2.0" :id 4 :method "resources/list"}) [:json "error" "code"])))
      (is (= -32602 (get-in (post-mcp {:jsonrpc "2.0" :id 5 :method "tools/call" :params {:name "nope"}}) [:json "error" "code"])))
      (is (= 400 (:status (post-mcp [{:jsonrpc "2.0" :id 6 :method "ping"}]))) "no batches")
      (is (= 400 (:status (post-mcp {:jsonrpc "2.0" :id 7 :method "ping"} :headers {"mcp-protocol-version" "2020-01-01"}))))
      (is (= 200 (:status (post-mcp {:jsonrpc "2.0" :id 8 :method "ping"} :headers {"mcp-protocol-version" "2025-11-25"}))))
      (is (= 405 (:status (larder/app (tu/request :get "/mcp" :headers {"authorization" (str "Bearer " *token*)}))))))
    (testing "cookies don't authenticate /mcp"
      (is (= 401 (:status (larder/app (tu/request :post "/mcp" :sid (login "admin") :bytes (.getBytes "{}")))))))))

;;; Tools

(deftest recipe-tools-test
  (with-key
    (let [[added] (call "add_recipe" {:title "Pancakes" :servings 4 :ingredients ["2 cups flour" "2 eggs" "1 dragon fruit"]
                                      :steps ["Mix." "Fry."] :tags ["breakfast"]})
          id (get added "id")]
      (is (= "draft" (get added "status")) "drafts unless publish is true")
      (is (= ["1 dragon fruit"] (get added "unmatched_lines")))
      (is (= 1 (:created_by (recipes/by-id id))))
      (testing "get_recipe"
        (let [[r] (call "get_recipe" {:id id})]
          (is (= ["Mix." "Fry."] (get r "steps")))
          (is (= ["flour" 2.0 "cup" "missing"] ((juxt #(get % "ingredient") #(get % "quantity") #(get % "unit") #(get % "pantry_status"))
                                                (first (get r "ingredients")))))))
      (testing "update_recipe changes only what is given"
        (call "update_recipe" {:id id :status "published" :rating 5})
        (let [r (recipes/by-id id)]
          (is (= ["published" 5 "Pancakes" 3] [(:status r) (:rating r) (:title r) (count (:ingredients r))]))))
      (testing "search_recipes"
        (is (= ["Pancakes"] (map #(get % "title") (get (first (call "search_recipes" {:query "pan"})) "recipes"))))
        (is (= [] (get (first (call "search_recipes" {:tag "dinner"})) "recipes"))))
      (testing "validation errors are tool errors"
        (is (str/includes? (second (call "add_recipe" {:title ""})) "title"))
        (is (str/includes? (second (call "get_recipe" {:id 999})) "No such recipe"))
        (is (str/includes? (second (call "import_recipe_url" {:url "http://127.0.0.1/x"})) "private network"))))))

(deftest ingredient-and-pantry-tools-test
  (with-key
    (testing "resolve_ingredients matches receipt names"
      (let [results (get (first (call "resolve_ingredients" {:names ["All-purpose flour" "Eggs" "2 cups milk" "Kombu"]})) "results")]
        (is (= [true true true false] (map #(get % "matched") results)))
        (is (= {"quantity" 2.0 "unit" "cup"} (get (nth results 2) "parsed")))))
    (testing "create_ingredient"
      (let [[r] (call "create_ingredient" {:name "Kombu" :dimension "mass" :aliases ["dried kelp"] :default_quantity 50 :default_unit "g"})]
        (is (= "Kombu" (get-in r ["ingredient" "name"])))
        (is (= (ing/match "dried kelp") (get-in r ["ingredient" "id"])))
        (is (str/includes? (second (call "create_ingredient" {:name "flour" :dimension "mass"})) "already"))))
    (testing "add_pantry_items in one batch, by id or name"
      (let [[r] (call "add_pantry_items" {:items [{:ingredient_id (ing/match "flour") :quantity 1.5 :unit "kg"}
                                                   {:name "eggs" :quantity 12 :unit "each"}
                                                   {:name "Saffron threads"}]})
            added (get r "added")]
        (is (= ["flour" "egg" "Saffron threads"] (map #(get % "ingredient") added)))
        (is (every? #(= "mcp" (get % "source")) added))
        (is (= #{1} (set (map :added_by (pantry/search {})))))))
    (testing "a bad item rejects the whole batch"
      (is (str/includes? (second (call "add_pantry_items" {:items [{:name "rice" :quantity 1 :unit "kg"} {:name "salt" :quantity 2}]}))
                         "Item 2"))
      (is (empty? (pantry/search {:q "rice"}))))
    (testing "batches are capped at 200"
      (is (str/includes? (second (call "add_pantry_items" {:items (repeat 201 {:name "salt"})})) "At most 200")))
    (let [flour-row (first (pantry/search {:q "flour"}))]
      (testing "update_pantry_item and list_pantry"
        (call "update_pantry_item" {:id (:id flour-row) :quantity 500 :unit "g"})
        (is (= [500.0 "g"] ((juxt #(get % "quantity") #(get % "unit"))
                            (first (get (first (call "list_pantry" {:query "flour"})) "items"))))))
      (testing "remove_pantry_items"
        (is (= 1 (get (first (call "remove_pantry_items" {:ids [(:id flour-row)]})) "removed")))))))

(deftest ingredient-review-tools-test
  (with-key
    (let [scallion (ing/create! {:name "Scallion" :dimension "count"})
          green (ing/create! {:name "Green onion" :dimension "count" :density 0.4})
          tahini (ing/create! {:name "Tahini" :dimension "volume"})
          a (recipes/create! (first (recipes/parse-form {:title "A" :ingredients "2 scallions, sliced\n2 tbsp tahini\n1 dragon fruit"})) "published" 1)
          _ (recipes/create! (first (recipes/parse-form {:title "B" :ingredients "3 green onions\n100 g green onions\n50 g tahini\n2 dragon fruits"})) "draft" 1)
          by-name (fn [r n] (first (filter #(= n (get % "name")) (get r "ingredients"))))]
      (pantry/add! {:ingredient_id scallion :quantity 2.0 :unit "each" :source "manual"})
      (testing "review_ingredients reports usage, units and issues"
        (let [[r] (call "review_ingredients" {})]
          (is (= {"used_in_recipes" 2 "recipe_lines" 2 "units_used" ["g" "tbsp"] "issues" ["needs_density" "no_default_purchase"]}
                 (select-keys (by-name r "Tahini") ["used_in_recipes" "recipe_lines" "units_used" "issues"])))
          (is (= ["measured_differently" "no_default_purchase"] (get (by-name r "Green onion") "issues")))
          (is (= 1 (get (by-name r "Scallion") "pantry_rows")))
          (is (= {"name" "dragon fruit" "lines" 2 "examples" ["1 dragon fruit" "2 dragon fruits"]}
                 (select-keys (first (get r "unmatched_lines")) ["name" "lines" "examples"])))
          (is (= #{"A" "B"} (set (map #(get % "title") (get (first (get r "unmatched_lines")) "recipes")))))
          (is (= 2 (get-in r ["summary" "unmatched_lines"])))
          (testing "seeded ingredients nobody uses are unused"
            (is (= ["unused"] (get (by-name r "flour") "issues"))))
          (testing "issues_only leaves out clean ingredients"
            (ing/update! scallion {:name "Scallion" :dimension "count" :default_quantity 1.0 :default_unit "bunch"})
            (let [[r] (call "review_ingredients" {:issues_only true})]
              (is (nil? (by-name r "Scallion")))
              (is (some? (by-name r "Tahini")))))))
      (testing "update_ingredients validates the whole batch first"
        (is (str/includes? (second (call "update_ingredients" {:ingredients [{:id tahini :density 0.95}
                                                                              {:id green :name "Tahini"}]}))
                           "Ingredient 2"))
        (is (nil? (:density (ing/by-id tahini))))
        (is (str/includes? (second (call "update_ingredients" {:ingredients [{:id tahini :add_aliases ["sesame paste"]}
                                                                              {:id green :add_aliases ["Sesame pastes"]}]}))
                           "more than one")))
      (testing "update_ingredients edits only the fields given and rematches lines"
        (let [pitaya (ing/create! {:name "Pitaya" :dimension "count"})
              [r] (call "update_ingredients" {:ingredients [{:id tahini :density 0.95 :default_quantity 250 :default_unit "ml"}
                                                            {:id pitaya :add_aliases ["dragon fruit"]}]})]
          (is (= 2 (get r "recipe_lines_matched")))
          (is (= [0.95 {"quantity" 250.0 "unit" "ml"} "volume"]
                 ((juxt #(get % "density") #(get % "default_purchase") #(get % "dimension")) (first (get r "ingredients")))))
          (is (= ["dragon fruit"] (ing/aliases pitaya)))
          (is (empty? (get (by-name (first (call "review_ingredients" {})) "Tahini") "issues")))
          (testing "null clears"
            (call "update_ingredients" {:ingredients [{:id tahini :density nil}]})
            (is (nil? (:density (ing/by-id tahini))))
            (is (= 250.0 (:default_quantity (ing/by-id tahini)))))))
      (testing "merge_ingredients folds duplicates into one"
        (is (str/includes? (second (call "merge_ingredients" {:into_id scallion :ids [scallion]})) "into_id"))
        (let [[r] (call "merge_ingredients" {:into_id scallion :ids [green]})]
          (is (= [{"name" "Green onion" "recipe_lines" 2 "pantry_rows" 0 "grocery_items" 0}] (get r "merged")))
          (is (nil? (ing/by-id green)))
          (is (= scallion (ing/match "green onions")))
          (is (= 0.4 (get-in r ["ingredient" "density"])) "takes the density it lacked")
          (is (= {"quantity" 1.0 "unit" "bunch"} (get-in r ["ingredient" "default_purchase"])) "keeps its own purchase size")
          (is (= 3 (count (filter #(= scallion (:ingredient_id %))
                                  (db/run-db-query ["select ingredient_id from recipe_ingredients"])))))
          (testing "recipes saved later still match"
            (recipes/update! a (first (recipes/parse-form {:title "A" :ingredients "1 green onion"})) "published")
            (is (= scallion (:ingredient_id (first (:ingredients (recipes/by-id a))))))))))))

(deftest meal-plan-tools-test
  (with-key
    (let [soup (recipes/create! (first (recipes/parse-form {:title "Soup" :servings "2" :ingredients "2 cups milk\n1 onion"})) "published" 1)
          ready (recipes/create! (first (recipes/parse-form {:title "Toast" :servings "1" :ingredients "1 tsp salt"})) "published" 1)]
      (db/exe-db-stmt ["update ingredients set always_have = 1 where id = ?" (ing/match "salt")])
      (testing "list_recipes_by_pantry_status"
        (let [[r] (call "list_recipes_by_pantry_status" {:status "pantry_ready"})]
          (is (= ["Toast"] (map #(get % "title") (get r "recipes")))))
        (let [[r] (call "list_recipes_by_pantry_status" {:status "none_in_pantry"})]
          (is (= ["2 cups milk" "1 onion"] (map #(get % "text") (get (first (get r "recipes")) "missing"))))))
      (testing "set_meal_plan_entries adds, then moves and resizes"
        (let [[r] (call "set_meal_plan_entries" {:entries [{:date "2026-11-16" :recipe_id soup :servings 4} {:date "2026-11-17" :recipe_id ready}]})
              [e1 e2] (get r "entry_ids")]
          (is (= 4.0 (:servings (mp/by-id e1))))
          (call "set_meal_plan_entries" {:entries [{:id e2 :date "2026-11-18" :servings 2}]})
          (is (= ["2026-11-18" 2.0] ((juxt :date :servings) (mp/by-id e2))))
          (testing "get_meal_plan"
            (let [[r] (call "get_meal_plan" {:from "2026-11-16" :to "2026-11-22"})]
              (is (= ["Soup" "Toast"] (map #(get % "title") (get r "entries"))))
              (is (= ["none_in_pantry" "pantry_ready"] (map #(get % "pantry_status") (get r "entries"))))))
          (testing "add_missing_for_meal_plan"
            (let [[r] (call "add_missing_for_meal_plan" {:from "2026-11-16" :to "2026-11-22"})]
              (is (= 2 (get r "added")))
              (is (= #{"mcp"} (set (map :origin (grocery/open-items)))))
              (is (= 4.0 (:quantity (first (filter #(= "milk" (:ingredient_name %)) (grocery/open-items))))) "4 servings of a 2-serving recipe")))
          (testing "mark_recipe_made"
            (pantry/add! {:ingredient_id (ing/match "milk") :quantity 2.0 :unit "l" :source "manual"})
            (pantry/add! {:ingredient_id (ing/match "onion") :quantity 3.0 :unit "each" :source "manual"})
            (let [[r] (call "mark_recipe_made" {:recipe_id soup :meal_plan_entry_id e1 :ran_out ["onions"]})]
              (is (= 4.0 (get r "servings")) "the entry's servings")
              (is (= ["onion"] (get r "ran_out")))
              (is (some? (:cooked_at (mp/by-id e1))))
              (is (empty? (pantry/rows-for (ing/match "onion"))))
              (is (< (Math/abs (- 1.0536 (:quantity (first (pantry/rows-for (ing/match "milk")))))) 0.001))))
          (testing "remove_meal_plan_entries"
            (call "remove_meal_plan_entries" {:ids [e2]})
            (is (nil? (mp/by-id e2))))
          (testing "date errors"
            (is (str/includes? (second (call "get_meal_plan" {:from "2026-01-01" :to "2026-12-31"})) "two months"))
            (is (str/includes? (second (call "get_meal_plan" {:from "soon" :to "2026-12-31"})) "date"))))))))

(deftest grocery-and-store-tools-test
  (with-key
    (testing "add_grocery_items: ingredients and misc, with stores"
      (let [[r] (call "add_grocery_items" {:items [{:name "milk" :quantity 1 :unit "l" :store "Market"}
                                                    {:ingredient_id (ing/match "flour")}
                                                    {:text "Foil"}]})]
        (is (= ["milk" "flour" "Foil"] (map #(get % "name") (get r "items"))))
        (is (= "Market" (get (first (get r "items")) "store")))
        (is (every? #(= "mcp" (:origin %)) (grocery/open-items)))))
    (testing "list_stores and add_store"
      (call "add_store" {:name "Corner Shop" :notes "Open late"})
      (is (= ["Corner Shop" "Market"] (map #(get % "name") (get (first (call "list_stores" {})) "stores")))))
    (let [items (get (first (call "list_grocery" {})) "items")
          id-of #(get (first (filter (fn [i] (= % (get i "name"))) items)) "id")]
      (testing "assign_stores, including clearing one"
        (call "assign_stores" {:assignments [{:item_id (id-of "flour") :store "Corner Shop"} {:item_id (id-of "milk") :store nil}]})
        (is (= ["Corner Shop" nil] (map #(:store_name (grocery/by-id %)) [(id-of "flour") (id-of "milk")]))))
      (testing "complete_grocery_item: given amount, default amount, skip"
        (call "complete_grocery_item" {:id (id-of "milk")})
        (is (= [1.0 "l" "mcp"] ((juxt :quantity :unit :source) (first (pantry/search {:q "milk"})))))
        (call "complete_grocery_item" {:id (id-of "flour") :skip_amount true})
        (is (nil? (:quantity (first (pantry/search {:q "flour"})))))
        (call "complete_grocery_item" {:id (id-of "Foil")})
        (is (= 2 (count (pantry/search {}))) "misc items add no pantry row")
        (is (str/includes? (second (call "complete_grocery_item" {:id (id-of "Foil")})) "already"))
        (is (= 3 (count (get (first (call "list_grocery" {:include_completed true})) "items"))))))))

(deftest api-keys-settings-test
  (let [sid (login "admin")
        resp (larder/app (tu/request :post "/api/keys" :sid sid :form {:name "Laptop"}))
        body (tu/body-str resp)
        k (second (re-find #"(lk_[A-Za-z0-9]{8}_[A-Za-z0-9_-]{43})" body))]
    (is (some? k) "the key is shown once")
    (is (str/includes? body "claude mcp add --transport http larder http://larder.example.com:8085/mcp"))
    (is (= 200 (:status (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"} :token k))))
    (is (not (str/includes? (tu/body-str (larder/app (tu/request :get "/app/settings/keys" :sid sid))) k)) "never shown again")
    (testing "users can't revoke other people's keys"
      (users/create! "cook" "password1" "user")
      (let [kid (:id (first (api-keys/for-user 1)))]
        (larder/app (tu/request :post (str "/api/keys/" kid "/revoke") :sid (login "cook")))
        (is (= 200 (:status (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"} :token k))))
        (is (= 403 (:status (larder/app (tu/request :post (str "/api/site/keys/" kid "/revoke") :sid (login "cook"))))))
        (testing "admins can revoke any key from the site tab"
          (is (str/includes? (tu/body-str (larder/app (tu/request :get "/app/settings/site" :sid sid))) "Laptop"))
          (larder/app (tu/request :post (str "/api/site/keys/" kid "/revoke") :sid sid))
          (is (= 401 (:status (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"} :token k)))))))))
