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

(ns com.larder.api-keys
  "Per-user API keys for the MCP endpoint. A key (lk_<key id>_<secret>) is shown once;
  only its SHA-256 is stored, looked up by the key id and compared in constant time."
  (:require [clojure.string :as str]
            [com.larder.database.db :as db]
            [com.larder.util :as u])
  (:import (java.security MessageDigest SecureRandom)
           (java.util Base64 HexFormat)))

(defn sha256-hex
  [^String s]
  (.formatHex (HexFormat/of) (.digest (MessageDigest/getInstance "SHA-256") (.getBytes s "UTF-8"))))

(defn constant-time=
  "Compare two strings without an early exit."
  [^String a ^String b]
  (boolean (and a b (MessageDigest/isEqual (.getBytes a "UTF-8") (.getBytes b "UTF-8")))))

(defn random-token
  "N random bytes, URL-safe base64 without padding."
  [n]
  (let [b (byte-array n)]
    (.nextBytes (SecureRandom.) b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))

(defn validate-name
  [name]
  (cond-> []
    (str/blank? name) (conj "Give the key a name, such as the computer or app that uses it.")
    (> (count (str name)) 60) (conj "Key names can be up to 60 characters.")))

(defn create!
  "Create a key named NAME for USER-ID. Returns the key (the only time it is visible)."
  [user-id name]
  (let [key-id (str/replace (random-token 6) #"[-_]" "x")
        k (str "lk_" key-id "_" (random-token 32))]
    (db/insert! ["insert into api_keys (user_id, name, key_id, key_hash, created_at) values (?, ?, ?, ?, ?)"
                 user-id (str/trim name) key-id (sha256-hex k) (u/now-ms)])
    k))

(defn lookup
  "The active key row for the presented key K, or nil. Records its last use."
  [k]
  (when-let [[_ key-id] (and (string? k) (re-matches #"lk_([A-Za-z0-9]{8})_[A-Za-z0-9_-]{43}" k))]
    (when-let [row (first (db/run-db-query ["select * from api_keys where key_id = ? and revoked_at is null" key-id]))]
      (when (constant-time= (:key_hash row) (sha256-hex k))
        (db/exe-db-stmt ["update api_keys set last_used_at = ? where id = ?" (u/now-ms) (:id row)])
        row))))

(defn for-user
  [user-id]
  (db/run-db-query ["select id, name, key_id, created_at, last_used_at from api_keys where user_id = ? and revoked_at is null order by created_at desc" user-id]))

(defn all-active
  []
  (db/run-db-query [(str "select k.id, k.name, k.key_id, k.created_at, k.last_used_at, u.username from api_keys k"
                         " join users u on u.id = k.user_id where k.revoked_at is null order by k.created_at desc")]))

(defn revoke!
  "Revoke key ID; when USER-ID is given, only if it belongs to that user. Returns true if revoked."
  [id & [user-id]]
  (pos? (first (db/exe-db-stmt (if user-id
                                 ["update api_keys set revoked_at = ? where id = ? and user_id = ? and revoked_at is null" (u/now-ms) id user-id]
                                 ["update api_keys set revoked_at = ? where id = ? and revoked_at is null" (u/now-ms) id])))))
