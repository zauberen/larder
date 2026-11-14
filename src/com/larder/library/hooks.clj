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

(ns com.larder.library.hooks
  "The seam between the recipe and ingredient stores and the library (the git repo of
  text files that is the source of truth for them). `recipes` and `ingredients` wrap
  their writes in `writing` and report them with `changed!`; `com.larder.library`
  listens and writes the files. Nothing listens until the library starts, so tests and
  setup write the database alone."
  (:require [clojure.tools.logging :as log]))

;; Held while the database and the files are written, so a sync from the files (after a
;; pull) never interleaves with a save.
(defonce lock (Object.))

(defonce ^:private listener (atom nil))

(defn set-listener!
  "Call F with each batch of changes (nil stops listening)."
  [f]
  (reset! listener f))

(defmacro writing
  "Run BODY holding the library lock."
  [& body]
  `(locking lock ~@body))

(defn changed!
  "Report CHANGES, maps of :kind (:recipe or :ingredient), :action (:add, :edit or :del),
  :id and :name. The database is already written, so a failure to write the files is
  logged rather than thrown."
  [& changes]
  (when-let [f @listener]
    (try
      (locking lock (f (vec changes)))
      (catch Exception e
        (log/error e "Couldn't write the library files for" (pr-str changes))))))
