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

(ns com.larder.library.git
  "Runs the git command line in the library folder. Nothing waits for a terminal: git
  never prompts (GIT_TERMINAL_PROMPT=0, ssh in batch mode) and every command has a
  time limit, so a remote that wants a password fails instead of hanging Larder."
  (:require [clojure.string :as str])
  (:import (java.io File)
           (java.util.concurrent TimeUnit)))

(def timeout-seconds 120)

(defn run
  "Run git with ARGS in DIR. Returns {:exit :out :err}; :exit is -1 when git couldn't be
  started or timed out."
  [^File dir & args]
  (try
    (let [pb (ProcessBuilder. ^java.util.List (vec (cons "git" (map str args))))
          env (.environment pb)]
      (.directory pb dir)
      (.put env "GIT_TERMINAL_PROMPT" "0")
      (.put env "LC_ALL" "C")
      (when-not (.containsKey env "GIT_SSH_COMMAND")
        (.put env "GIT_SSH_COMMAND" "ssh -o BatchMode=yes"))
      (let [p (.start pb)
            _ (.close (.getOutputStream p))
            out (future (slurp (.getInputStream p)))
            err (future (slurp (.getErrorStream p)))]
        (if (.waitFor p timeout-seconds TimeUnit/SECONDS)
          {:exit (.exitValue p) :out @out :err @err}
          (do (.destroyForcibly p)
              {:exit -1 :out "" :err (str "git " (first args) " took longer than " timeout-seconds " s")}))))
    (catch java.io.IOException e
      {:exit -1 :out "" :err (str "Couldn't run git: " (.getMessage e))})))

(defn ok?
  [result]
  (zero? (:exit result)))

(defn out
  "Trimmed output of a successful git command, nil otherwise."
  [dir & args]
  (let [r (apply run dir args)]
    (when (ok? r) (str/trim (:out r)))))

(defn available?
  []
  (ok? (run (File. ".") "--version")))

(defn error-text
  "A one-paragraph description of a failed RESULT."
  [what result]
  (str what ": " (or (not-empty (str/trim (str (:err result) "\n" (:out result)))) (str "exit " (:exit result)))))
