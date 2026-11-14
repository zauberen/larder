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

(ns com.larder.import.tandoor
  "Import a Tandoor export: a zip holding one zip per recipe (`1.zip`, `2.zip`, ...),
  each with `recipe.json` and optionally `image.<ext>`. A single recipe's zip (with
  `recipe.json` at the top) is accepted too.

  Ingredient lines are rebuilt as text (\"1½ cup sugar, sifted\") so they go through the
  same parser and matcher as typed ones. Everything read from the archive is size
  capped, so a crafted zip can't exhaust memory."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.larder.images :as images]
            [com.larder.import.core :as core]
            [com.larder.units :as units])
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream InputStream)
           (java.util.zip ZipEntry ZipFile ZipInputStream)))

(def max-recipes
  "Recipes read from one export at most."
  5000)

(def max-recipe-zip-bytes
  "Largest inner zip read (recipe.json plus image)."
  (* 20 1024 1024))

(def max-json-bytes
  "Largest recipe.json read."
  (* 2 1024 1024))

(defn- user-error
  [msg]
  (ex-info msg {:user-message msg}))

(defn- read-capped
  "All bytes of IN, or throws a user error naming WHAT when there are more than LIMIT."
  ^bytes [^InputStream in limit what]
  (let [out (ByteArrayOutputStream.)
        buf (byte-array 65536)]
    (loop [n 0]
      (let [r (.read in buf)]
        (if (neg? r)
          (.toByteArray out)
          (let [n (+ n r)]
            (when (> n limit)
              (throw (user-error (str what " is too large."))))
            (.write out buf 0 r)
            (recur n)))))))

;;; recipe.json -> the importer shape

(defn- clean
  "S with empty parentheses (left by Tandoor's parser) removed and whitespace collapsed."
  [s]
  (some-> s str (str/replace #"\(\s*\)" "") (str/replace #"\s+" " ") str/trim not-empty))

(defn ingredient-line
  "The text of one Tandoor ingredient, or nil for headers and empty ones. Without an
  amount the unit goes in parentheses, so the name still matches (\"salt (pinch)\");
  marked no amount, it is left out (Tandoor hides it too)."
  [{:keys [food unit amount note is_header no_amount]}]
  (let [food (clean (:name food))
        unit (when-not no_amount (clean (:name unit)))
        qty (when (and (not no_amount) (number? amount) (pos? amount)) (units/format-qty amount))
        note (clean note)]
    (when (and food (not is_header))
      (str (str/join " " (remove nil? [qty (when qty unit) food]))
           (when (and unit (not qty)) (str " (" unit ")"))
           (when note (str ", " note))))))

(defn- positive
  [n]
  (when (and (number? n) (pos? n)) (long n)))

(defn- steps-text
  "One line per instruction line; a named step's name comes first on its own line."
  [steps]
  (->> steps
       (mapcat (fn [{:keys [name instruction]}]
                 (cons (clean name) (str/split-lines (str instruction)))))
       (keep clean)
       (str/join "\n")))

(defn parse-recipe
  "The importer's :recipe map for a parsed recipe.json (keyword keys)."
  [r]
  (let [steps (sort-by #(or (:order %) 0) (:steps r))]
    {:title (clean (:name r))
     :description (some-> (:description r) str str/trim not-empty)
     :servings (positive (:servings r))
     :prep_minutes (positive (:working_time r))
     :cook_minutes (positive (:waiting_time r))
     :source_url (clean (:source_url r))
     :tags (keep (comp clean :name) (:keywords r))
     :lines (->> steps
                 (mapcat #(sort-by (fn [i] (or (:order i) 0)) (:ingredients %)))
                 (keep ingredient-line)
                 vec)
     :steps (steps-text steps)}))

;;; Archives

(defn- entry-base
  [^String name]
  (last (str/split name #"/")))

(defn- image-entry?
  [name]
  (boolean (re-matches #"(?i)image\.(jpe?g|png|gif|bmp|webp)" (entry-base name))))

(defn- read-recipe-entries
  "Imported recipe from a map of entry name -> bytes (recipe.json and the image)."
  [files]
  (let [json-bytes (some (fn [[n b]] (when (= "recipe.json" (entry-base n)) b)) files)
        image (some (fn [[n b]] (when (image-entry? n) b)) files)]
    (when-not json-bytes
      (throw (user-error "It has no recipe.json.")))
    (let [data (try (json/read-str (String. ^bytes json-bytes "UTF-8") :key-fn keyword)
                    (catch Exception _ (throw (user-error "Its recipe.json isn't valid JSON."))))]
      (when-not (map? data)
        (throw (user-error "Its recipe.json isn't a recipe.")))
      (let [recipe (parse-recipe data)]
        {:recipe recipe
         :image (when image {:bytes image})
         :warnings (cond-> []
                     (nil? (:title recipe)) (conj "It had no name.")
                     (> (count (:lines recipe)) 200) (conj "Only the first 200 ingredients were kept."))}))))

(defn- read-inner-zip
  "Imported recipe from the bytes of one recipe's zip."
  [^bytes zip-bytes]
  (with-open [zin (ZipInputStream. (ByteArrayInputStream. zip-bytes))]
    (loop [files {}]
      (if-let [^ZipEntry e (.getNextEntry zin)]
        (let [n (.getName e)]
          (cond
            (.isDirectory e) (recur files)
            (= "recipe.json" (entry-base n)) (recur (assoc files n (read-capped zin max-json-bytes "Its recipe.json")))
            (image-entry? n) (recur (assoc files n (read-capped zin images/max-bytes "Its image")))
            :else (recur files)))
        (read-recipe-entries files)))))

(defn- entry-order
  "Sort key: Tandoor numbers its zips, so 2.zip comes before 10.zip."
  [^ZipEntry e]
  (let [base (entry-base (.getName e))
        n (re-find #"^\d+" base)]
    [(if n (parse-long n) Long/MAX_VALUE) base]))

(defn read-export
  "Imported recipes from the Tandoor export in FILE. A recipe that can't be read becomes
  {:failed \"<entry>: <why>\"}."
  [^java.io.File file]
  (let [zf (try (ZipFile. file)
                (catch Exception _ (throw (user-error "That file isn't a zip file."))))]
    (with-open [zf zf]
      (let [entries (->> (enumeration-seq (.entries zf)) (remove #(.isDirectory ^ZipEntry %)))
            by-name (fn [pred] (filter #(pred (.getName ^ZipEntry %)) entries))
            inner (sort-by entry-order (by-name #(str/ends-with? (str/lower-case %) ".zip")))
            top-json (by-name #(= "recipe.json" (entry-base %)))]
        (cond
          (seq inner)
          (do (when (> (count inner) max-recipes)
                (throw (user-error (str "The export has more than " max-recipes " recipes."))))
              (doall
               (for [^ZipEntry e inner]
                 (try
                   (with-open [in (.getInputStream zf e)]
                     (read-inner-zip (read-capped in max-recipe-zip-bytes "It")))
                   (catch clojure.lang.ExceptionInfo ex
                     {:failed (str (.getName e) ": " (or (:user-message (ex-data ex)) "It couldn't be read."))})
                   (catch Exception ex
                     (log/info "Reading" (.getName e) "from a Tandoor export failed:" (.getMessage ex))
                     {:failed (str (.getName e) ": It isn't a valid zip file.")})))))

          (seq top-json)
          [(read-recipe-entries
            (into {} (for [^ZipEntry e entries
                           :let [n (.getName e)]
                           :when (or (= "recipe.json" (entry-base n)) (image-entry? n))]
                       [n (with-open [in (.getInputStream zf e)]
                            (if (image-entry? n)
                              (read-capped in images/max-bytes "The image")
                              (read-capped in max-json-bytes "recipe.json")))])))]

          :else
          (throw (user-error "That zip doesn't look like a Tandoor export (it has no recipes).")))))))

(defmethod core/import-source :tandoor
  [{:keys [file]}]
  (read-export file))
