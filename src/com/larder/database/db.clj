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

(ns com.larder.database.db
  "Database access and the session store (SQLite only; Larder has no Valkey store)."
  (:require [clojure.edn :as edn]
            [clojure.java.jdbc :as jdbc]
            [com.larder.database.sqlite :as sqlite]
            [com.larder.settings :as s]
            [com.larder.util :as u]))

(defn get-db-spec
  "Get the db-spec so you can connect and run queries against the database."
  []
  (case (:db (s/get-settings))
    sqlite (do (sqlite/initialize) (sqlite/db-spec))))

(defn run-db-query
  "Query the database given STMT query string or vec of prepared statment and args."
  [stmt]
  (jdbc/query (get-db-spec) stmt))

(defn exe-db-stmt
  "Execute a statement on the database given STMT query string or vec of prepared statment and args."
  [stmt]
  (jdbc/execute! (get-db-spec) stmt))

(def session-ttl-seconds
  "How long a login lasts."
  (* 30 24 60 60))

(defn- session-cutoff
  "Sessions created before this time (epoch ms) have expired."
  []
  (- (u/now-ms) (* 1000 session-ttl-seconds)))

(defn new-session
  "Store SESSION-OBJ under SID. Expired sessions are removed at the same time."
  [sid session-obj]
  (jdbc/execute! (get-db-spec) ["delete from sessions where created_at <= ?" (session-cutoff)])
  (jdbc/execute! (get-db-spec) ["insert into sessions (sid, session_obj, created_at) values (?, ?, ?)"
                                sid (pr-str session-obj) (u/now-ms)]))

(defn- session-row
  [sid]
  (first (run-db-query ["select session_obj from sessions where sid = ? and created_at > ?"
                        sid (session-cutoff)])))

(defn get-session
  "The unexpired session stored under SID, or nil."
  [sid]
  (some-> (session-row sid) :session_obj edn/read-string))

(defn valid-session?
  "Check if SID is a valid session ID."
  [sid]
  (some? (session-row sid)))

(defn delete-session
  "Delete the session associated with SID."
  [sid]
  (jdbc/execute! (get-db-spec) ["delete from sessions where sid = ?" sid]))

(defn delete-user-sessions!
  "Log USERNAME out everywhere."
  [username]
  (doseq [{:keys [sid session_obj]} (run-db-query ["select sid, session_obj from sessions"])
          :when (= username (:user (edn/read-string session_obj)))]
    (delete-session sid)))
