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

(ns com.larder.api.session
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [buddy.hashers :as hashers]
            [com.larder.database.db :as db]
            [com.larder.ratelimit :as rl])
  (:import java.util.Base64))

(def cookie-name "larder-session-id")

(defn encode
  "Wrapper for Base64 encoder."
  [to-encode]
  (String. (.encode (Base64/getEncoder) (.getBytes to-encode))))

(defn decode
  "Wrapper for Base64 decoder."
  [to-decode]
  (String. (.decode (Base64/getDecoder) (.getBytes to-decode))))

(defn verify-password
  "Verify a user's password matches the hash from the database."
  [plain-password hashed-password]
  (hashers/check plain-password (decode hashed-password)))

(defn hash-password
  "Hash a pasword to be stored in the database."
  [plain-password]
  (encode (hashers/derive plain-password {:alg :argon2id})))

(defn https?
  "True when REQ came in over https, directly or through a reverse proxy."
  [req]
  (or (= :https (:scheme req))
      (= "https" (some-> (get-in req [:headers "x-forwarded-proto"]) str/lower-case))))

(defn session-cookie
  "The Set-Cookie value that stores SID in the browser for the session lifetime."
  [req sid]
  (str cookie-name "=" sid "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" db/session-ttl-seconds
       (when (https? req) "; Secure")))

(defn login-success
  "Returns the login success response."
  [req session]
  {:status 200
   :body ""
   :headers {"Content-Type" "text/plain"
             "Set-Cookie" (session-cookie req (:sid session))
             "HX-Redirect" "/app"}})

(defn login-fail
  "Returns the login fail response."
  []
  {:status 401
   :body "Invalid username or password."
   :headers {"Content-Type" "text/plain"}})

(defn initialize-session
  "Create a new session for USER and store it, returns the session obj. The session only
  identifies the user: role and enabled flag are re-read on every request."
  [user]
  (let [sid (str (java.util.UUID/randomUUID))
        session {:sid sid :user user}]
    (db/new-session sid session)
    session))

(def ^:private dummy-hash
  "Checked against when the user doesn't exist, so a login takes as long either way and
  doesn't reveal which usernames exist."
  (delay (hash-password (str (java.util.UUID/randomUUID)))))

(defn try-login
  "Try to log into the application. Failed attempts are rate limited per address."
  [req]
  (let [form-params (:form-params req)
        user (get form-params "username")
        pass (get form-params "password")
        address (rl/client-address req)]
    (cond
      (rl/limited? :login address) (rl/too-many-requests)
      (or (str/blank? user) (str/blank? pass)) (login-fail)
      :else
      (let [row (first (jdbc/query (db/get-db-spec)
                                   ["select password from users where username = ? and enabled = 1" user]))]
        (if (and (verify-password pass (or (:password row) @dummy-hash)) row)
          (login-success req (initialize-session user))
          (do (rl/record! :login address)
              (login-fail)))))))

(defn get-sid-from-req
  "Returns the session id from the request."
  [req]
  (get-in req [:cookies cookie-name :value]))

(defn logout
  "Delete the session and redirect to the login page."
  [req]
  (when-let [sid (get-sid-from-req req)]
    (db/delete-session sid))
  {:status 302
   :headers {"Location" "/login"
             "Set-Cookie" (str cookie-name "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0")}
   :body ""})

(defn has-session?
  "Returns true if the request has a session and the session is valid."
  [req]
  (when-let [sid (get-sid-from-req req)]
    (db/valid-session? sid)))

(defn get-session
  "Get the session data using the given request."
  [req]
  (when-let [sid (get-sid-from-req req)]
    (db/get-session sid)))

(defn htmx-request?
  "Returns true if the request was made by htmx."
  [req]
  (= "true" (get-in req [:headers "hx-request"])))

(defn login-redirect
  "Redirect to the login page, using HX-Redirect for htmx requests so partial swaps don't embed the login page."
  [req]
  (if (htmx-request? req)
    {:status 200 :headers {"HX-Redirect" "/login"} :body ""}
    {:status 302 :headers {"Location" "/login"} :body ""}))
