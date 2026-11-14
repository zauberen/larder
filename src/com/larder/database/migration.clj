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

(ns com.larder.database.migration
  "Idempotent schema migrations. Every statement in `migrations` must be safe to run
  again (create ... if not exists, alter table add column, which fails harmlessly with
  a duplicate column error). They all run whenever `server-version` changes.
  Times are stored as epoch milliseconds."
  (:require [clojure.java.jdbc :as jdbc]))

(def server-version "0.1.0")
;; Reset whenever this namespace is reloaded; set once check-run-migrations has run.
(def migration-chk-ran (atom nil))

(def migrations
  [(str "create table if not exists db_version ("
        "id integer primary key autoincrement,"
        "version_number text)")
   (str "insert into db_version (version_number) values ('" server-version "')")
   ;; Roles: admin (everything, plus users and site settings), user (everything else).
   (str "create table if not exists users ("
        "id integer primary key autoincrement,"
        "username text unique not null,"
        "password text not null,"
        "role text not null default 'user',"
        "enabled integer not null default 1,"
        "created_at integer not null,"
        "modified_at integer not null)")
   (str "create table if not exists sessions ("
        "sid text primary key,"
        "session_obj text not null,"
        "created_at integer not null)")
   (str "create table if not exists global_settings ("
        "key text primary key,"
        "value text)")])

(defn run-migrations
  "Initialize or update a database. No user is created: the first admin comes from onboarding."
  [db-spec]
  (doseq [migration migrations]
    (try
      (jdbc/execute! db-spec [migration])
      (catch Exception e
        ;; Ignore duplicate column errors from alter table statements
        (when-not (re-find #"(?i)duplicate column" (.getMessage e))
          (throw e))))))

(defn get-server-version-query
  "Returns a query to check the server version."
  []
  "select version_number from db_version where id = (select max(id) from db_version)")

(defn check-run-migrations
  "Run the migrations for DB-SPEC when the database is at another version."
  [db-spec]
  (if-some [_ @migration-chk-ran]
    true ; Already checked since this namespace was loaded.
    (let [ver-rs (try (jdbc/query db-spec (get-server-version-query))
                      ;; A database file without the db_version table (e.g. an empty file).
                      (catch java.sql.SQLException _ []))]
      (reset! migration-chk-ran true)
      (when (or (<= (count ver-rs) 0)
                (not= server-version (:version_number (nth ver-rs 0))))
        (run-migrations db-spec)))))
