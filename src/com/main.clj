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

(ns com.main
  "Entry point. Only the settings namespace is loaded up front: slf4j reads its
  configuration once, when the first logger is created, and loading the web server or
  clojure.tools.logging creates one. So logging is configured first and everything else
  is loaded after."
  (:gen-class)
  (:require [com.larder.settings :as s]))

(defn- configure-logging
  []
  (let [log-path (s/get-log-path)]
    ;; slf4j opens the file before startup creates the data folder.
    (.mkdirs (.getParentFile (java.io.File. log-path)))
    (System/setProperty "org.slf4j.simpleLogger.logFile" log-path)
    (System/setProperty "org.slf4j.simpleLogger.showDateTime" "true")
    (System/setProperty "org.slf4j.simpleLogger.dateTimeFormat" "yyyy-MM-dd HH:mm:ss")
    (System/setProperty "org.slf4j.simpleLogger.defaultLogLevel" "info")))

(defn -main [& _]
  (configure-logging)
  ((requiring-resolve 'com.larder/start-server)))

(defn -stop []
  ((requiring-resolve 'com.larder/stop-server)))
