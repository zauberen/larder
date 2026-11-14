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

(ns com.larder.settings-web-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.global-settings :as gs]
            [com.larder.test-util :as tu]
            [com.larder.users :as users]
            [com.larder.web-test :refer [login]]))

(use-fixtures :each tu/temp-env-fixture)

(defn- post [sid uri & {:as opts}]
  (let [resp (larder/app (apply tu/request :post uri :sid sid (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(deftest settings-role-checks-test
  (let [admin (login "admin")
        _ (users/create! "cook" "password1" "user")
        cook (login "cook")]
    (doseq [path ["/app/settings" "/app/settings/account" "/app/settings/users" "/app/settings/site"]]
      (is (= 200 (:status (larder/app (tu/request :get path :sid admin)))) path))
    (testing "users see only their account"
      (is (= 200 (:status (larder/app (tu/request :get "/app/settings" :sid cook)))))
      (is (not (str/includes? (tu/body-str (larder/app (tu/request :get "/app/settings" :sid cook))) "/app/settings/users")))
      (is (= 404 (:status (larder/app (tu/request :get "/app/settings/users" :sid cook)))))
      (is (= 404 (:status (larder/app (tu/request :get "/app/settings/site" :sid cook))))))
    (testing "admin endpoints refuse users"
      (is (= 403 (:status (post cook "/api/users" :form {:username "x" :password "password1" :role "admin"}))))
      (is (nil? (users/by-username "x")))
      (is (= 403 (:status (post cook (str "/api/users/" (:id (users/by-username "admin")) "/disable")))))
      (is (= 403 (:status (post cook "/api/site/settings" :form {:site_name "Mine"}))))
      (is (= 403 (:status (post cook "/api/site/config" :form {})))))
    (testing "a demoted admin loses admin access on the next request"
      (users/create! "boss2" "password1" "admin")
      (let [sid (login "boss2")]
        (is (= 200 (:status (larder/app (tu/request :get "/app/settings/users" :sid sid)))))
        (users/set-role! (:id (users/by-username "boss2")) "user")
        (is (= 404 (:status (larder/app (tu/request :get "/app/settings/users" :sid sid)))))))))

(deftest user-management-test
  (let [admin-sid (login "admin")
        me (users/by-username "admin")]
    (testing "adding a user"
      (is (str/includes? (:text (post admin-sid "/api/users" :form {:username "cook" :password "password1" :role "user"}))
                         "Added cook"))
      (is (= "user" (:role (users/by-username "cook")))))
    (testing "validation"
      (is (str/includes? (:text (post admin-sid "/api/users" :form {:username "cook" :password "short" :role "user"}))
                         "taken"))
      (is (str/includes? (:text (post admin-sid "/api/users" :form {:username "x" :password "password1" :role "moderator"}))
                         "Unknown role")))
    (let [cook (users/by-username "cook")
          api (str "/api/users/" (:id cook))]
      (testing "password reset via prompt"
        (post admin-sid (str api "/password") :headers {"hx-prompt" "anotherpass"})
        (is (users/check-password "cook" "anotherpass")))
      (testing "disable and enable"
        (is (str/includes? (:text (post admin-sid (str api "/disable"))) "Disabled cook"))
        (is (nil? (users/by-username "cook")))
        (is (= 0 (:enabled (users/by-id (:id cook)))))
        (post admin-sid (str api "/enable"))
        (is (some? (users/by-username "cook"))))
      (testing "you can't demote, disable or remove yourself"
        (post admin-sid (str "/api/users/" (:id me) "/role") :form {:role "user"})
        (is (= "admin" (:role (users/by-username "admin"))))
        (is (str/includes? (:text (post admin-sid (str "/api/users/" (:id me) "/disable"))) "disable yourself"))
        (is (str/includes? (:text (post admin-sid (str "/api/users/" (:id me) "/delete"))) "remove yourself")))
      (testing "promotion, then removal signs the user out"
        (post admin-sid (str api "/role") :form {:role "admin"})
        (is (= "admin" (:role (users/by-username "cook"))))
        (let [cook-sid (login "cook")]
          (post admin-sid (str api "/delete"))
          (is (nil? (users/by-id (:id cook))))
          (is (= 302 (:status (larder/app (tu/request :get "/app" :sid cook-sid))))))))))

(deftest last-admin-test
  (let [admin (users/by-username "admin")]
    (is (users/last-admin? admin))
    (let [boss2 (users/create! "boss2" "password1" "admin")]
      (is (not (users/last-admin? admin)))
      (testing "disabled admins don't count"
        (users/set-enabled! (:id boss2) false)
        (is (users/last-admin? admin))
        (is (not (users/last-admin? (users/by-id (:id boss2)))))))))

(deftest account-password-test
  (let [sid (login "admin")]
    (is (str/includes? (:text (post sid "/api/account/password" :form {:current "nope" :password "newpassword" :confirm "newpassword"}))
                       "wrong"))
    (is (str/includes? (:text (post sid "/api/account/password" :form {:current "admin" :password "newpassword" :confirm "other"}))
                       "match"))
    (is (str/includes? (:text (post sid "/api/account/password" :form {:current "admin" :password "newpassword" :confirm "newpassword"}))
                       "Password changed"))
    (is (users/check-password "admin" "newpassword"))))

(deftest site-settings-test
  (let [sid (login "admin")]
    (testing "validation"
      (is (str/includes? (:text (post sid "/api/site/settings" :form {:site_name "  "})) "name"))
      (is (str/includes? (:text (post sid "/api/site/settings" :form {:site_name "Home" :public_base_url "ftp://x"}))
                         "public base URL")))
    (testing "saving"
      (post sid "/api/site/settings" :form {:site_name "Our Kitchen" :public_base_url "https://food.example.com/"})
      (is (= "Our Kitchen" (gs/site-name)))
      (is (= "https://food.example.com" (gs/public-base-url)))
      (is (str/includes? (tu/body-str (larder/app (tu/request :get "/"))) "Our Kitchen")))))
