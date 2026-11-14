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

(ns com.larder.session-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder.api.session :as session]
            [com.larder.auth :as auth]
            [com.larder.database.db :as db]
            [com.larder.test-util :as tu]))

(use-fixtures :each tu/temp-env-fixture)

(deftest password-hashing-test
  (let [hashed (session/hash-password "hunter2")]
    (is (session/verify-password "hunter2" hashed))
    (is (not (session/verify-password "wrong" hashed)))))

(deftest sqlite-session-roundtrip-test
  (testing "Sessions stored in sqlite can be read back, including the user."
    (let [sess (session/initialize-session "admin")]
      (is (db/valid-session? (:sid sess)))
      (is (= "admin" (:user (db/get-session (:sid sess)))))
      (db/delete-session (:sid sess))
      (is (not (db/valid-session? (:sid sess)))))))

(deftest login-test
  (testing "The test fixture's admin account can log in."
    (let [resp (session/try-login {:form-params {"username" "admin" "password" "admin"}})]
      (is (= 200 (:status resp)))
      (is (re-find #"SameSite=Strict" (get-in resp [:headers "Set-Cookie"])))))
  (testing "Bad passwords and unknown users are rejected."
    (is (= 401 (:status (session/try-login {:form-params {"username" "admin" "password" "nope"}}))))
    (is (= 401 (:status (session/try-login {:form-params {"username" "nobody" "password" "admin"}})))))
  (testing "The cookie expires with the session and is Secure over https."
    (let [cookie #(get-in (session/try-login (merge {:form-params {"username" "admin" "password" "admin"}} %))
                          [:headers "Set-Cookie"])]
      (is (re-find #"Max-Age=\d+" (cookie {})))
      (is (not (re-find #"Secure" (cookie {}))))
      (is (re-find #"Secure" (cookie {:scheme :https})))
      (is (re-find #"Secure" (cookie {:headers {"x-forwarded-proto" "https"}}))))))

(deftest session-expiry-test
  (testing "Sessions older than the session lifetime are no longer valid."
    (let [sid (:sid (session/initialize-session "admin"))]
      (db/exe-db-stmt ["update sessions set created_at = created_at - 31 * 24 * 3600 * 1000 where sid = ?" sid])
      (is (not (db/valid-session? sid)))
      (is (nil? (db/get-session sid))))))

(deftest requires-user-test
  (testing "Unauthenticated htmx requests are redirected via HX-Redirect."
    (let [resp (auth/requires-user (constantly :page) {:headers {"hx-request" "true"}})]
      (is (= "/login" (get-in resp [:headers "HX-Redirect"])))))
  (testing "Unauthenticated page requests get a 302."
    (is (= 302 (:status (auth/requires-user (constantly :page) {}))))))

(deftest login-rate-limit-test
  (testing "Ten failed logins from one address lock that address out for a while."
    (let [attempt (fn [addr pass] (session/try-login {:remote-addr addr
                                                      :form-params {"username" "admin" "password" pass}}))]
      (dotimes [_ 10] (is (= 401 (:status (attempt "10.0.0.1" "wrong")))))
      (is (= 429 (:status (attempt "10.0.0.1" "admin"))) "even the right password is refused")
      (is (= 200 (:status (attempt "10.0.0.2" "admin"))) "other addresses are unaffected"))))
