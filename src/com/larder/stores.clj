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

(ns com.larder.stores
  "Stores grocery items can be assigned to."
  (:require [clojure.string :as str]
            [com.larder.database.db :as db]))

(defn all
  []
  (db/run-db-query ["select id, name, notes from stores order by lower(name)"]))

(defn by-id
  [id]
  (first (db/run-db-query ["select id, name, notes from stores where id = ?" id])))

(defn by-name
  [name]
  (first (db/run-db-query ["select id, name, notes from stores where name = ?" (str/trim (str name))])))

(defn validate
  [name notes]
  (cond-> []
    (str/blank? name) (conj "Give the store a name.")
    (> (count (str name)) 60) (conj "Store names can be up to 60 characters.")
    (> (count (str notes)) 500) (conj "Notes can be up to 500 characters.")))

(defn create!
  "Create a store. Returns its id (the existing one's when the name is taken)."
  [name & [notes]]
  (or (:id (by-name name))
      (db/insert! ["insert into stores (name, notes) values (?, ?)" (str/trim name) (some-> notes str/trim not-empty)])))

(defn find-or-create!
  "The id of the store called NAME, creating it; nil for a blank name."
  [name]
  (let [name (str/trim (str name))]
    (when (seq name) (create! name))))

(defn delete!
  [id]
  (db/exe-db-stmt ["delete from stores where id = ?" id]))
