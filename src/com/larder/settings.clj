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

(ns com.larder.settings
  "The settings file (~/.larder): where Larder keeps its data and which port it serves.
  Settings editable by admins at runtime (site name, access levels) live in the
  global_settings table instead (see `com.larder.global-settings`)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [com.larder.wrappers.path-utils :as pu]))

(defn get-default-settings
  "Returns the default settings as a settings object."
  []
  {:data-root "~/larder"
   :db 'sqlite
   :sqlite-db-path "~/larder/larder.db"
   ;; Block Shipper uses 8081, so both can run on one machine.
   :listen-port 8085
   ;; Enables /api/echo (admins only), which prints raw requests.
   :debug false
   ;; Files (recipe images, import archives) are uploaded in pieces of this many MB.
   :upload-chunk-mb 1})

(defn config-path
  "Where the settings file lives."
  []
  "~/.larder")

(def config-keys
  "Settings written by onboarding and editable under Settings -> Site -> Server configuration."
  [:listen-port :data-root :sqlite-db-path :upload-chunk-mb :debug])

(def restart-keys
  "Settings read once when Larder starts; changing them needs a restart."
  #{:listen-port :data-root :sqlite-db-path :db})

;; The restart-only settings this process is running with.
(defonce ^:private pinned (atom nil))

(defn configured?
  "True when a settings file exists."
  []
  (pu/file-exists? (config-path)))

(defn- read-config-file
  "The settings file as a map, {} when it doesn't exist."
  []
  (if (configured?)
    (or (edn/read-string (pu/read-file-safe (config-path))) {})
    {}))

(defn file-settings
  "Defaults merged with the settings file, as saved. Restart-only keys may differ from
  what is in effect (see `get-settings`)."
  []
  (try
    (merge (get-default-settings) (read-config-file))
    (catch Exception e
      (println (str "Error reading " (config-path) ": " (.getMessage e)))
      (.printStackTrace e)
      (get-default-settings))))

(defn get-settings
  "Returns the settings in effect: the settings file, with restart-only keys as they
  were when Larder started."
  []
  (merge (file-settings) @pinned))

(defn pin-restart-settings!
  "Fix the restart-only keys to their values in SETTINGS (default: the settings file)."
  ([] (pin-restart-settings! (file-settings)))
  ([settings] (reset! pinned (select-keys settings restart-keys))))

(defn pending-restart
  "Restart-only keys whose saved value differs from the one in effect."
  []
  (let [saved (file-settings)
        running (get-settings)]
    (filterv #(not= (get saved %) (get running %)) (filter restart-keys config-keys))))

(defn save-settings!
  "Merge UPDATES into the settings file and write it."
  [updates]
  (let [settings (merge (read-config-file) updates)
        file (io/file (pu/expand-home-path (config-path)))
        tmp (io/file (str (.getPath file) ".tmp"))]
    (when-let [parent (.getParentFile file)] (.mkdirs parent))
    (spit tmp (str ";; Larder settings. Edit from Settings -> Site, or by hand while Larder is stopped.\n"
                   (with-out-str (pprint/pprint (into (sorted-map) settings)))))
    (java.nio.file.Files/move (.toPath tmp) (.toPath file)
                              (into-array java.nio.file.CopyOption
                                          [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                           java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
    settings))

(defn data-dir
  "The expanded data folder, or a folder inside it."
  ([] (pu/expand-home-path (:data-root (get-settings))))
  ([sub] (str (data-dir) "/" sub)))

(defn get-logfile
  "Today's log file name, inside `(data-dir \"logs\")`."
  []
  (let [date (java.time.LocalDate/now)]
    (str (format "%04d%02d%02d" (.getYear date) (.getMonthValue date) (.getDayOfMonth date))
         "-larder.log")))

(defn get-log-path
  "Returns the full expanded path to today's log file."
  []
  (str (data-dir "logs") "/" (get-logfile)))
