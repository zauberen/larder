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

(ns com.larder.global-settings
  "Site settings editable by admins from the UI (the global_settings table)."
  (:require [clojure.string :as str]
            [com.larder.database.db :as db])
  (:import (java.net URI)))

(def default-site-name "Larder")

(def defaults
  {"site_name" default-site-name
   ;; Public base URL, e.g. https://larder.example.com. Used in OAuth metadata and share
   ;; links; blank means the address the page was opened with.
   "public_base_url" ""})

(defn all-settings
  "Every site setting as a map of string key to string value, defaults included."
  []
  (merge defaults
         (into {} (map (juxt :key :value)) (db/run-db-query ["select key, value from global_settings"]))))

(defn get-setting
  [k]
  (get (all-settings) k))

(defn put!
  [k v]
  (db/exe-db-stmt ["insert into global_settings (key, value) values (?, ?) on conflict(key) do update set value = excluded.value"
                   k (str v)]))

(defn site-name
  []
  (let [n (get-setting "site_name")]
    (if (str/blank? n) default-site-name n)))

(defn valid-base-url?
  "True for an http(s) URL with a host and no query or fragment."
  [s]
  (try
    (let [uri (URI. s)]
      (boolean (and (#{"http" "https"} (some-> (.getScheme uri) str/lower-case))
                    (not (str/blank? (.getHost uri)))
                    (nil? (.getRawQuery uri))
                    (nil? (.getRawFragment uri)))))
    (catch Exception _ false)))

(defn public-base-url
  "The configured public base URL without a trailing slash, or nil."
  []
  (some-> (get-setting "public_base_url") str/trim not-empty (str/replace #"/+$" "")))
