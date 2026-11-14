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

(ns com.larder.recipes
  "Recipes: validation of the editor form, storage, search. A recipe is a map with the
  recipes columns plus :tags (strings) and :ingredients (recipe_ingredients rows in
  order)."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [com.larder.database.db :as db]
            [com.larder.ingredients :as ingredients]
            [com.larder.util :as u])
  (:import (java.net URI)))

(def statuses #{"draft" "published"})

(def limits
  {:title 200 :description 5000 :steps 50000 :tag 40 :tags 20 :lines 200 :line 500
   :servings 1000 :minutes 10000 :source-url 2000})

;;; Form parsing

(defn split-lines
  "Non-blank, trimmed lines of S."
  [s]
  (->> (str/split-lines (or s ""))
       (map str/trim)
       (remove str/blank?)
       vec))

(defn parse-tags
  "Comma separated tags, trimmed, without duplicates (ignoring case), in typed order."
  [s]
  (->> (str/split (or s "") #",")
       (map #(str/replace (str/trim %) #"\s+" " "))
       (remove str/blank?)
       (reduce (fn [acc t] (if (some #(= (str/lower-case t) (str/lower-case %)) acc) acc (conj acc t))) [])))

(defn valid-url?
  "True for an absolute http(s) URL with a host."
  [s]
  (try
    (let [uri (URI. s)]
      (boolean (and (#{"http" "https"} (some-> (.getScheme uri) str/lower-case))
                    (not (str/blank? (.getHost uri))))))
    (catch Exception _ false)))

(defn- number-field
  "[value error] for the optional whole number in S between LO and HI."
  [s label lo hi]
  (let [s (str/trim (or s ""))]
    (cond
      (str/blank? s) [nil nil]
      :else (let [n (u/parse-int s)]
              (if (and n (<= lo n hi))
                [n nil]
                [nil (str label " must be a whole number from " lo " to " hi ".")])))))

(defn parse-form
  "Read the recipe editor's PARAMS. Returns [recipe errors]; the recipe has the
  editable fields, :tags and :lines (ingredient lines as typed)."
  [params]
  (let [text #(str/trim (str (get params % "")))
        title (text :title)
        description (text :description)
        steps (str/join "\n" (split-lines (get params :steps)))
        source-url (text :source_url)
        tags (parse-tags (get params :tags))
        lines (split-lines (get params :ingredients))
        [servings servings-error] (number-field (get params :servings) "Servings" 1 (:servings limits))
        [prep prep-error] (number-field (get params :prep_minutes) "Prep time" 0 (:minutes limits))
        [cook cook-error] (number-field (get params :cook_minutes) "Cook time" 0 (:minutes limits))
        [rating rating-error] (number-field (get params :rating) "The rating" 1 5)
        errors (cond-> []
                 (str/blank? title) (conj "Give the recipe a title.")
                 (> (count title) (:title limits)) (conj (str "Titles can be up to " (:title limits) " characters."))
                 (> (count description) (:description limits)) (conj (str "The description can be up to " (:description limits) " characters."))
                 (> (count steps) (:steps limits)) (conj "The steps are too long.")
                 (and (seq source-url) (not (valid-url? source-url))) (conj "The source should be a web address starting with http:// or https://.")
                 (> (count source-url) (:source-url limits)) (conj "The source address is too long.")
                 (> (count tags) (:tags limits)) (conj (str "A recipe can have up to " (:tags limits) " tags."))
                 (some #(> (count %) (:tag limits)) tags) (conj (str "Tags can be up to " (:tag limits) " characters."))
                 (> (count lines) (:lines limits)) (conj (str "A recipe can have up to " (:lines limits) " ingredient lines."))
                 (some #(> (count %) (:line limits)) lines) (conj (str "Ingredient lines can be up to " (:line limits) " characters."))
                 servings-error (conj servings-error)
                 prep-error (conj prep-error)
                 cook-error (conj cook-error)
                 rating-error (conj rating-error))]
    [{:title title
      :description (not-empty description)
      :steps (not-empty steps)
      :source_url (not-empty source-url)
      :servings servings
      :prep_minutes prep
      :cook_minutes cook
      :rating rating
      :tags tags
      :lines lines}
     errors]))

;;; Reading

(defn- tags-by-recipe
  [ids]
  (if (empty? ids)
    {}
    (->> (db/run-db-query (into [(str "select recipe_id, tag from recipe_tags where recipe_id in ("
                                      (str/join "," (repeat (count ids) "?")) ") order by rowid")]
                                ids))
         (group-by :recipe_id)
         (into {} (map (fn [[k rows]] [k (mapv :tag rows)]))))))

(defn by-id
  "The recipe with ID with its :tags and :ingredients, or nil. Each ingredient row also
  has :ingredient_name (nil when the line is unmatched)."
  [id]
  (when-let [recipe (first (db/run-db-query ["select * from recipes where id = ?" id]))]
    (assoc recipe
           :tags (get (tags-by-recipe [id]) id [])
           :ingredients (db/run-db-query [(str "select ri.*, i.name as ingredient_name from recipe_ingredients ri"
                                               " left join ingredients i on i.id = ri.ingredient_id"
                                               " where ri.recipe_id = ? order by ri.position") id]))))

(defn unmatched-lines
  "Recipe ID's lines without an ingredient, each with its parsed :name."
  [id]
  (for [row (db/run-db-query ["select * from recipe_ingredients where recipe_id = ? and ingredient_id is null order by position" id])]
    (assoc row :name (:name (ingredients/parse-line (:original_text row))))))

(defn all-unmatched-lines
  "Every recipe's lines without an ingredient, each with its parsed :name and the
  recipe's :recipe_title, ordered by recipe title then position."
  []
  (for [row (db/run-db-query [(str "select ri.*, r.title as recipe_title from recipe_ingredients ri"
                                   " join recipes r on r.id = ri.recipe_id"
                                   " where ri.ingredient_id is null"
                                   " order by r.title collate nocase, r.id, ri.position")])]
    (assoc row :name (:name (ingredients/parse-line (:original_text row))))))

(defn unmatched-count
  "How many recipe lines have no ingredient."
  []
  (:n (first (db/run-db-query ["select count(*) as n from recipe_ingredients where ingredient_id is null"]))))

(defn published-by-id
  "The published recipe with ID, or nil (drafts don't exist for visitors)."
  [id]
  (let [r (by-id id)]
    (when (= "published" (:status r)) r)))

(defn search
  "Recipes (without ingredients) matching Q in the title, description or tags, newest
  first. STATUS is \"draft\", \"published\" or nil for both."
  [{:keys [q status]}]
  (let [q (some-> q str/trim not-empty str/lower-case)
        like (when q (str "%" (str/replace q #"[\\%_]" "\\\\$0") "%"))
        where (cond-> []
                (statuses status) (conj ["status = ?" status])
                q (conj [(str "(lower(title) like ? escape '\\' or lower(coalesce(description, '')) like ? escape '\\'"
                              " or exists (select 1 from recipe_tags t where t.recipe_id = recipes.id"
                              " and lower(t.tag) like ? escape '\\'))")
                         like like like]))
        sql (str "select * from recipes"
                 (when (seq where) (str " where " (str/join " and " (map first where))))
                 " order by lower(title), id")
        rows (db/run-db-query (into [sql] (mapcat rest where)))
        tags (tags-by-recipe (map :id rows))]
    (mapv #(assoc % :tags (get tags (:id %) [])) rows)))

;;; Writing

(defn title-keys
  "Every recipe's title, lower case and trimmed (to spot repeated imports)."
  []
  (into #{} (map #(str/lower-case (str/trim (:title %)))) (db/run-db-query ["select title from recipes"])))

(defn- write-children!
  "Replace the tags and ingredient lines of recipe ID."
  [tx id {:keys [tags lines]}]
  (jdbc/execute! tx ["delete from recipe_tags where recipe_id = ?" id])
  (jdbc/execute! tx ["delete from recipe_ingredients where recipe_id = ?" id])
  (doseq [tag tags]
    (jdbc/execute! tx ["insert into recipe_tags (recipe_id, tag) values (?, ?)" id tag]))
  (let [index (ingredients/name-index)]
    (doseq [[i line] (map-indexed vector lines)
            :let [{:keys [ingredient_id quantity unit note original_text]} (ingredients/resolve-line line index)]]
      (jdbc/execute! tx [(str "insert into recipe_ingredients (recipe_id, position, ingredient_id, quantity, unit, note, original_text)"
                              " values (?, ?, ?, ?, ?, ?, ?)")
                         id i ingredient_id quantity unit note original_text]))))

(def ^:private columns [:title :description :servings :prep_minutes :cook_minutes :steps :source_url :rating])

(defn create!
  "Store RECIPE (as from `parse-form`) with STATUS for USER-ID. Returns the new id."
  [recipe status user-id]
  {:pre [(statuses status)]}
  (jdbc/with-db-transaction [tx (db/get-db-spec)]
    (let [t (u/now-ms)
          cols (concat columns [:status :image_path :created_by :created_at :modified_at])]
      (jdbc/execute! tx (into [(str "insert into recipes (" (str/join ", " (map name cols)) ") values ("
                                    (str/join ", " (repeat (count cols) "?")) ")")]
                              (concat (map recipe columns) [status (:image_path recipe) user-id t t])))
      (let [id (:id (first (jdbc/query tx ["select last_insert_rowid() as id"])))]
        (write-children! tx id recipe)
        id))))

(defn update!
  "Replace the editable fields, tags and lines of recipe ID with RECIPE and set STATUS."
  [id recipe status]
  {:pre [(statuses status)]}
  (jdbc/with-db-transaction [tx (db/get-db-spec)]
    (jdbc/execute! tx (into [(str "update recipes set " (str/join ", " (map #(str (name %) " = ?") columns))
                                  ", status = ?, modified_at = ? where id = ?")]
                            (concat (map recipe columns) [status (u/now-ms) id])))
    (write-children! tx id recipe)))

(defn set-line-ingredient!
  "Match recipe line LINE-ID to ingredient ING-ID."
  [line-id ing-id]
  (db/exe-db-stmt ["update recipe_ingredients set ingredient_id = ? where id = ?" ing-id line-id]))

(defn set-image!
  "Set recipe ID's image file name (nil removes it). Returns the previous name."
  [id image-name]
  (let [old (:image_path (first (db/run-db-query ["select image_path from recipes where id = ?" id])))]
    (db/exe-db-stmt ["update recipes set image_path = ?, modified_at = ? where id = ?" image-name (u/now-ms) id])
    old))

(defn delete!
  "Delete recipe ID with its tags and lines. Returns its image file name, if any."
  [id]
  (let [old (:image_path (first (db/run-db-query ["select image_path from recipes where id = ?" id])))]
    (db/exe-db-stmt ["delete from recipes where id = ?" id])
    old))

(defn by-image
  "The recipe whose image is NAME, or nil."
  [name]
  (first (db/run-db-query ["select id, status from recipes where image_path = ?" name])))

;;; Display helpers

(defn format-minutes
  [m]
  (when m
    (let [h (quot m 60) mm (mod m 60)]
      (cond (zero? h) (str mm " min")
            (zero? mm) (str h " h")
            :else (str h " h " mm " min")))))
