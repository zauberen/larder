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

(ns com.larder.mcp-tools
  "The tools Claude calls over MCP. Each tool has a name, a description, a JSON Schema
  for its arguments and a function (fn [args user] -> result map). Arguments arrive as
  keyword maps. A tool signals a problem Claude can fix with `fail`, which becomes a
  tool result with isError. Every write records the calling user and the source mcp."
  (:require [clojure.string :as str]
            [com.larder.cooking :as cooking]
            [com.larder.grocery :as grocery]
            [com.larder.import.core :as importer]
            [com.larder.import.web]
            [com.larder.ingredients :as ingredients]
            [com.larder.mealplan :as mp]
            [com.larder.pages.made :as made]
            [com.larder.pantry :as pantry]
            [com.larder.recipes :as recipes]
            [com.larder.status :as status]
            [com.larder.stores :as stores]
            [com.larder.units :as units]
            [com.larder.util :as u]))

(def max-batch 200)

(defn fail
  [msg]
  (throw (ex-info msg {:tool-error msg})))

;;; Argument helpers

(defn- whole
  [v what]
  (cond (and (number? v) (== v (Math/floor (double v)))) (long v)
        (and (string? v) (re-matches #"\d+" v)) (parse-long v)
        :else (fail (str what " must be a whole number."))))

(defn- req-id [args k] (whole (get args k) (name k)))

(defn- opt-num
  [args k]
  (let [v (get args k)]
    (cond (nil? v) nil
          (number? v) (double v)
          (and (string? v) (parse-double v)) (parse-double v)
          :else (fail (str (name k) " must be a number.")))))

(defn- batch
  [args k]
  (let [v (get args k)]
    (cond (not (sequential? v)) (fail (str (name k) " must be a list."))
          (empty? v) (fail (str (name k) " is empty."))
          (> (count v) max-batch) (fail (str "At most " max-batch " " (name k) " per call."))
          :else (vec v))))

(defn- date-arg
  [args k]
  (or (mp/parse-date (get args k)) (fail (str (name k) " must be a date like 2026-11-13."))))

(defn- amount
  "[quantity unit] from tool arguments, validated like the pantry form."
  [quantity unit]
  (let [[q un errors] (pantry/parse-amount (some-> quantity str) (some-> unit str))]
    (when (seq errors) (fail (first errors)))
    [q un]))

(defn- ingredient-ref
  "The ingredient id for {:ingredient_id} or {:name} (an unknown name is created)."
  [{:keys [ingredient_id name unit]}]
  (cond
    ingredient_id (let [id (whole ingredient_id "ingredient_id")]
                    (if (ingredients/by-id id) id (fail (str "No ingredient " id "."))))
    (and (string? name) (seq (str/trim name)))
    (let [[id created?] (ingredients/find-or-create! name (some-> unit str units/normalize))]
      (when created? (ingredients/rematch-unmatched!))
      id)
    :else (fail "Give an ingredient_id or a name.")))

;;; Shapes returned to Claude

(def ^:private line-status-names {:have "have" :low "low" :unknown "have_amount_unknown" :missing "missing"})
(def ^:private recipe-status-names {:ready "pantry_ready" :some "some_in_pantry" :none "none_in_pantry"})

(defn- amount-map [q unit] (when q {:quantity q :unit unit}))

(defn- recipe-summary
  [tags r]
  {:id (:id r) :title (:title r) :status (:status r) :rating (:rating r) :servings (:servings r)
   :tags (:tags r) :pantry_status (recipe-status-names (get tags (:id r)))})

(defn- recipe-full
  [snap r]
  {:id (:id r) :title (:title r) :status (:status r) :description (:description r)
   :servings (:servings r) :prep_minutes (:prep_minutes r) :cook_minutes (:cook_minutes r)
   :rating (:rating r) :tags (:tags r) :source_url (:source_url r)
   :pantry_status (recipe-status-names (status/recipe-status snap (:ingredients r)))
   :ingredients (vec (for [line (:ingredients r)
                           :let [{st :status rem :remaining} (status/line-status snap line)]]
                       {:line_id (:id line) :text (:original_text line)
                        :quantity (:quantity line) :unit (:unit line) :note (:note line)
                        :ingredient_id (:ingredient_id line) :ingredient (:ingredient_name line)
                        :pantry_status (line-status-names st)
                        :in_pantry (when rem (amount-map (:quantity rem) (:unit rem)))}))
   :steps (recipes/split-lines (:steps r))
   :url (str "/app/recipes/" (:id r))})

(defn- ingredient-map
  [ing]
  (when ing
    {:id (:id ing) :name (:name ing) :dimension (:dimension ing) :density (:density ing)
     :default_purchase (amount-map (:default_quantity ing) (:default_unit ing))
     :always_have (= 1 (:always_have ing))
     :aliases (ingredients/aliases (:id ing))}))

(defn- pantry-map
  [row]
  {:id (:id row) :ingredient_id (:ingredient_id row) :ingredient (:ingredient_name row)
   :quantity (:quantity row) :unit (:unit row) :added (u/format-date (:added_at row)) :source (:source row)})

(defn- grocery-map
  [item]
  {:id (:id item) :section (:section item) :ingredient_id (:ingredient_id item) :name (grocery/label item)
   :quantity (:quantity item) :unit (:unit item) :store (:store_name item) :store_id (:store_id item)
   :origin (:origin item) :completed (some? (:done_at item))})

(defn- entry-map
  [snap lines entry]
  {:id (:id entry) :date (:date entry) :recipe_id (:recipe_id entry) :title (:title entry)
   :servings (:servings entry) :cooked (some? (:cooked_at entry))
   :pantry_status (recipe-status-names (status/recipe-status snap (get lines (:recipe_id entry)) (mp/scale entry)))})

;;; Recipes

(defn- form-params
  "Recipe editor params from tool arguments."
  [args]
  (let [s #(some-> % str)]
    {:title (s (:title args)) :description (s (:description args))
     :servings (s (:servings args)) :prep_minutes (s (:prep_minutes args)) :cook_minutes (s (:cook_minutes args))
     :rating (s (:rating args)) :source_url (s (:source_url args))
     :tags (str/join ", " (map str (:tags args)))
     :ingredients (str/join "\n" (map str (:ingredients args)))
     :steps (str/join "\n" (map str (:steps args)))}))

(defn- parse-recipe
  [params]
  (when (> (count (str/split-lines (:ingredients params ""))) (:lines recipes/limits))
    (fail (str "At most " (:lines recipes/limits) " ingredient lines.")))
  (let [[parsed errors] (recipes/parse-form params)]
    (when (seq errors) (fail (str/join " " errors)))
    parsed))

(defn- saved-recipe
  [id]
  (let [r (recipes/by-id id)]
    {:id id :title (:title r) :status (:status r)
     :unmatched_lines (vec (map :original_text (remove :ingredient_id (:ingredients r))))}))

(defn search-recipes
  [{:keys [query status tag limit]} _user]
  (let [found (recipes/search {:q query :status (when (#{"draft" "published"} status) status)})
        found (if (seq tag)
                (filter #(some (fn [t] (= (str/lower-case t) (str/lower-case tag))) (:tags %)) found)
                found)
        found (take (min max-batch (or (some-> limit (whole "limit")) 50)) found)
        tags (status/recipe-statuses (status/snapshot) (map :id found))]
    {:recipes (mapv #(recipe-summary tags %) found)}))

(defn get-recipe
  [args _user]
  (if-let [r (recipes/by-id (req-id args :id))]
    (recipe-full (status/snapshot) r)
    (fail "No such recipe.")))

(defn add-recipe
  [args user]
  (let [parsed (parse-recipe (form-params args))
        id (recipes/create! parsed (if (true? (:publish args)) "published" "draft") (:id user))]
    (saved-recipe id)))

(defn update-recipe
  [args _user]
  (let [r (or (recipes/by-id (req-id args :id)) (fail "No such recipe."))
        current {:title (:title r) :description (:description r) :servings (:servings r)
                 :prep_minutes (:prep_minutes r) :cook_minutes (:cook_minutes r) :rating (:rating r)
                 :source_url (:source_url r) :tags (:tags r)
                 :ingredients (map :original_text (:ingredients r)) :steps (recipes/split-lines (:steps r))}
        merged (merge current (select-keys args (keys current)))
        status (or (:status args) (:status r))]
    (when-not (#{"draft" "published"} status) (fail "status must be draft or published."))
    (recipes/update! (:id r) (parse-recipe (form-params merged)) status)
    (saved-recipe (:id r))))

(defn import-recipe-url
  [{:keys [url]} user]
  (when-not (string? url) (fail "Give the page's url."))
  (try
    (let [imported (first (importer/import-source {:kind :web :url url}))
          {:keys [id warnings]} (importer/save! imported (:id user))]
      (assoc (saved-recipe id) :warnings warnings))
    (catch clojure.lang.ExceptionInfo e
      (if-let [m (:user-message (ex-data e))]
        (fail (str m (when (:no-recipe (ex-data e)) " Read the page yourself and call add_recipe instead.")))
        (throw e)))))

;;; Ingredients

(defn resolve-ingredients
  [args _user]
  (let [index (ingredients/name-index)]
    {:results
     (vec (for [n (batch args :names)
                :let [n (str n)
                      direct (ingredients/match n index)
                      parsed (when-not direct (ingredients/parse-line n))
                      id (or direct (some-> parsed :name (ingredients/match index)))]]
            (merge {:input n :matched (some? id)}
                   (when id {:ingredient (ingredient-map (ingredients/by-id id))})
                   (when (and parsed (:quantity parsed))
                     {:parsed (amount-map (:quantity parsed) (:unit parsed)) :parsed_name (:name parsed)}))))}))

(defn create-ingredient
  [{:keys [name dimension density default_quantity default_unit always_have aliases] :as args} _user]
  (let [dq (opt-num args :default_quantity)
        du (some-> default_unit str units/normalize)
        values {:name (str/trim (str name)) :dimension (or dimension "count") :density (opt-num args :density)
                :default_quantity dq :default_unit du :always_have (true? always_have)}
        aliases (vec (remove str/blank? (map (comp str/trim str) aliases)))
        errors (concat (ingredients/validate values) (ingredients/alias-errors nil aliases)
                       (when (and dq (nil? du)) ["Give a default_unit with default_quantity."]))]
    (when (seq errors) (fail (str/join " " errors)))
    (let [id (ingredients/create! values)]
      (ingredients/set-aliases! id aliases)
      (ingredients/rematch-unmatched!)
      {:ingredient (ingredient-map (ingredients/by-id id))})))

(defn review-ingredients
  [{:keys [issues_only]} _user]
  (let [use (ingredients/usage)
        aliases (ingredients/all-aliases)
        rows (for [ing (ingredients/all)
                   :let [u (get use (:id ing))
                         found (ingredients/issues ing u)]
                   :when (or (not (true? issues_only)) (seq found))]
               {:id (:id ing) :name (:name ing) :dimension (:dimension ing) :density (:density ing)
                :default_purchase (amount-map (:default_quantity ing) (:default_unit ing))
                :always_have (= 1 (:always_have ing))
                :aliases (get aliases (:id ing) [])
                :used_in_recipes (:recipes u 0) :recipe_lines (:recipe_lines u 0)
                :pantry_rows (:pantry_rows u 0) :open_grocery_items (:grocery_items u 0)
                :units_used (:units u [])
                :issues (mapv name found)})
        unmatched (ingredients/unmatched-groups)]
    {:ingredients (vec rows)
     :unmatched_lines unmatched
     :summary {:ingredients (count (ingredients/all))
               :with_issues (count (filter #(seq (:issues %)) rows))
               :unmatched_lines (reduce + 0 (map :lines unmatched))}}))

(defn- ingredient-edit
  "The edited values of ingredient item ITEM merged over its current record, or a fail."
  [item]
  (let [ing (or (ingredients/by-id (req-id item :id)) (fail (str "No ingredient " (:id item) ".")))
        has? #(contains? item %)
        dq (if (has? :default_quantity) (opt-num item :default_quantity) (:default_quantity ing))
        du (cond (has? :default_unit) (some-> (:default_unit item) str units/normalize)
                 :else (:default_unit ing))
        density (if (has? :density) (opt-num item :density) (:density ing))
        clean (fn [xs] (vec (distinct (remove str/blank? (map (comp str/trim str) xs)))))
        aliases (cond-> (if (has? :aliases) (clean (:aliases item)) (ingredients/aliases (:id ing)))
                  (has? :add_aliases) (-> (into (clean (:add_aliases item))) distinct vec))
        values {:name (if (has? :name) (str/trim (str (:name item))) (:name ing))
                :dimension (if (has? :dimension) (:dimension item) (:dimension ing))
                :density density :default_quantity dq :default_unit (when dq du)
                :always_have (if (has? :always_have) (true? (:always_have item)) (= 1 (:always_have ing)))}
        errors (concat (ingredients/validate values (:id ing))
                       (ingredients/alias-errors (:id ing) aliases)
                       (when (and (has? :default_unit) (:default_unit item) (nil? du)) [(str "Unknown unit " (:default_unit item) ".")])
                       (when (and dq (not (pos? dq))) ["default_quantity must be above zero."])
                       (when (and dq (nil? du)) ["Give a default_unit with default_quantity."])
                       (when (> (count aliases) 50) ["Up to 50 aliases."]))]
    (when (seq errors) (fail (str/join " " errors)))
    {:id (:id ing) :values values :aliases aliases}))

(defn update-ingredients
  [args _user]
  (let [edits (vec (for [[i item] (map-indexed vector (batch args :ingredients))]
                     (try (ingredient-edit item)
                          (catch clojure.lang.ExceptionInfo e
                            (fail (str "Ingredient " (inc i) ": " (ex-message e)))))))
        ids (map :id edits)
        names (mapcat #(cons (:name (:values %)) (:aliases %)) edits)
        clash (->> (group-by ingredients/match-key names) vals (filter #(< 1 (count %))) first)]
    (when (not= (count ids) (count (distinct ids))) (fail "Each ingredient may appear once per call."))
    (when clash (fail (str "\"" (first clash) "\" is given to more than one ingredient.")))
    (doseq [{:keys [id values aliases]} edits]
      (ingredients/update! id values)
      (ingredients/set-aliases! id aliases))
    {:ingredients (mapv #(ingredient-map (ingredients/by-id %)) ids)
     :recipe_lines_matched (ingredients/rematch-unmatched!)}))

(defn merge-ingredients
  [args _user]
  (let [into-id (req-id args :into_id)
        ids (mapv #(whole % "ids") (batch args :ids))]
    (when-not (ingredients/by-id into-id) (fail (str "No ingredient " into-id ".")))
    (doseq [id ids] (when-not (ingredients/by-id id) (fail (str "No ingredient " id "."))))
    (when (some #{into-id} ids) (fail "into_id can't also be in ids."))
    (when (not= (count ids) (count (distinct ids))) (fail "ids has repeats."))
    (let [merged (vec (for [id ids :let [n (:name (ingredients/by-id id))]]
                        (assoc (ingredients/merge! id into-id) :name n)))]
      {:ingredient (ingredient-map (ingredients/by-id into-id))
       :merged merged
       :recipe_lines_matched (ingredients/rematch-unmatched!)})))

;;; Pantry

(defn list-pantry
  [{:keys [query source]} _user]
  {:items (mapv pantry-map (pantry/search {:q query :source source}))})

(defn add-pantry-items
  [args user]
  (let [items (batch args :items)
        prepared (vec (map-indexed
                       (fn [i item]
                         (try (let [[q unit] (amount (:quantity item) (:unit item))]
                                {:ref item :quantity q :unit unit})
                              (catch clojure.lang.ExceptionInfo e
                                (fail (str "Item " (inc i) ": " (ex-message e))))))
                       items))]
    {:added (vec (for [{:keys [ref quantity unit]} prepared
                       :let [id (pantry/add! {:ingredient_id (ingredient-ref ref) :quantity quantity :unit unit
                                              :source "mcp" :added_by (:id user)})]]
                   (pantry-map (pantry/by-id id))))}))

(defn update-pantry-item
  [args _user]
  (let [row (or (pantry/by-id (req-id args :id)) (fail "No such pantry item."))
        [q unit] (amount (:quantity args) (or (:unit args) (when (:quantity args) (:unit row))))]
    (pantry/set-amount! (:id row) q unit)
    {:item (pantry-map (pantry/by-id (:id row)))}))

(defn remove-pantry-items
  [args _user]
  {:removed (pantry/delete! (map #(whole % "id") (batch args :ids)))})

;;; Meal plan

(defn- check-range
  [from to]
  (when (.isAfter from to) (fail "from must not be after to."))
  (when (> (.until from to java.time.temporal.ChronoUnit/DAYS) 62) (fail "Ask for at most two months at a time.")))

(defn get-meal-plan
  [args _user]
  (let [from (date-arg args :from) to (date-arg args :to)
        _ (check-range from to)
        entries (mp/entries-between from to)
        snap (status/snapshot)
        lines (into {} (for [id (distinct (map :recipe_id entries))] [id (:ingredients (recipes/by-id id))]))]
    {:entries (mapv #(entry-map snap lines %) entries)}))

(defn set-meal-plan-entries
  [args _user]
  (let [entries (batch args :entries)
        checked (vec (for [[i e] (map-indexed vector entries)]
                       (try
                         (let [date (date-arg e :date)
                               servings (opt-num e :servings)
                               existing (when (:id e) (or (mp/by-id (whole (:id e) "id")) (fail "No such entry.")))
                               recipe-id (when-not existing (req-id e :recipe_id))]
                           (when (and recipe-id (not (recipes/by-id recipe-id))) (fail "No such recipe."))
                           (when (and servings (not (< 0 servings 10000))) (fail "servings must be above zero."))
                           {:date date :servings servings :existing existing :recipe-id recipe-id})
                         (catch clojure.lang.ExceptionInfo e
                           (fail (str "Entry " (inc i) ": " (ex-message e)))))))]
    {:entry_ids (vec (for [{:keys [date servings existing recipe-id]} checked]
                       (if existing
                         (do (when (not= (str date) (:date existing)) (mp/move! (:id existing) date))
                             (when servings (mp/set-servings! (:id existing) servings))
                             (:id existing))
                         (mp/add! recipe-id date servings))))}))

(defn remove-meal-plan-entries
  [args _user]
  (let [ids (map #(whole % "id") (batch args :ids))]
    (doseq [id ids] (mp/delete! id))
    {:removed (count ids)}))

(defn list-recipes-by-pantry-status
  [{:keys [status include_drafts]} _user]
  (let [wanted ({"pantry_ready" :ready "some_in_pantry" :some "none_in_pantry" :none} status)
        snap (status/snapshot)
        found (recipes/search {:status (when-not (true? include_drafts) "published")})]
    {:recipes
     (vec (for [r found
                :let [full (recipes/by-id (:id r))
                      tag (status/recipe-status snap (:ingredients full))]
                :when (or (nil? wanted) (= wanted tag))]
            (assoc (recipe-summary {(:id r) tag} r)
                   :missing (vec (for [line (:ingredients full)
                                       :let [st (:status (status/line-status snap line))]
                                       :when (#{:missing :low} st)]
                                   {:text (:original_text line) :status (line-status-names st)})))))}))

(defn mark-recipe-made
  [args user]
  (let [r (or (recipes/by-id (req-id args :recipe_id)) (fail "No such recipe."))
        entry-id (some-> (:meal_plan_entry_id args) (whole "meal_plan_entry_id"))
        entry (when entry-id (or (mp/by-id entry-id) (fail "No such meal plan entry.")))
        servings (or (opt-num args :servings) (:servings entry) (some-> (:servings r) double))
        snap (status/snapshot)
        index (ingredients/name-index)
        ran-out (set (for [x (:ran_out args)]
                       (if (number? x) (long x) (or (ingredients/match (str x) index) (fail (str "No ingredient called " x "."))))))
        items (for [{:keys [line] :as e} (made/items snap r servings)]
                (assoc e :ingredient_id (:ingredient_id line) :ran-out? (contains? ran-out (:ingredient_id line))))
        {:keys [ran-out added-to-grocery]} (cooking/made! {:recipe-id (:id r) :entry-id entry-id :servings servings
                                                           :user-id (:id user) :items items :origin "mcp"
                                                           :add-to-grocery? (not (false? (:add_ran_out_to_grocery args)))})]
    {:made (:title r) :servings servings
     :ran_out (vec (map #(:name (ingredients/by-id %)) ran-out))
     :added_to_grocery added-to-grocery}))

;;; Grocery

(defn list-grocery
  [{:keys [include_completed]} _user]
  {:items (mapv grocery-map (concat (grocery/open-items) (when (true? include_completed) (grocery/done-items))))})

(defn add-grocery-items
  [args user]
  (let [items (batch args :items)
        prepared (vec (for [[i item] (map-indexed vector items)]
                        (try
                          (if (and (:text item) (not (:ingredient_id item)) (not (:name item)))
                            (let [t (str/trim (str (:text item)))]
                              (when (or (str/blank? t) (> (count t) 200)) (fail "text must be 1-200 characters."))
                              {:misc t :store (:store item)})
                            (let [[q unit] (amount (:quantity item) (:unit item))]
                              {:ref item :quantity q :unit unit :store (:store item)}))
                          (catch clojure.lang.ExceptionInfo e
                            (fail (str "Item " (inc i) ": " (ex-message e)))))))]
    {:items (vec (for [{:keys [misc ref quantity unit store]} prepared
                       :let [store-id (some-> store str stores/find-or-create!)
                             id (if misc
                                  (grocery/add-misc! {:text misc :store_id store-id :origin "mcp" :added_by (:id user)})
                                  (:id (grocery/add-ingredient! {:ingredient_id (ingredient-ref ref) :quantity quantity :unit unit
                                                                 :store_id store-id :origin "mcp" :added_by (:id user)})))]]
                   (grocery-map (grocery/by-id id))))}))

(defn complete-grocery-item
  [args user]
  (let [item (or (grocery/by-id (req-id args :id)) (fail "No such grocery item."))
        _ (when (:done_at item) (fail "That item is already completed."))
        [q unit] (cond
                   (or (= "misc" (:section item)) (true? (:skip_amount args))) [nil nil]
                   (:quantity args) (amount (:quantity args) (:unit args))
                   :else (grocery/purchase-default item))]
    (grocery/complete! item q (when q unit) (:id user) "mcp")
    {:item (grocery-map (grocery/by-id (:id item))) :pantry_amount (amount-map q unit)}))

(defn assign-stores
  [args _user]
  (let [assignments (batch args :assignments)
        checked (vec (for [a assignments
                           :let [id (whole (:item_id a) "item_id")]]
                       (do (when-not (grocery/by-id id) (fail (str "No grocery item " id ".")))
                           (when (> (count (str (:store a))) 60) (fail "Store names can be up to 60 characters."))
                           [id (:store a)])))]
    (doseq [[id store] checked]
      (grocery/set-store! id (some-> store str stores/find-or-create!)))
    {:assigned (count checked)}))

(defn add-missing-for-meal-plan
  [args user]
  (let [from (date-arg args :from) to (date-arg args :to)]
    (check-range from to)
    (mp/add-to-grocery! from to (:id user) "mcp")))

;;; Stores

(defn list-stores
  [_args _user]
  {:stores (mapv #(select-keys % [:id :name :notes]) (stores/all))})

(defn add-store
  [{:keys [name notes]} _user]
  (let [errors (stores/validate (str/trim (str name)) notes)]
    (when (seq errors) (fail (str/join " " errors)))
    {:store (select-keys (stores/by-id (stores/create! (str name) notes)) [:id :name :notes])}))

;;; Definitions

(def ^:private s-str {:type "string"})
(def ^:private s-int {:type "integer"})
(def ^:private s-num {:type "number"})
(def ^:private s-date {:type "string" :description "YYYY-MM-DD"})
(def ^:private s-unit {:type "string" :description (str "One of: " (str/join ", " units/all-units))})
(def ^:private s-ids {:type "array" :items s-int :maxItems max-batch})
(defn- obj [props & required] (cond-> {:type "object" :properties props} (seq required) (assoc :required (vec required))))

(def recipe-fields
  {:title s-str :description s-str :servings s-int :prep_minutes s-int :cook_minutes s-int
   :rating (assoc s-int :minimum 1 :maximum 5)
   :tags {:type "array" :items s-str}
   :ingredients {:type "array" :items s-str :description "One line per ingredient as written, e.g. \"2 cups flour, sifted\"."}
   :steps {:type "array" :items s-str :description "One step per entry, plain text."}
   :source_url s-str})

(def ingredient-item
  (obj {:ingredient_id s-int :name (assoc s-str :description "Used when there's no ingredient_id; a new name creates the ingredient.")
        :quantity (assoc s-num :description "Omit when unknown.") :unit s-unit}))

(def tools
  [{:name "search_recipes" :fn search-recipes
    :description "Search recipes by text in the title, description or tags. Returns ids, titles, status (draft/published), rating, tags and pantry status."
    :inputSchema (obj {:query s-str :status {:type "string" :enum ["draft" "published" "all"]} :tag s-str :limit s-int})}
   {:name "get_recipe" :fn get-recipe
    :description "Get one recipe with its ingredient lines (parsed amounts, matched ingredient, pantry status and stock) and steps."
    :inputSchema (obj {:id s-int} :id)}
   {:name "add_recipe" :fn add-recipe
    :description "Add a recipe from structured fields. Saved as a draft unless publish is true. Ingredient lines are parsed and matched to ingredients; unmatched lines are returned."
    :inputSchema (obj (assoc recipe-fields :publish {:type "boolean"}) :title)}
   {:name "update_recipe" :fn update-recipe
    :description "Change a recipe. Only the fields given change; ingredients and steps, when given, replace the whole list. status sets draft or published."
    :inputSchema (obj (assoc recipe-fields :id s-int :status {:type "string" :enum ["draft" "published"]}) :id)}
   {:name "import_recipe_url" :fn import-recipe-url
    :description "Import a recipe from a web page that publishes schema.org Recipe data. Saved published with its image. If the page has no recipe data, read it and use add_recipe."
    :inputSchema (obj {:url s-str} :url)}
   {:name "resolve_ingredients" :fn resolve-ingredients
    :description "Match names (e.g. receipt lines or shopping items) to ingredients, ignoring case and plurals. Lines with amounts are parsed. Use before add_pantry_items."
    :inputSchema (obj {:names {:type "array" :items s-str :maxItems max-batch}} :names)}
   {:name "create_ingredient" :fn create-ingredient
    :description "Create an ingredient. dimension is how it's measured (mass, volume or count); density in g/ml lets mass and volume convert."
    :inputSchema (obj {:name s-str :dimension {:type "string" :enum ["mass" "volume" "count"]} :density s-num
                       :default_quantity s-num :default_unit s-unit :always_have {:type "boolean"}
                       :aliases {:type "array" :items s-str}} :name :dimension)}
   {:name "review_ingredients" :fn review-ingredients
    :description (str "Review the ingredient list: every ingredient with its aliases, density, default purchase size, how many recipes, "
                      "pantry rows and open grocery items use it, the units they use, and issues: unused, needs_density (used by weight "
                      "and by volume with no density, so amounts can't convert), measured_differently (used in count units and weights "
                      "or volumes, which never convert), no_default_purchase (nothing to add to the grocery list when it runs out). "
                      "Also lists unmatched recipe lines grouped by name. Look for duplicates (e.g. \"scallion\" and \"green onion\") "
                      "to merge_ingredients, gaps to fill with update_ingredients, and unmatched names to add as aliases or "
                      "create_ingredient. Propose the changes to the user before making them.")
    :inputSchema (obj {:issues_only {:type "boolean" :description "Only ingredients with issues."}})}
   {:name "update_ingredients" :fn update-ingredients
    :description (str "Edit ingredients in one batch. Only the fields given change; null clears density or the default purchase size. "
                      "aliases replaces the other names, add_aliases adds to them. Unmatched recipe lines are rematched afterwards.")
    :inputSchema (obj {:ingredients {:type "array" :maxItems max-batch
                                     :items (obj {:id s-int :name s-str :dimension {:type "string" :enum ["mass" "volume" "count"]}
                                                  :density (assoc s-num :type ["number" "null"] :description "Grams per millilitre.")
                                                  :default_quantity (assoc s-num :type ["number" "null"])
                                                  :default_unit s-unit :always_have {:type "boolean"}
                                                  :aliases {:type "array" :items s-str}
                                                  :add_aliases {:type "array" :items s-str}} :id)}} :ingredients)}
   {:name "merge_ingredients" :fn merge-ingredients
    :description (str "Merge duplicate ingredients (ids) into one (into_id): their recipe lines, pantry rows, grocery items and aliases "
                      "move over, their names become aliases, and into_id keeps its own settings, taking the density and default "
                      "purchase size from them only where it has none. The merged ingredients are deleted. This can't be undone.")
    :inputSchema (obj {:into_id s-int :ids s-ids} :into_id :ids)}
   {:name "list_pantry" :fn list-pantry
    :description "List pantry rows (one per purchase or entry): ingredient, quantity (null when unknown), unit, date added, source."
    :inputSchema (obj {:query s-str :source {:type "string" :enum ["grocery" "manual" "mcp"]}})}
   {:name "add_pantry_items" :fn add-pantry-items
    :description "Add items to the pantry in one batch (e.g. from a receipt), each by ingredient_id or name, with an optional quantity and unit."
    :inputSchema (obj {:items {:type "array" :items ingredient-item :maxItems max-batch}} :items)}
   {:name "update_pantry_item" :fn update-pantry-item
    :description "Set a pantry row's quantity and unit. Omit quantity to mark the amount unknown."
    :inputSchema (obj {:id s-int :quantity s-num :unit s-unit} :id)}
   {:name "remove_pantry_items" :fn remove-pantry-items
    :description "Remove pantry rows by id."
    :inputSchema (obj {:ids s-ids} :ids)}
   {:name "get_meal_plan" :fn get-meal-plan
    :description "Get planned meals between two dates (at most two months) with servings, cooked flag and pantry status."
    :inputSchema (obj {:from s-date :to s-date} :from :to)}
   {:name "set_meal_plan_entries" :fn set-meal-plan-entries
    :description "Plan recipes on dates. Entries without an id are added; entries with an id are moved or their servings changed. Servings default to the recipe's."
    :inputSchema (obj {:entries {:type "array" :maxItems max-batch
                                 :items (obj {:id s-int :date s-date :recipe_id s-int :servings s-num} :date)}} :entries)}
   {:name "remove_meal_plan_entries" :fn remove-meal-plan-entries
    :description "Remove planned meals by entry id."
    :inputSchema (obj {:ids s-ids} :ids)}
   {:name "list_recipes_by_pantry_status" :fn list-recipes-by-pantry-status
    :description "List published recipes with their pantry status (pantry_ready, some_in_pantry, none_in_pantry) and the ingredients that are missing or low."
    :inputSchema (obj {:status {:type "string" :enum ["pantry_ready" "some_in_pantry" "none_in_pantry"]} :include_drafts {:type "boolean"}})}
   {:name "mark_recipe_made" :fn mark-recipe-made
    :description "Record that a recipe was cooked: deducts its ingredients from the pantry (scaled to servings), clears ingredients that ran out (adding them to the grocery list unless add_ran_out_to_grocery is false) and marks the meal plan entry cooked."
    :inputSchema (obj {:recipe_id s-int :servings s-num :meal_plan_entry_id s-int
                       :ran_out {:type "array" :items {:type ["integer" "string"]} :description "Ingredient ids or names that ran out."}
                       :add_ran_out_to_grocery {:type "boolean"}} :recipe_id)}
   {:name "list_grocery" :fn list-grocery
    :description "List the grocery list: grocery items (tracked into the pantry) and misc items, with amounts and stores."
    :inputSchema (obj {:include_completed {:type "boolean"}})}
   {:name "add_grocery_items" :fn add-grocery-items
    :description "Add items to the grocery list: ingredients (by ingredient_id or name, optional quantity, unit and store) are tracked into the pantry; give text instead for misc items. An ingredient already listed has its amount raised when units convert."
    :inputSchema (obj {:items {:type "array" :maxItems max-batch
                               :items (obj (merge (:properties ingredient-item) {:text s-str :store s-str}))}} :items)}
   {:name "complete_grocery_item" :fn complete-grocery-item
    :description "Check off a grocery item. A grocery item goes into the pantry with the quantity given, else its default amount; skip_amount records it with an unknown amount. Misc items just complete."
    :inputSchema (obj {:id s-int :quantity s-num :unit s-unit :skip_amount {:type "boolean"}} :id)}
   {:name "assign_stores" :fn assign-stores
    :description "Set the store for grocery items. A new store name creates the store; null clears it."
    :inputSchema (obj {:assignments {:type "array" :maxItems max-batch
                                     :items (obj {:item_id s-int :store {:type ["string" "null"]}} :item_id)}} :assignments)}
   {:name "add_missing_for_meal_plan" :fn add-missing-for-meal-plan
    :description "Add to the grocery list what the uncooked meals between two dates need beyond the pantry and what's already listed."
    :inputSchema (obj {:from s-date :to s-date} :from :to)}
   {:name "list_stores" :fn list-stores
    :description "List stores grocery items can be assigned to."
    :inputSchema (obj {})}
   {:name "add_store" :fn add-store
    :description "Add a store."
    :inputSchema (obj {:name s-str :notes s-str} :name)}])
