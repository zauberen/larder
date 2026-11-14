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

(ns com.larder.library.format
  "The library's text files: one per recipe (a header of `key: value` lines, then
  [Description], [Ingredients] and [Steps] sections) and ingredients.txt (one block per
  ingredient: its name, then indented `key: value` lines). Rendering is deterministic, so
  an unchanged record writes the same bytes and git sees no change."
  (:require [clojure.string :as str]
            [com.larder.recipes :as recipes]
            [com.larder.units :as units]))

;;; Shared

(defn- one-line
  "S on one line, trimmed."
  [s]
  (some-> s str (str/replace #"[\r\n]+" " ") str/trim not-empty))

(defn- iso
  [ms]
  (when ms (str (java.time.Instant/ofEpochMilli ms))))

(defn- parse-iso
  [s]
  (try (.toEpochMilli (java.time.Instant/parse s)) (catch Exception _ nil)))

(defn num-str
  "A number without a trailing .0."
  [n]
  (when n
    (let [d (double n)]
      (if (and (== d (Math/rint d)) (< (Math/abs d) 1e15))
        (str (long d))
        (str d)))))

(defn- header-line
  [line]
  (when-let [[_ k v] (re-matches #"([A-Za-z][A-Za-z-]*)\s*:\s*(.*)" line)]
    [(str/lower-case k) (str/trim v)]))

(defn- normalize-newlines
  [s]
  (str/replace (or s "") #"\r\n?" "\n"))

;;; Recipes

(def recipe-header-keys
  ["title" "id" "status" "servings" "prep" "cook" "rating" "tags" "source" "image" "created" "modified"])

(def ^:private image-name-re #"[0-9a-f]{32}\.jpg")

(def ^:private section-re #"(?i)\[(description|ingredients|steps)\]")

;; A body line that would read as a section marker ([Steps], or an already escaped
;; \[Steps]) is written with one more backslash in front; reading removes one.
(def ^:private escaped-re #"(?i)\\*\[(?:description|ingredients|steps)\]")

(defn escape-line
  [line]
  (if (re-matches escaped-re (str/trim line)) (str/replace-first line #"^(\s*)" "$1\\\\") line))

(defn unescape-line
  [line]
  (if (re-matches #"(?i)\\+\[(?:description|ingredients|steps)\]" (str/trim line))
    (str/replace-first line "\\" "")
    line))

(defn render-recipe
  "The file text for RECIPE (as from `recipes/by-id`)."
  [r]
  (let [header [["title" (one-line (:title r))]
                ["id" (:id r)]
                ["status" (:status r)]
                ["servings" (:servings r)]
                ["prep" (:prep_minutes r)]
                ["cook" (:cook_minutes r)]
                ["rating" (:rating r)]
                ["tags" (not-empty (str/join ", " (keep one-line (:tags r))))]
                ["source" (one-line (:source_url r))]
                ["image" (:image_path r)]
                ["created" (iso (:created_at r))]
                ["modified" (iso (:modified_at r))]]
        section (fn [title body]
                  (when-not (str/blank? body)
                    (str "\n[" title "]\n"
                         (str/join "\n" (map escape-line (str/split-lines (str/trim body))))
                         "\n")))]
    (str (str/join (for [[k v] header :when (some? v)] (str k ": " v "\n")))
         (section "Description" (normalize-newlines (:description r)))
         (section "Ingredients" (str/join "\n" (keep (comp one-line :original_text) (:ingredients r))))
         (section "Steps" (normalize-newlines (:steps r))))))

(defn header-id
  "The id in recipe file TEXT's header, or nil (to keep a recipe whose file has errors)."
  [text]
  (some (fn [line]
          (when-let [[k v] (header-line line)]
            (when (= "id" k) (parse-long v))))
        (take-while (complement str/blank?) (str/split-lines (normalize-newlines text)))))

(defn parse-recipe
  "Read a recipe file. Returns [recipe errors]: the recipe is shaped like
  `recipes/parse-form`'s, plus :id (nil when the file has none), :status, :image_path,
  :created_at and :modified_at."
  [text]
  (let [lines (str/split-lines (normalize-newlines text))
        [head body] (split-with #(not (or (str/blank? %) (re-matches section-re (str/trim %)))) lines)
        fields (map-indexed (fn [i l] [i l (header-line l)]) head)
        bad-lines (for [[i l parsed] fields :when (nil? parsed)] (str "Line " (inc i) " isn't \"key: value\": " l))
        header (into {} (keep #(nth % 2)) fields)
        unknown (remove (set recipe-header-keys) (keys header))
        sections (loop [[l & more :as ls] body current nil acc {} stray []]
                   (if (empty? ls)
                     [acc stray]
                     (if-let [[_ s] (re-matches section-re (str/trim l))]
                       (recur more (str/lower-case s) (update acc (str/lower-case s) #(or % [])) stray)
                       (cond
                         current (recur more current (update acc current conj (unescape-line l)) stray)
                         (str/blank? l) (recur more current acc stray)
                         :else (recur more current acc (conj stray l))))))
        [sections stray] sections
        text-of #(str/trim (str/join "\n" (get sections %)))
        [recipe form-errors] (recipes/parse-form
                              {:title (get header "title")
                               :description (text-of "description")
                               :ingredients (text-of "ingredients")
                               :steps (text-of "steps")
                               :tags (get header "tags")
                               :source_url (get header "source")
                               :servings (get header "servings")
                               :prep_minutes (get header "prep")
                               :cook_minutes (get header "cook")
                               :rating (get header "rating")})
        id (some-> (get header "id") parse-long)
        status (get header "status" "draft")
        image (not-empty (get header "image"))
        [created modified] (map #(some-> (get header %) not-empty parse-iso) ["created" "modified"])
        errors (cond-> (vec (concat bad-lines form-errors))
                 (seq unknown) (conj (str "Unknown field" (when (next unknown) "s") ": " (str/join ", " unknown) "."))
                 (seq stray) (conj (str "Text outside [Description], [Ingredients] or [Steps]: " (first stray)))
                 (and (get header "id") (not (and id (pos? id)))) (conj "The id must be a positive whole number.")
                 (not (recipes/statuses status)) (conj "The status must be draft or published.")
                 (and image (not (re-matches image-name-re image))) (conj "The image must name a stored image.")
                 (and (get header "created") (not created)) (conj "created must be a time like 2026-01-31T12:00:00Z.")
                 (and (get header "modified") (not modified)) (conj "modified must be a time like 2026-01-31T12:00:00Z."))]
    [(assoc recipe :id id :status status :image_path image :created_at created :modified_at modified)
     errors]))

(defn slug
  "A file name base for TITLE."
  [title]
  (let [s (-> (str/lower-case (or title ""))
              (str/replace #"[^\p{L}\p{N}]+" "-")
              (str/replace #"^-+|-+$" ""))
        s (if (> (count s) 60) (str/replace (subs s 0 60) #"-+$" "") s)]
    (if (str/blank? s) "recipe" s)))

;;; Ingredients

(def ^:private ingredients-preamble
  (str "# Larder ingredients. Each block starts with the ingredient's name; the indented\n"
       "# lines under it are its details (id, dimension, density in g/ml, buy: the usual\n"
       "# purchase size, always-have, and one alias line per other name).\n"))

(defn render-ingredients
  "ingredients.txt for INGREDIENTS (records with :aliases), sorted by name."
  [ingredients]
  (str ingredients-preamble
       (str/join
        (for [i (sort-by (juxt (comp str/lower-case :name) :id) ingredients)]
          (str "\n" (one-line (:name i)) "\n"
               (str/join
                (for [[k v] [["id" (:id i)]
                             ["dimension" (:dimension i)]
                             ["density" (num-str (:density i))]
                             ["buy" (when (:default_quantity i)
                                      (str (num-str (:default_quantity i)) " " (:default_unit i)))]
                             ["always-have" (when (= 1 (:always_have i)) "yes")]]
                      :when (some? v)]
                  (str "  " k ": " v "\n")))
               (str/join (for [a (sort-by str/lower-case (distinct (keep one-line (:aliases i))))]
                           (str "  alias: " a "\n"))))))))

(defn- parse-ingredient-block
  "[ingredient errors] for one block: NAME and its [line-number line] details."
  [name details]
  (let [where (str "\"" name "\": ")
        parsed (for [[n l] details] [n l (header-line (str/trim l))])
        bad (for [[n l p] parsed :when (nil? p)] (str "Line " n " isn't \"key: value\": " (str/trim l)))
        pairs (keep #(nth % 2) parsed)
        one (fn [k] (some (fn [[pk v]] (when (= k pk) v)) pairs))
        unknown (remove #{"id" "dimension" "density" "buy" "always-have" "alias"} (map first pairs))
        id-s (one "id")
        id (some-> id-s parse-long)
        density-s (one "density")
        density (some-> density-s parse-double)
        [_ qty-s unit-s] (some->> (one "buy") (re-matches #"(\S+)\s+(.+)"))
        qty (some-> qty-s parse-double)
        unit (some-> unit-s units/normalize)
        dimension (or (one "dimension") (units/dimension unit) "count")
        always (some-> (one "always-have") str/lower-case)
        errors (cond-> (vec bad)
                 (> (count name) 100) (conj (str where "names can be up to 100 characters."))
                 (seq unknown) (conj (str where "unknown field " (first unknown) "."))
                 (and id-s (not (and id (pos? id)))) (conj (str where "the id must be a positive whole number."))
                 (not (some #{dimension} units/dimensions)) (conj (str where "the dimension must be mass, volume or count."))
                 (and density-s (not (and density (pos? density)))) (conj (str where "the density must be a number above zero."))
                 (and (one "buy") (not (and qty (pos? qty) unit))) (conj (str where "buy must be an amount and a unit, like 1 kg."))
                 (and always (not (#{"yes" "no" "true" "false"} always))) (conj (str where "always-have must be yes or no.")))]
    [{:id id
      :name name
      :dimension dimension
      :density density
      :default_quantity (when unit qty)
      :default_unit (when qty unit)
      :always_have (if (#{"yes" "true"} always) 1 0)
      :aliases (vec (distinct (keep (fn [[k v]] (when (and (= "alias" k) (seq v)) v)) pairs)))}
     errors]))

(defn parse-ingredients
  "Read ingredients.txt. Returns [ingredients errors]; ingredients without an id are new."
  [text]
  (let [lines (map-indexed (fn [i l] [(inc i) l]) (str/split-lines (normalize-newlines text)))
        content (remove (fn [[_ l]] (or (str/blank? l) (str/starts-with? (str/triml l) "#"))) lines)
        [blocks stray] (reduce (fn [[blocks stray] [n l :as line]]
                                 (cond
                                   (not (re-find #"^\s" l)) [(conj blocks [(str/trim l) []]) stray]
                                   (empty? blocks) [blocks (conj stray n)]
                                   :else [(update-in blocks [(dec (count blocks)) 1] conj line) stray]))
                               [[] []] content)
        results (map (fn [[name details]] (parse-ingredient-block name details)) blocks)
        ingredients (mapv first results)
        dupes (fn [xs] (->> xs frequencies (keep (fn [[k n]] (when (> n 1) k)))))
        dup-ids (dupes (keep :id ingredients))
        ;; Names and aliases share one namespace across ingredients (case-insensitive).
        dup-names (dupes (mapcat (fn [i] (distinct (map str/lower-case (cons (:name i) (:aliases i))))) ingredients))
        errors (cond-> (vec (mapcat second results))
                 (seq stray) (conj (str "Line " (first stray) " is indented but comes before any ingredient name."))
                 (seq dup-ids) (conj (str "More than one ingredient has id " (str/join ", " dup-ids) "."))
                 (seq dup-names) (conj (str "More than one ingredient is called " (str/join ", " (map #(str "\"" % "\"") dup-names)) ".")))]
    [ingredients errors]))

;;; Commit messages

(defn collapse
  "CHANGES (in order) folded to one per record: an add then edits stays an add, an add
  then a delete disappears, anything then a delete is a delete. Keeps first-seen order
  and the latest name."
  [changes]
  (let [folded (reduce (fn [m {:keys [kind id action] :as c}]
                         (let [k [kind id]
                               prev (get-in m [:by k :action])
                               action (cond
                                        (nil? prev) action
                                        (and (= :add prev) (= :del action)) ::gone
                                        (= :add prev) :add
                                        (= :del action) :del
                                        (= :del prev) :edit
                                        :else action)]
                           (-> m
                               (update :order #(if (some #{k} %) % (conj % k)))
                               (assoc-in [:by k] (assoc c :action action)))))
                       {:order [] :by {}} changes)]
    (->> (:order folded)
         (map (:by folded))
         (remove #(= ::gone (:action %)))
         vec)))

(def ^:private action-word {:add "add" :edit "edit" :del "del"})

(defn- change-line
  [{:keys [action kind name]}]
  (str (action-word action) "(" (clojure.core/name kind) "): " (one-line name)))

(defn commit-message
  "The commit message for CHANGES: `add(recipe): Pancakes` for one change; for several,
  a subject grouping them by action and kind (shortened to counts when it gets long) and
  a body with one line per change. Nil when there is nothing to describe."
  [changes]
  (let [changes (collapse changes)
        groups (->> changes
                    (group-by (juxt :action :kind))
                    (sort-by (fn [[k _]] (.indexOf ^java.util.List (vec (distinct (map (juxt :action :kind) changes))) k))))
        prefix (fn [[action kind]] (str (action-word action) "(" (name kind) "): "))
        full (str/join "; " (for [[k cs] groups] (str (prefix k) (str/join ", " (map (comp one-line :name) cs)))))
        short (str/join "; " (for [[[_ kind :as k] cs] groups]
                               (str (prefix k)
                                    (if (= 1 (count cs))
                                      (one-line (:name (first cs)))
                                      (str (count cs) " " (name kind) "s")))))]
    (cond
      (empty? changes) nil
      (= 1 (count changes)) (change-line (first changes))
      :else (str (if (<= (count full) 72) full short)
                 "\n\n" (str/join "\n" (map change-line (mapcat second groups)))))))
