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

(ns com.larder.users
  "Larder accounts. Admins manage users and site settings; users do everything else."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [com.larder.api.session :as session]
            [com.larder.database.db :as db]
            [com.larder.util :as u]))

(def roles ["admin" "user"])

(def ^:private columns "id, username, role, enabled, created_at")

(defn by-username
  "The enabled user named USERNAME, or nil."
  [username]
  (first (db/run-db-query [(str "select " columns " from users where username = ? and enabled = 1")
                           username])))

(defn by-id
  "The user with ID, enabled or not, or nil."
  [id]
  (first (db/run-db-query [(str "select " columns " from users where id = ?") id])))

(defn all-users
  []
  (db/run-db-query [(str "select " columns " from users order by lower(username)")]))

(defn any-users?
  "True once at least one account exists."
  []
  (boolean (seq (db/run-db-query ["select id from users limit 1"]))))

(defn admin-count
  "Enabled admins."
  []
  (count (db/run-db-query ["select id from users where role = 'admin' and enabled = 1"])))

(defn validate-new
  "Errors for creating USERNAME with PASSWORD and ROLE."
  [username password role]
  (cond-> []
    (or (str/blank? username) (not (re-matches #"[A-Za-z0-9_.-]{1,32}" username)))
    (conj "Usernames are 1-32 letters, numbers, dots, dashes or underscores.")
    (and (not (str/blank? username))
         (seq (db/run-db-query ["select id from users where username = ?" username])))
    (conj "That username is taken.")
    (< (count (or password "")) 8)
    (conj "Passwords must be at least 8 characters.")
    (not (some #{role} roles))
    (conj "Unknown role.")))

(defn create!
  [username password role]
  (let [t (u/now-ms)]
    (db/exe-db-stmt ["insert into users (username, password, role, created_at, modified_at) values (?, ?, ?, ?, ?)"
                     username (session/hash-password password) role t t]))
  (by-username username))

(defn set-password!
  [user-id password]
  (db/exe-db-stmt ["update users set password = ?, modified_at = ? where id = ?"
                   (session/hash-password password) (u/now-ms) user-id]))

(defn check-password
  "True when PASSWORD is USERNAME's current password."
  [username password]
  (when-let [row (first (db/run-db-query ["select password from users where username = ?" username]))]
    (boolean (session/verify-password (or password "") (:password row)))))

(defn set-role!
  [user-id role]
  (db/exe-db-stmt ["update users set role = ?, modified_at = ? where id = ?" role (u/now-ms) user-id]))

(defn set-enabled!
  "Enable or disable USER-ID. Disabled users are refused on their next request (the flag
  is read on every request) and can't log in."
  [user-id enabled?]
  (db/exe-db-stmt ["update users set enabled = ?, modified_at = ? where id = ?"
                   (if enabled? 1 0) (u/now-ms) user-id]))

(defn delete!
  [user-id]
  (when-let [user (by-id user-id)]
    (jdbc/with-db-transaction [tx (db/get-db-spec)]
      (jdbc/execute! tx ["delete from users where id = ?" user-id]))
    (db/delete-user-sessions! (:username user))))

(defn last-admin?
  "True when USER is the only enabled admin left."
  [user]
  (and (= "admin" (:role user)) (= 1 (:enabled user)) (<= (admin-count) 1)))
