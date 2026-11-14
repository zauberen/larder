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

(ns com.larder.web-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.api.session :as session]
            [com.larder.test-util :as tu]
            [com.larder.users :as users]))

(use-fixtures :each tu/temp-env-fixture)

(defn login
  [username]
  (:sid (session/initialize-session username)))

(defn get-page
  [uri & opts]
  (let [resp (larder/app (apply tu/request :get uri opts))]
    (assoc resp :text (tu/body-str resp))))

(deftest auth-redirects-test
  (is (= 302 (:status (get-page "/app"))))
  (is (= "/login" (get-in (get-page "/app") [:headers "Location"])))
  (is (= "/login" (get-in (get-page "/app/pantry" :headers {"hx-request" "true"}) [:headers "HX-Redirect"])))
  (is (= 200 (:status (get-page "/"))))
  (is (= 200 (:status (get-page "/login"))))
  (testing "a signed-in user going to /login lands in the app"
    (is (= "/app" (get-in (get-page "/login" :sid (login "admin")) [:headers "Location"])))))

(deftest login-flow-test
  (let [resp (larder/app (tu/request :post "/api/login" :form {:username "admin" :password "admin"}))
        sid (second (re-find #"larder-session-id=([^;]+)" (get-in resp [:headers "Set-Cookie"] "")))]
    (is (= "/app" (get-in resp [:headers "HX-Redirect"])))
    (is (= 200 (:status (get-page "/app" :sid sid))))
    (testing "logging out ends the session"
      (larder/app (tu/request :get "/logout" :sid sid))
      (is (= 302 (:status (get-page "/app" :sid sid)))))))

(deftest public-dashboard-test
  (testing "visitors see the Recipes tab and Log in"
    (let [body (:text (get-page "/"))]
      (is (str/includes? body ">Recipes<"))
      (is (str/includes? body ">Log in<"))
      (is (not (str/includes? body "Grocery list")) "the grocery tab is hidden by default")))
  (testing "signed-in users see Open app"
    (let [body (:text (get-page "/" :sid (login "admin")))]
      (is (str/includes? body "Open app"))
      (is (not (str/includes? body ">Log in<"))))))

(deftest app-shell-test
  (users/create! "cook" "password1" "user")
  (let [sid (login "cook")]
    (testing "every sidebar page renders for a plain user"
      (doseq [path ["/app" "/app/recipes/new" "/app/meal-plan" "/app/pantry" "/app/grocery" "/app/settings"]]
        (is (= 200 (:status (get-page path :sid sid))) path)))
    (testing "the sidebar lists Add, Recipes, Meal plan, Pantry, Grocery in order"
      (let [body (:text (get-page "/app" :sid sid))
            positions (map #(str/index-of body %) [">Add<" ">Recipes<" ">Meal plan<" ">Pantry<" ">Grocery<"])]
        (is (every? some? positions))
        (is (apply < positions))))
    (testing "the current page is highlighted"
      (is (re-find #"class=\"nav-button active\"[^>]*data-section=\"pantry\"|data-section=\"pantry\"[^>]*class=\"nav-button active\""
                   (:text (get-page "/app/pantry" :sid sid)))))
    (testing "htmx navigation gets only the main element"
      (let [body (:text (get-page "/app/pantry" :sid sid :headers {"hx-request" "true"}))]
        (is (str/starts-with? body "<main"))
        (is (str/includes? body "data-section=\"pantry\""))
        (is (not (str/includes? body "sidebar")))))
    (testing "the header shows the username and the site name"
      (let [body (:text (get-page "/app" :sid sid))]
        (is (str/includes? body "cook"))
        (is (str/includes? body "<title>Larder</title>"))))))

(deftest disabled-user-test
  (let [cook (users/create! "cook" "password1" "user")
        sid (login "cook")]
    (is (= 200 (:status (get-page "/app" :sid sid))))
    (users/set-enabled! (:id cook) false)
    (testing "an existing session stops working at once"
      (is (= 302 (:status (get-page "/app" :sid sid)))))
    (testing "and the user can't log in"
      (is (= 401 (:status (session/try-login {:form-params {"username" "cook" "password" "password1"}})))))
    (testing "enabling restores access"
      (users/set-enabled! (:id cook) true)
      (is (= 200 (:status (get-page "/app" :sid sid)))))))

(deftest debug-echo-test
  (is (= 404 (:status (get-page "/api/echo" :sid (login "admin"))))))

(deftest cross-origin-test
  (let [sid (login "admin")
        post (fn [uri origin] (larder/app (tu/request :post uri :sid sid :form {:site_name "Evil"}
                                                      :headers (when origin {"origin" origin}))))]
    (is (= 403 (:status (post "/api/site/settings" "https://evil.example.com"))))
    (is (not= 403 (:status (post "/api/site/settings" "http://larder.example.com:8085"))))
    (is (not= 403 (:status (post "/api/site/settings" nil))) "non-browser clients send no Origin")
    (testing "bearer-token endpoints are exempt"
      (doseq [path ["/mcp" "/oauth/token" "/oauth/register"]]
        (is (not= 403 (:status (post path "https://claude.ai"))) path)))))

(deftest hardening-test
  (let [resp (get-page "/")]
    (is (= "nosniff" (get-in resp [:headers "X-Content-Type-Options"])))
    (is (= "DENY" (get-in resp [:headers "X-Frame-Options"]))))
  (testing "large bodies are refused"
    (is (= 413 (:status (larder/app (tu/request :post "/api/login"
                                                :headers {"content-length" (str (* 2 1024 1024))})))))))

(deftest static-content-type-test
  (is (str/starts-with? (get-in (larder/app (tu/request :get "/css/main.css")) [:headers "Content-Type"]) "text/css"))
  (is (str/starts-with? (get-in (larder/app (tu/request :get "/js/main.js")) [:headers "Content-Type"]) "text/javascript")))
