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

(ns com.larder.library
  "The library: a git repo of text files (recipes/<slug>.txt and ingredients.txt) that is
  the source of truth for recipes and ingredients. The recipe and ingredient tables are
  an index of it: rebuilt from the files at startup and after a pull, and written through
  on every save (`hooks/changed!` -> `on-change`, which rewrites the affected files).

  Changes are committed after a quiet period (`:library-commit-delay-seconds`, at most
  ten periods after the first change), so cataloguing a pile of ingredients makes one
  commit. A commit is followed by a pull (fetch, then rebase onto the remote branch; if
  that brought new commits the files are loaded again) and a push. A failed fetch, rebase
  or push is logged, shown under Settings -> Site, and retried later."
  (:require [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.larder.database.db :as db]
            [com.larder.images :as images]
            [com.larder.ingredients :as ingredients]
            [com.larder.library.format :as fmt]
            [com.larder.library.git :as git]
            [com.larder.library.hooks :as hooks]
            [com.larder.recipes :as recipes]
            [com.larder.settings :as s]
            [com.larder.util :as u]
            [com.larder.wrappers.path-utils :as pu])
  (:import (java.io File)
           (java.nio.file CopyOption Files StandardCopyOption)
           (java.util.concurrent Executors ScheduledFuture ThreadFactory TimeUnit)))

(def remote-name "origin")

(def ^:private fallback-identity
  "Used when git has no user.email configured for the library."
  ["-c" "user.name=Larder" "-c" "user.email=larder@localhost"])

(def retry-ms
  "How long to wait before trying again after a failed commit, pull or push."
  (* 10 60 1000))

;; Nil until `init!`. {:dir File :git? :identity [args] :remote? :files {recipe id -> file
;; name} :pending [changes] :first-at ms :task ScheduledFuture :status {...}}
(defonce ^:private state (atom nil))

(defonce ^:private executor
  (delay (Executors/newSingleThreadScheduledExecutor
          (reify ThreadFactory
            (newThread [_ r] (doto (Thread. ^Runnable r "larder-library") (.setDaemon true)))))))

;; One flush at a time (the timer, Commit now and the shutdown hook may overlap).
(defonce ^:private flush-lock (Object.))

;;; Files

(defn library-dir
  ^File []
  (io/file (pu/expand-home-path (s/library-path))))

(defn- recipes-dir ^File [dir] (io/file dir "recipes"))
(defn- ingredients-file ^File [dir] (io/file dir "ingredients.txt"))

(defn- read-text
  [^File f]
  (slurp f :encoding "UTF-8"))

(defn- write-if-changed!
  "Write TEXT to F (atomically) unless it already holds it. True when it wrote."
  [^File f ^String text]
  (if (and (.exists f) (= text (read-text f)))
    false
    (let [dir (.getParentFile f)
          tmp (io/file dir (str "." (.getName f) ".tmp"))]
      (.mkdirs dir)
      (spit tmp text :encoding "UTF-8")
      (Files/move (.toPath tmp) (.toPath f)
                  (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))
      true)))

(defn- db-ingredients
  []
  (let [aliases (ingredients/all-aliases)]
    (map #(assoc % :aliases (get aliases (:id %) [])) (ingredients/all))))

(defn- ingredients-text
  []
  (fmt/render-ingredients (db-ingredients)))

(defn- recipe-files
  [dir]
  (->> (.listFiles (recipes-dir dir))
       (filter (fn [^File f] (let [n (.getName f)]
                               (and (.isFile f) (str/ends-with? n ".txt") (not (str/starts-with? n "."))))))
       (sort-by #(.getName ^File %))))

(defn- file-name-for
  "The file name for recipe ID titled TITLE: its current one while that still fits the
  title, else the title's slug (numbered when another recipe has it)."
  [id title]
  (let [{:keys [files dir]} @state
        base (fmt/slug title)
        current (get files id)
        taken (set (map str/lower-case (vals (dissoc files id))))
        fits? #(re-matches (re-pattern (str "\\Q" base "\\E(-\\d+)?\\.txt")) %)]
    (if (and current (fits? current))
      current
      (first (for [n (cons nil (iterate inc 2))
                   :let [candidate (str base (when n (str "-" n)) ".txt")]
                   :when (and (not (taken candidate))
                              (or (= candidate current) (not (.exists (io/file (recipes-dir dir) candidate)))))]
               candidate)))))

;;; Status

(defn- fail!
  "Remember and log a problem. RESULT, when given, is the failed git command's result."
  ([text] (fail! text nil))
  ([text result]
   (let [msg (if result (git/error-text text result) text)]
     (log/warn "Library:" msg)
     (swap! state assoc-in [:status :error] {:text msg :at (u/now-ms)})
     false)))

(defn- g
  "Run git in the library with the commit identity."
  [& args]
  (let [{:keys [dir identity]} @state]
    (apply git/run dir (concat identity args))))

(defn- head
  []
  (git/out (:dir @state) "rev-parse" "-q" "--verify" "HEAD"))

(defn- branch
  []
  (or (not-empty (git/out (:dir @state) "symbolic-ref" "--short" "HEAD")) "main"))

(defn- dirty?
  []
  (not (str/blank? (:out (g "status" "--porcelain")))))

(defn status
  "What the library is doing, for Settings -> Site; nil before it starts."
  []
  (when-let [{:keys [dir git? remote? pending first-at task status]} @state]
    {:dir (.getPath ^File dir)
     :git? git?
     :remote (when remote? (git/out dir "remote" "get-url" remote-name))
     :pending (count (fmt/collapse pending))
     :commit-at (when (and first-at task (not (.isDone ^ScheduledFuture task)))
                  (+ (u/now-ms) (.getDelay ^ScheduledFuture task TimeUnit/MILLISECONDS)))
     :last-commit-at (:last-commit-at status)
     :last-push-at (:last-push-at status)
     :error (:error status)}))

;;; Scheduling

(declare flush!)

(defn- delay-ms
  []
  (* 1000 (max 0 (or (:library-commit-delay-seconds (s/get-settings)) 120))))

(defn- run-flush!
  []
  (try
    (when-not (flush!)
      (locking hooks/lock
        (when-let [^ScheduledFuture t (:task @state)] (.cancel t false))
        (swap! state assoc :task (.schedule ^java.util.concurrent.ScheduledExecutorService @executor
                                            ^Runnable run-flush! retry-ms TimeUnit/MILLISECONDS))))
    (catch Throwable e
      (log/error e "Library: commit failed"))))

(defn- schedule!
  "(Re)start the quiet period before the next commit."
  []
  (locking hooks/lock
    (when (:git? @state)
      (let [now (u/now-ms)
            d (delay-ms)
            {:keys [first-at task]} (swap! state update :first-at #(or % now))
            at (min (+ now d) (+ first-at (* 10 d)))]
        (when task (.cancel ^ScheduledFuture task false))
        (swap! state assoc :task (.schedule ^java.util.concurrent.ScheduledExecutorService @executor
                                            ^Runnable run-flush! (max 0 (- at now)) TimeUnit/MILLISECONDS))))))

(defn- record!
  [changes]
  (when (seq changes)
    (swap! state update :pending into changes)
    (schedule!)))

;;; Writing the files (database -> files)

(defn- write-recipe!
  "Write (or remove) the file of the recipe CHANGE is about. Returns CHANGE when a file
  changed."
  [{:keys [id action] :as change}]
  (let [{:keys [dir files]} @state
        rdir (recipes-dir dir)
        old (get files id)]
    (if (= :del action)
      (when old
        (io/delete-file (io/file rdir old) true)
        (swap! state update :files dissoc id)
        change)
      (when-let [r (recipes/by-id id)]
        (let [name (file-name-for id (:title r))
              wrote? (write-if-changed! (io/file rdir name) (fmt/render-recipe r))
              moved? (and old (not= old name))]
          (when moved? (io/delete-file (io/file rdir old) true))
          (swap! state assoc-in [:files id] name)
          (when (or wrote? moved?) change))))))

(defn- on-change
  "Write the files for CHANGES (called by `hooks/changed!` holding the lock) and queue
  the ones that changed a file for the next commit."
  [changes]
  (when @state
    (let [{recipe-changes :recipe ingredient-changes :ingredient} (group-by :kind changes)
          written (keep write-recipe! recipe-changes)
          ingredients? (and (seq ingredient-changes)
                            (write-if-changed! (ingredients-file (:dir @state)) (ingredients-text)))]
      (record! (concat written (when ingredients? ingredient-changes))))))

;;; Loading the files (files -> database)

(def ^:private ingredient-fields [:name :dimension :density :default_quantity :default_unit :always_have])

(defn- apply-ingredients!
  "Make the ingredient tables match PARSED (from ingredients.txt) in transaction TX."
  [tx parsed]
  (let [existing (into {} (map (juxt :id identity))
                       (jdbc/query tx [(str "select id, " (str/join ", " (map name ingredient-fields)) " from ingredients")]))
        listed (set (keep :id parsed))
        t (u/now-ms)]
    (doseq [id (keys existing) :when (not (listed id))]
      (jdbc/execute! tx ["delete from ingredients where id = ?" id]))
    (jdbc/execute! tx ["delete from ingredient_aliases"])
    ;; Free renamed ingredients' names first, so two ingredients can swap names.
    (doseq [{:keys [id name]} parsed
            :when (and id (existing id) (not= name (:name (existing id))))]
      (jdbc/execute! tx ["update ingredients set name = ? where id = ?" (str "\u0001rename-" id) id]))
    (doseq [{:keys [id aliases] :as ing} parsed]
      (let [vals (map ing ingredient-fields)
            id (if (and id (existing id))
                 (do (when (not= (select-keys ing ingredient-fields) (select-keys (existing id) ingredient-fields))
                       (jdbc/execute! tx (into [(str "update ingredients set "
                                                     (str/join ", " (map #(str (name %) " = ?") ingredient-fields))
                                                     ", modified_at = ? where id = ?")]
                                               (concat vals [t id]))))
                     id)
                 (let [cols (cond->> (concat ingredient-fields [:created_at :modified_at]) id (cons :id))]
                   (jdbc/execute! tx (into [(str "insert into ingredients (" (str/join ", " (map name cols)) ") values ("
                                                 (str/join ", " (repeat (count cols) "?")) ")")]
                                           (cond->> (concat vals [t t]) id (cons id))))
                   (or id (:id (first (jdbc/query tx ["select last_insert_rowid() as id"]))))))]
        (doseq [a aliases]
          (jdbc/execute! tx ["insert or ignore into ingredient_aliases (ingredient_id, alias) values (?, ?)" id a]))))))

(defn- sync-ingredients!
  "Load ingredients.txt into the database. True when it rewrote the file (new
  ingredients get their ids written back)."
  [dir]
  (let [f (ingredients-file dir)]
    (when (.exists f)
      (let [text (read-text f)]
        (when (not= text (ingredients-text))
          (let [[parsed errors] (fmt/parse-ingredients text)]
            (if (seq errors)
              (do (fail! (str "ingredients.txt wasn't loaded (the database keeps the previous ingredients): "
                              (str/join " " (take 5 errors))))
                  false)
              (do (jdbc/with-db-transaction [tx (db/get-db-spec)]
                    (apply-ingredients! tx parsed))
                  (log/info "Library: loaded ingredients.txt")
                  (write-if-changed! f (ingredients-text))))))))))

(def ^:private recipe-columns
  [:title :description :servings :prep_minutes :cook_minutes :steps :source_url :rating
   :status :image_path :created_at :modified_at])

(defn- save-recipe!
  "Store recipe R read from a file: over EXISTING-ID, or as a new recipe (with FILE-ID
  when given). Returns its id."
  [r existing-id file-id]
  (jdbc/with-db-transaction [tx (db/get-db-spec)]
    (let [t (u/now-ms)
          r (-> r
                (update :created_at #(or % t))
                (update :modified_at #(or % t)))
          id (if existing-id
               (do (jdbc/execute! tx (into [(str "update recipes set "
                                                 (str/join ", " (map #(str (name %) " = ?") recipe-columns))
                                                 " where id = ?")]
                                           (concat (map r recipe-columns) [existing-id])))
                   existing-id)
               (let [cols (cond->> recipe-columns file-id (cons :id))]
                 (jdbc/execute! tx (into [(str "insert into recipes (" (str/join ", " (map name cols)) ") values ("
                                               (str/join ", " (repeat (count cols) "?")) ")")]
                                         (cond->> (map r recipe-columns) file-id (cons file-id))))
                 (or file-id (:id (first (jdbc/query tx ["select last_insert_rowid() as id"]))))))]
      (recipes/write-children! tx id r)
      id)))

(defn- delete-recipe-row!
  [id]
  (let [img (:image_path (first (db/run-db-query ["select image_path from recipes where id = ?" id])))]
    (db/exe-db-stmt ["delete from recipes where id = ?" id])
    (when (and img (nil? (recipes/by-image img)))
      (images/delete! img))))

(defn- sync-recipes!
  "Load recipes/*.txt into the database. A file with errors is reported and its recipe
  kept as it was; a recipe without a file is deleted. Returns true when it rewrote a file
  (a new recipe's id, or a hand edit put back in the usual layout)."
  [dir]
  (when (.isDirectory (recipes-dir dir))
    (let [db-ids (set (map :id (db/run-db-query ["select id from recipes"])))
          files (volatile! {})
          rewrote (volatile! false)]
      (doseq [^File f (recipe-files dir)]
        (let [text (read-text f)
              fname (.getName f)
              [r errors] (fmt/parse-recipe text)]
          (if (seq errors)
            (let [hid (fmt/header-id text)]
              (fail! (str "recipes/" fname " wasn't loaded (the database keeps the previous version): "
                          (str/join " " (take 3 errors))))
              (when (and hid (not (contains? @files hid)))
                (vswap! files assoc hid fname)))
            ;; A copied file repeats another recipe's id: it becomes a new recipe.
            (let [file-id (when-not (contains? @files (:id r)) (:id r))
                  current (when (and file-id (db-ids file-id)) (recipes/by-id file-id))
                  id (if (and current (= text (fmt/render-recipe current)))
                       file-id
                       (save-recipe! r (when current file-id) (when-not current file-id)))]
              (vswap! files assoc id fname)
              (when (write-if-changed! f (fmt/render-recipe (recipes/by-id id)))
                (vreset! rewrote true))))))
      (doseq [id db-ids :when (not (contains? @files id))]
        (log/info "Library: recipe" id "has no file any more; deleting it")
        (delete-recipe-row! id))
      (swap! state assoc :files @files)
      @rewrote)))

(defn sync!
  "Load the library's files into the database. Run at startup and after a pull."
  []
  (hooks/writing
   (let [dir (:dir @state)
         rewrote-ingredients? (sync-ingredients! dir)
         rewrote-recipes? (sync-recipes! dir)]
     ;; Lines of ingredients deleted from ingredients.txt
     (db/exe-db-stmt [(str "update recipe_ingredients set ingredient_id = null where ingredient_id is not null"
                           " and ingredient_id not in (select id from ingredients)")])
     (ingredients/rematch-unmatched!)
     (when (or rewrote-ingredients? rewrote-recipes?)
       (schedule!)))))

(defn- export-missing!
  "Write the files that don't exist yet from the database (the first start, or a
  library folder that lost a file)."
  [dir]
  (let [f (ingredients-file dir)
        rdir (recipes-dir dir)]
    (when-not (.exists f)
      (log/info "Library: writing ingredients.txt from the database")
      (write-if-changed! f (ingredients-text)))
    (when-not (.isDirectory rdir)
      (log/info "Library: writing recipes/ from the database")
      (.mkdirs rdir)
      (doseq [{:keys [id]} (db/run-db-query ["select id from recipes order by id"])]
        (write-recipe! {:kind :recipe :action :add :id id})))))

;;; Git

(defn- commit!
  "Commit everything in the library, describing the pending changes. False on failure."
  []
  (let [pending (:pending @state)
        added (g "add" "-A")]
    (if-not (git/ok? added)
      (fail! "git add" added)
      (do (swap! state assoc :pending [] :first-at nil)
          (if-not (dirty?)
            true
            (let [msg (or (fmt/commit-message pending) "chore: update library files")
                  r (g "commit" "-q" "--no-gpg-sign" "-m" msg)]
              (if (git/ok? r)
                (do (log/info "Library: committed" (pr-str (first (str/split-lines msg))))
                    (swap! state assoc-in [:status :last-commit-at] (u/now-ms))
                    true)
                (do (swap! state update :pending #(into (vec pending) %))
                    (fail! "git commit" r)))))))))

(defn- pull!
  "Fetch the remote branch and rebase onto it, loading the files again when that brought
  changes. False on failure (a conflicting rebase is undone)."
  []
  (let [b (branch)
        fetched (g "fetch" "-q" remote-name)]
    (if-not (git/ok? fetched)
      (fail! "git fetch" fetched)
      (hooks/writing
       (let [ref (str remote-name "/" b)
             before (head)]
         (cond
           (not (git/ok? (g "rev-parse" "-q" "--verify" (str ref "^{commit}"))))
           true                         ; the remote doesn't have the branch yet
           (nil? before)
           (let [r (g "checkout" "-q" "-B" b ref)]
             (if (git/ok? r) (do (sync!) true) (fail! (str "git checkout " ref) r)))
           :else
           (let [r (g "rebase" "-q" ref)]
             (if (git/ok? r)
               (do (when (not= before (head)) (sync!)) true)
               (do (g "rebase" "--abort")
                   (fail! (str "Couldn't rebase onto " ref "; resolve it by hand in " (.getPath ^File (:dir @state))) r))))))))))

(defn- push!
  []
  (if (nil? (head))
    true
    (let [r (g "push" "-q" remote-name (str "HEAD:refs/heads/" (branch)))]
      (if (git/ok? r)
        (do (swap! state (fn [st] (-> st
                                      (assoc-in [:status :last-push-at] (u/now-ms))
                                      (update :status dissoc :error))))
            true)
        (fail! "git push" r)))))

(defn flush!
  "Commit the pending changes now, then pull and push. True when everything worked."
  []
  (locking flush-lock
    (let [{:keys [git? remote?]} @state]
      (if-not git?
        true
        (and (hooks/writing (commit!))
             (or (not remote?)
                 (and (pull!) (push!)))
             (do (swap! state update :status dissoc :error) true))))))

(defn commit-soon!
  "Run the commit (and push) now, in the background."
  []
  (when (:git? @state)
    (.submit ^java.util.concurrent.ExecutorService @executor ^Runnable run-flush!)))

(defn- ensure-repo!
  "Make DIR a git repo with the remote from the settings. Returns the identity args to
  commit with, or nil when git isn't available."
  [^File dir]
  (if-not (git/available?)
    (do (log/warn "Library: git isn't available; recipe and ingredient files are written but not committed")
        nil)
    (do
      (when-not (.exists (io/file dir ".git"))
        (log/info "Library: creating a git repo in" (.getPath dir))
        (git/run dir "init" "-q")
        (git/run dir "symbolic-ref" "HEAD" "refs/heads/main"))
      (let [url (some-> (:library-remote (s/get-settings)) str str/trim not-empty)
            current (git/out dir "remote" "get-url" remote-name)]
        (cond
          (or (nil? url) (= url current)) nil
          (str/starts-with? url "-") (log/warn "Library: ignoring the remote" url)
          current (git/run dir "remote" "set-url" remote-name url)
          :else (git/run dir "remote" "add" remote-name url)))
      (if (str/blank? (git/out dir "config" "user.email")) fallback-identity []))))

(defonce ^:private shutdown-hook
  (delay (.addShutdownHook (Runtime/getRuntime)
                           (Thread. ^Runnable (fn [] (when (seq (:pending @state)) (run-flush!)))
                                    "larder-library-shutdown"))))

(defn init!
  "Start the library: create the repo, pull, write missing files from the database, load
  the files into it and start writing changes back."
  []
  (let [dir (library-dir)]
    (.mkdirs dir)
    (let [identity (ensure-repo! dir)
          git? (some? identity)]
      (reset! state {:dir dir :git? git? :identity identity :files {} :pending [] :status {}
                     :remote? (and git? (some? (git/out dir "remote" "get-url" remote-name)))})
      (hooks/writing
       (when (and git? (head) (dirty?))
         (commit!))
       (when (:remote? @state)
         (pull!))
       (export-missing! dir)
       (sync!)
       (when git?
         (cond
           (and (nil? (head)) (dirty?))
           (do (g "add" "-A")
               (let [r (g "commit" "-q" "--no-gpg-sign" "-m" "init: export recipes and ingredients")]
                 (if (git/ok? r)
                   (swap! state assoc-in [:status :last-commit-at] (u/now-ms))
                   (fail! "git commit" r)))
               (when (:remote? @state) (commit-soon!)))
           (dirty?) (schedule!)
           (:remote? @state) (commit-soon!))))
      (hooks/set-listener! on-change)
      @shutdown-hook
      (log/info "Library: ready in" (.getPath dir)))))

(defn stop!
  "Stop writing the files (tests)."
  []
  (hooks/set-listener! nil)
  (when-let [^ScheduledFuture t (:task @state)] (.cancel t false))
  (reset! state nil))
