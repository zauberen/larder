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

(ns com.larder.ingredients
  "The shared ingredient records every recipe line, pantry row and grocery item points
  at; the recipe line parser; and the matcher from names to ingredients."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [com.larder.database.db :as db]
            [com.larder.units :as units]
            [com.larder.util :as u]))

;;; Line parser

(def ^:private unicode-fractions
  {"½" 0.5 "⅓" (/ 1.0 3) "⅔" (/ 2.0 3) "¼" 0.25 "¾" 0.75 "⅕" 0.2 "⅖" 0.4 "⅗" 0.6 "⅘" 0.8
   "⅙" (/ 1.0 6) "⅚" (/ 5.0 6) "⅛" 0.125 "⅜" 0.375 "⅝" 0.625 "⅞" 0.875})

(def ^:private number-re
  "One amount: 1 1/2, 1½, 1/2, ½, 1.5 or 2."
  (str "(?:\\d+\\s+\\d+\\s*/\\s*\\d+|\\d+\\s*[" (str/join (keys unicode-fractions)) "]|\\d+\\s*/\\s*\\d+|["
       (str/join (keys unicode-fractions)) "]|\\d+(?:\\.\\d+)?|\\.\\d+)"))

(def ^:private quantity-re
  "An amount or a range (2-3, 2 to 3, 2 or 3) at the start of a line."
  (re-pattern (str "^(" number-re ")(?:\\s*(?:-|–|—|to|or)\\s*(" number-re "))?")))

(defn parse-number
  "The value of one amount matched by `number-re`."
  [s]
  (let [s (str/trim s)]
    (or (when-let [[_ w n d] (re-matches #"(\d+)\s+(\d+)\s*/\s*(\d+)" s)]
          (when-not (= "0" d) (+ (parse-long w) (/ (double (parse-long n)) (parse-long d)))))
        (when-let [[_ w f] (re-matches #"(\d+)\s*(\D)" s)]
          (when-let [v (unicode-fractions f)] (+ (parse-long w) v)))
        (when-let [[_ n d] (re-matches #"(\d+)\s*/\s*(\d+)" s)]
          (when-not (= "0" d) (/ (double (parse-long n)) (parse-long d))))
        (unicode-fractions s)
        (parse-double s))))

(def ^:private unit-re
  (re-pattern (str "(?i)^(" units/alias-pattern ")(?![\\p{L}\\d])\\.?\\s*(?:of\\s+)?")))

(defn- split-note
  "[name note] from the text after the unit: the part after the first comma and any
  parenthesised text are notes, as is a trailing \"to taste\"."
  [s]
  (let [parens (map second (re-seq #"\(([^)]*)\)" s))
        s (str/replace s #"\([^)]*\)" " ")
        [name & after] (str/split s #"," 2)
        [name taste] (if-let [[_ n] (re-matches #"(?i)(.*?)\s+(to taste)\s*" name)] [n "to taste"] [name nil])
        clean #(-> (or % "") (str/replace #"\s+" " ") str/trim)
        note (->> (concat parens [taste] after) (map clean) (remove str/blank?) (str/join ", "))]
    [(clean name) (not-empty note)]))

(defn parse-line
  "Split a recipe line into {:quantity :unit :name :note :original_text}. A range uses
  its larger amount, so shopping and pantry checks don't come up short. A quantity
  without a unit is counted (\"each\"). Unparseable parts stay in the name."
  [line]
  (let [original (str/trim (or line ""))
        text (-> original (str/replace #"^[-*•·]\s*" "") str/trim)
        [qmatch q1 q2] (re-find quantity-re text)
        quantity (when qmatch
                   (let [a (parse-number q1) b (some-> q2 parse-number)]
                     (if b (max a b) a)))
        rest1 (str/trim (if qmatch (subs text (count qmatch)) text))
        ;; "1 (14 oz) can tomatoes": the size in parentheses is a note.
        [_ size-note] (when quantity (re-find #"^\(([^)]*)\)\s*" rest1))
        rest1 (if size-note (str/trim (str/replace-first rest1 #"^\([^)]*\)\s*" "")) rest1)
        [umatch uword] (when quantity (re-find unit-re rest1))
        unit (some-> uword units/normalize)
        rest2 (if umatch (subs rest1 (count umatch)) rest1)
        [name note] (split-note rest2)]
    {:quantity quantity
     :unit (or unit (when quantity "each"))
     :name name
     :note (->> [size-note note] (remove str/blank?) (str/join ", ") not-empty)
     :original_text original}))

;;; Matching

(defn- singular
  [w]
  (cond
    (<= (count w) 3) w
    (str/ends-with? w "ies") (str (subs w 0 (- (count w) 3)) "y")
    (re-find #"(?:ch|sh|ss|x|z|o)es$" w) (subs w 0 (- (count w) 2))
    (re-find #"(?:ss|us|is)$" w) w
    (str/ends-with? w "s") (subs w 0 (dec (count w)))
    :else w))

(defn match-key
  "The form names are compared in: lower case, punctuation removed, each word singular."
  [s]
  (->> (str/split (str/lower-case (or s "")) #"[^\p{L}\p{N}]+")
       (remove str/blank?)
       (map singular)
       (str/join " ")))

(defn name-index
  "match-key -> ingredient id, over every ingredient name and alias."
  []
  (merge (into {} (for [{:keys [ingredient_id alias]} (db/run-db-query ["select ingredient_id, alias from ingredient_aliases"])]
                    [(match-key alias) ingredient_id]))
         (into {} (for [{:keys [id name]} (db/run-db-query ["select id, name from ingredients"])]
                    [(match-key name) id]))))

(defn match
  "The id of the ingredient called NAME (by name or alias, ignoring case and plurals) in
  INDEX (default: a fresh `name-index`), or nil."
  ([name] (match name (name-index)))
  ([name index] (let [k (match-key name)] (when-not (str/blank? k) (get index k)))))

(defn resolve-line
  "`parse-line` plus :ingredient_id from the matcher."
  ([line] (resolve-line line (name-index)))
  ([line index]
   (let [parsed (parse-line line)]
     (assoc parsed :ingredient_id (match (:name parsed) index)))))

;;; Records

(def ^:private columns "id, name, dimension, density, default_quantity, default_unit, always_have")

(defn by-id
  [id]
  (first (db/run-db-query [(str "select " columns " from ingredients where id = ?") id])))

(defn all
  []
  (db/run-db-query [(str "select " columns " from ingredients order by lower(name)")]))

(defn aliases
  [id]
  (mapv :alias (db/run-db-query ["select alias from ingredient_aliases where ingredient_id = ? order by lower(alias)" id])))

(defn guess-dimension
  "The dimension for a new ingredient first seen with UNIT."
  [unit]
  (or (units/dimension unit) "count"))

(defn validate
  "Errors for an ingredient called NAME (EXCEPT-ID: the ingredient being edited)."
  [{:keys [name dimension density]} & [except-id]]
  (let [existing (match name)]
    (cond-> []
      (str/blank? name) (conj "Give the ingredient a name.")
      (> (count (or name "")) 100) (conj "Ingredient names can be up to 100 characters.")
      (and existing (not= existing except-id)) (conj (str "\"" name "\" is already an ingredient or one of its other names."))
      (not (some #{dimension} units/dimensions)) (conj "Choose mass, volume or count.")
      (and density (not (pos? density))) (conj "The density must be above zero."))))

(defn create!
  "Create an ingredient. Returns its id."
  [{:keys [name dimension density default_quantity default_unit always_have]}]
  (jdbc/with-db-transaction [tx (db/get-db-spec)]
    (let [t (u/now-ms)]
      (jdbc/execute! tx ["insert into ingredients (name, dimension, density, default_quantity, default_unit, always_have, created_at, modified_at) values (?, ?, ?, ?, ?, ?, ?, ?)"
                         (str/trim name) dimension density default_quantity default_unit (if always_have 1 0) t t])
      (:id (first (jdbc/query tx ["select last_insert_rowid() as id"]))))))

(defn add-alias!
  "Make ALIAS another name of ingredient ID, unless it already matches something."
  [id alias]
  (let [alias (str/trim (or alias ""))]
    (when (and (seq alias) (nil? (match alias)))
      (db/exe-db-stmt ["insert or ignore into ingredient_aliases (ingredient_id, alias) values (?, ?)" id alias]))))

;;; Recipe lines

(defn rematch-unmatched!
  "Match every unmatched recipe line against the current names and aliases (after an
  ingredient or alias is added). Returns the number of lines matched."
  []
  (let [index (name-index)
        rows (db/run-db-query ["select id, original_text from recipe_ingredients where ingredient_id is null"])]
    (reduce (fn [n {:keys [id original_text]}]
              (if-let [ing (match (:name (parse-line original_text)) index)]
                (do (db/exe-db-stmt ["update recipe_ingredients set ingredient_id = ? where id = ?" ing id])
                    (inc n))
                n))
            0 rows)))

;;; Editing

(defn find-or-create!
  "The id of the ingredient called NAME, creating it (dimension guessed from UNIT) when
  no name or alias matches. Returns [id created?], or nil for a blank name."
  [name unit]
  (let [name (str/trim (or name ""))]
    (when (seq name)
      (if-let [id (match name)]
        [id false]
        [(create! {:name name :dimension (guess-dimension unit)}) true]))))

(defn update!
  "Save the edit form's fields for ingredient ID."
  [id {:keys [name dimension density default_quantity default_unit always_have]}]
  (db/exe-db-stmt [(str "update ingredients set name = ?, dimension = ?, density = ?, default_quantity = ?,"
                        " default_unit = ?, always_have = ?, modified_at = ? where id = ?")
                   (str/trim name) dimension density default_quantity (when default_quantity default_unit)
                   (if always_have 1 0) (u/now-ms) id]))

(defn alias-errors
  "Errors for giving ingredient ID the ALIASES."
  [id aliases]
  (vec (for [a aliases
             :let [other (match a)]
             :when (and other (not= other id))]
         (str "\"" a "\" already names another ingredient."))))

(defn set-aliases!
  "Replace ingredient ID's aliases."
  [id aliases]
  (jdbc/with-db-transaction [tx (db/get-db-spec)]
    (jdbc/execute! tx ["delete from ingredient_aliases where ingredient_id = ?" id])
    (doseq [a (distinct (map str/trim aliases)) :when (seq a)]
      (jdbc/execute! tx ["insert or ignore into ingredient_aliases (ingredient_id, alias) values (?, ?)" id a]))))
