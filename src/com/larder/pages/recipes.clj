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

(ns com.larder.pages.recipes
  "Logged-in recipe pages: the list, the detail view and the editor, plus the image
  endpoint shared with the public dashboard."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.larder.auth :as auth]
            [com.larder.images :as images]
            [com.larder.import.core :as importer]
            [com.larder.import.web]
            [com.larder.grocery :as grocery]
            [com.larder.ingredients :as ingredients]
            [com.larder.pages.layout :as layout]
            [com.larder.pages.recipe-view :as view]
            [com.larder.recipes :as recipes]
            [com.larder.response :as r]
            [com.larder.status :as status]
            [com.larder.ui :as ui]
            [com.larder.units :as units]
            [com.larder.uploads :as uploads]
            [com.larder.util :as u]))

(defn recipe-path [id] (str "/app/recipes/" id))

(defn- draft-badge [] [:span.badge.draft "Draft"])

;;; List

(defn recipe-list
  [params]
  (let [found (recipes/search {:q (:q params) :status (:status params)})
        tags (status/recipe-statuses (status/snapshot) (map :id found))
        filtered? (or (seq (str/trim (or (:q params) ""))) (recipes/statuses (:status params)))]
    [:div#recipe-list
     (view/card-grid (for [rec found]
                       (view/recipe-card rec (ui/nav-attrs (recipe-path (:id rec)))
                                         (when (= "draft" (:status rec)) (draft-badge))
                                         (view/status-tag (tags (:id rec)) #{:ready})))
                     (if filtered? "No recipes match." "No recipes yet. Use Add to create one."))]))

(defn recipes-page
  [req user]
  (let [params (:params req)]
    (layout/app-page
     req user
     (layout/main
      "recipes"
      [:div.page-top
       [:h1.dashboard-title "Recipes"]
       [:a.btn.btn-primary (ui/nav-attrs "/app/recipes/new") "New recipe"]]
      [:form.list-filters {:hx-get "/app" :hx-trigger "input changed delay:300ms, change"
                           :hx-target "#recipe-list" :hx-select "#recipe-list" :hx-swap "outerHTML"
                           :hx-push-url "true" :role "search"}
       [:input.form-input {:type "search" :name "q" :value (:q params) :placeholder "Search recipes"
                           :aria-label "Search recipes"}]
       (ui/select "status" [["" "All"] ["published" "Published"] ["draft" "Drafts"]] (:status params)
                  {:aria-label "Show"})]
      (recipe-list params)))))

;;; Detail

(defn grocery-button
  [recipe-id line]
  [:button.icon-button.grocery-add-button {:hx-post (str "/api/recipes/" recipe-id "/lines/" (:id line) "/grocery")
                                           :hx-swap "outerHTML"
                                           :title "Add to the grocery list"
                                           :aria-label (str "Add " (:original_text line) " to the grocery list")}
   (ui/grocery-icon)])

(defn line-with-status
  "A recipe line with its pantry status, the amount in stock and a grocery button."
  [snap recipe-id line]
  (let [{st :status rem :remaining always? :always?} (status/line-status snap line)]
    [:div.line-with-status
     [:div.line-text
      [:span (:original_text line)]
      (if (:ingredient_id line)
        [:span.line-status
         [:span.badge.line-badge {:class (name st)} (if always? "Always have" (status/line-labels st))]
         (when rem [:span.muted.remaining (str (units/format-qty (:quantity rem)) " " (:unit rem) " in pantry")])]
        [:span.line-status [:span.badge.unmatched {:title "Not matched to an ingredient"} "Not matched"]])]
     (grocery-button recipe-id line)]))

(defn detail-main
  [recipe & [message]]
  (let [snap (status/snapshot)]
  (layout/main
   "recipes"
   [:div.page-top
    [:div
     [:h1.dashboard-title.compact (:title recipe)]
     [:div.recipe-subline
      (when (= "draft" (:status recipe)) (draft-badge))
      (view/status-tag (status/recipe-status snap (:ingredients recipe)))
      (view/tag-list (:tags recipe))
      (when-let [src (:source_url recipe)]
        [:a.source-link {:href src :target "_blank" :rel "noopener noreferrer"} "Source"])]]
    [:div.page-actions
     [:button.btn.btn-primary {:hx-get (str "/api/recipes/" (:id recipe) "/made") :hx-target "#modal"} "Made it"]
     [:button.btn.btn-secondary {:hx-post (str "/api/recipes/" (:id recipe) "/grocery") :hx-target "#recipe-message"
                                 :hx-swap "innerHTML"}
      "Add missing to grocery list"]
     [:a.btn.btn-secondary (ui/nav-attrs (str (recipe-path (:id recipe)) "/edit")) "Edit"]
     [:button.btn.btn-danger {:hx-post (str "/api/recipes/" (:id recipe) "/delete")
                              :hx-confirm (str "Delete " (:title recipe) "? This can't be undone.")}
      "Delete"]]]
   [:div#recipe-message (ui/message-alert message)]
   (let [unmatched (count (remove :ingredient_id (:ingredients recipe)))]
     (when (pos? unmatched)
       (ui/alert :info
                 (str unmatched (if (= 1 unmatched) " ingredient line isn't" " ingredient lines aren't")
                      " matched to an ingredient yet, so the pantry can't track "
                      (if (= 1 unmatched) "it" "them") ". ")
                 [:a (ui/nav-attrs (str (recipe-path (:id recipe)) "/edit")) "Review in the editor"])))
   (view/detail-body recipe {:line-fn #(line-with-status snap (:id recipe) %)
                             ;; Statuses follow the pantry (Made it, grocery check-offs).
                             :ingredients-attrs {:hx-get (recipe-path (:id recipe)) :hx-select "#recipe-ingredients"
                                                 :hx-target "this" :hx-swap "outerHTML"
                                                 :hx-trigger "pantry-changed from:body" :hx-disinherit "*"}})
   [:div#modal])))

(defn recipe-page
  [req user id]
  (if-let [recipe (recipes/by-id (u/parse-int id))]
    (layout/app-page req user (detail-main recipe))
    (auth/not-found)))

;;; Grocery list from a recipe

(defn add-line-to-grocery!
  "Put recipe LINE on the grocery list for USER: a matched line as its ingredient (the
  default purchase size, else the recipe amount), an unmatched one as a misc item with
  its text. Returns :added, :merged or :already (misc text already listed)."
  [snap line user]
  (if-let [ing-id (:ingredient_id line)]
    (let [[q unit] (status/grocery-amount snap line)]
      (if (:merged? (grocery/add-ingredient! {:ingredient_id ing-id :quantity q :unit unit
                                              :origin "recipe" :added_by (:id user)}))
        :merged
        :added))
    (if (grocery/open-misc-text? (:original_text line))
      :already
      (do (grocery/add-misc! {:text (:original_text line) :origin "recipe" :added_by (:id user)})
          :added))))

(defn api-line-to-grocery
  [_req user recipe-id line-id]
  (let [recipe (recipes/by-id (u/parse-int recipe-id))
        line (first (filter #(= (u/parse-int line-id) (:id %)) (:ingredients recipe)))]
    (if line
      (do (add-line-to-grocery! (status/snapshot) line user)
          (r/h-respond [:span.on-list {:title "On the grocery list"} "✓"]))
      (auth/not-found))))

(defn api-missing-to-grocery
  "Add every Missing or Low line that isn't on the grocery list yet."
  [_req user recipe-id]
  (if-let [recipe (recipes/by-id (u/parse-int recipe-id))]
    (let [snap (status/snapshot)
          wanted (filter #(#{:missing :low} (:status (status/line-status snap %))) (:ingredients recipe))
          results (for [line wanted]
                    (if (and (:ingredient_id line) (grocery/open-item-for-ingredient? (:ingredient_id line)))
                      :already
                      (add-line-to-grocery! snap line user)))
          added (count (filter #{:added :merged} results))
          already (count (filter #{:already} results))]
      (r/h-respond
       (ui/alert (if (pos? added) :success :info)
                 (cond
                   (empty? wanted) "Everything is in the pantry."
                   (zero? added) "Everything missing is already on the grocery list."
                   :else (str "Added " added (if (= 1 added) " item" " items") " to the grocery list"
                              (when (pos? already) (str "; " already " already on it"))
                              ".")))))
    (auth/not-found)))

;;; Unmatched ingredient picker

(defn picker-section
  "The unmatched lines of saved recipe RECIPE-ID, each with a choice of an existing
  ingredient or a new one."
  [recipe-id & [message]]
  (let [lines (recipes/unmatched-lines recipe-id)
        options (cons ["" "Choose an ingredient…"] (map (juxt :id :name) (ingredients/all)))
        target {:hx-target "#unmatched-lines" :hx-swap "outerHTML"}]
    [:section#unmatched-lines.card.picker-card
     (cond
       (seq lines)
       (list
        [:div.card-header [:h2 "Match ingredients"]]
        [:p.muted "These lines don't match an ingredient yet. Choose the ingredient each one means "
         "(its name is remembered for next time) or create a new one."]
        (ui/message-alert message)
        (into [:ul.picker-list]
              (for [{:keys [id name unit original_text]} lines
                    :let [api (str "/api/recipes/" recipe-id "/lines/" id)]]
                [:li.picker-row
                 [:div.picker-line [:code original_text]]
                 [:form.inline-form (merge {:hx-post (str api "/match")} target)
                  (ui/select "ingredient_id" options nil {:required true :aria-label (str "Ingredient for " original_text)})
                  [:button.btn.btn-secondary.small {:type "submit"} "Use"]]
                 [:form.inline-form (merge {:hx-post (str api "/create")} target)
                  [:input.form-input {:name "name" :value name :required true :maxlength 100
                                      :aria-label (str "New ingredient name for " original_text)}]
                  (ui/select "dimension" [["mass" "Weighed"] ["volume" "Measured"] ["count" "Counted"]]
                             (ingredients/guess-dimension unit) {:aria-label "Measured by"})
                  [:button.btn.btn-secondary.small {:type "submit"} "Create"]]])))
       message (ui/message-alert message)
       :else nil)]))

(defn- line-of
  [recipe-id line-id]
  (first (filter #(= (u/parse-int line-id) (:id %)) (recipes/unmatched-lines (u/parse-int recipe-id)))))

(defn api-match-line
  "Use an existing ingredient for an unmatched line; its parsed name becomes an alias."
  [req _user recipe-id line-id]
  (let [line (line-of recipe-id line-id)
        ing (ingredients/by-id (u/parse-int (get-in req [:params :ingredient_id])))]
    (cond
      (nil? line) (r/h-respond (picker-section (u/parse-int recipe-id) {:kind :info :text "That line is already matched."}))
      (nil? ing) (r/h-respond (picker-section (u/parse-int recipe-id) {:kind :error :text "Choose an ingredient."}))
      :else (do (ingredients/add-alias! (:id ing) (:name line))
                (recipes/set-line-ingredient! (:id line) (:id ing))
                (ingredients/rematch-unmatched!)
                (r/h-respond (picker-section (u/parse-int recipe-id)
                                             {:kind :success :text (str "Matched to " (:name ing) ".")}))))))

(defn api-create-ingredient
  "Create an ingredient for an unmatched line. If it is named differently from the line,
  the line's name becomes an alias."
  [req _user recipe-id line-id]
  (let [line (line-of recipe-id line-id)
        {:keys [name dimension]} (:params req)
        new-ing {:name (str/trim (or name "")) :dimension dimension}
        errors (ingredients/validate new-ing)]
    (cond
      (nil? line) (r/h-respond (picker-section (u/parse-int recipe-id) {:kind :info :text "That line is already matched."}))
      (seq errors) (r/h-respond (picker-section (u/parse-int recipe-id) {:kind :error :text (str/join " " errors)}))
      :else (let [id (ingredients/create! new-ing)]
              (ingredients/add-alias! id (:name line))
              (recipes/set-line-ingredient! (:id line) id)
              (ingredients/rematch-unmatched!)
              (r/h-respond (picker-section (u/parse-int recipe-id)
                                           {:kind :success :text (str "Created " (:name new-ing) ".")}))))))

;;; Editor

(def ^:private buttons
  {:new [["draft" "Save draft" "btn-secondary"] ["publish" "Publish" "btn-primary"]]
   "draft" [["draft" "Save draft" "btn-secondary"] ["publish" "Publish" "btn-primary"]]
   "published" [["revert" "Revert to draft" "btn-secondary"] ["save" "Save" "btn-primary"]]})

(defn- rating-input
  [rating]
  [:fieldset.form-field.rating-field
   [:legend.form-label "Rating"]
   [:div.rating-row
    (into [:div.rating-input]
          (for [n [5 4 3 2 1]]
            (list [:input {:type "radio" :name "rating" :value n :id (str "rating-" n)
                           :checked (= (str n) (str rating))}]
                  [:label {:for (str "rating-" n) :title (str n " out of 5")}
                   [:span.visually-hidden (str n " stars")] "★"])))
    [:label.checkbox.rating-clear
     [:input {:type "radio" :name "rating" :value "" :checked (str/blank? (str rating))}]
     [:span "No rating"]]]])

(defn- image-input
  "The image field. STAGED is an upload id already received (kept after validation errors)."
  [recipe staged]
  [:div.form-field
   [:span.form-label "Image"]
   (when-let [src (view/image-url recipe)]
     [:div.image-current
      [:img {:src src :alt ""}]
      [:label.checkbox [:input {:type "checkbox" :name "remove_image" :value "1"}] [:span "Remove image"]]])
   [:input.form-input {:type "file" :accept "image/*" :data-upload-file true :aria-label "Image"}]
   [:input {:type "hidden" :name "image_upload" :value staged :data-upload-id true}]
   [:span.form-hint (if staged
                      "An image is ready and will be saved with the recipe."
                      "JPEG, PNG, GIF or BMP, up to 10 MB.")]
   [:div.upload-status {:hidden true}
    [:progress.upload-progress {:max 100}]
    [:span.upload-status-text.form-hint {:role "status" :aria-live "polite"}]]])

(defn editor-main
  "The editor for RECIPE (nil for a new one). VALUES are the typed form values to show
  instead of the recipe's (after errors)."
  [user recipe & [values errors]]
  (let [new? (nil? recipe)
        v (or values
              (when recipe
                {:title (:title recipe) :description (:description recipe)
                 :servings (:servings recipe) :prep_minutes (:prep_minutes recipe)
                 :cook_minutes (:cook_minutes recipe) :source_url (:source_url recipe)
                 :rating (:rating recipe) :steps (:steps recipe)
                 :tags (str/join ", " (:tags recipe))
                 :ingredients (str/join "\n" (map :original_text (:ingredients recipe)))})
              {:servings 4})
        staged (let [id (:image_upload v)] (when (and id (uploads/info id (:id user))) id))]
    (layout/main
     (if new? "add" "recipes")
     [:div.page-top
      [:h1.dashboard-title.compact (if new? "New recipe" (str "Edit " (:title recipe)))]
      (when-not new?
        [:a.btn.btn-secondary (ui/nav-attrs (recipe-path (:id recipe))) "Cancel"])]
     (when-not new? (picker-section (:id recipe)))
     [:form.form.recipe-editor {:hx-post (if new? "/api/recipes" (str "/api/recipes/" (:id recipe)))
                                :hx-target "#main" :hx-swap "outerHTML"
                                :data-upload "/api/uploads" :data-upload-kind "image"}
      (ui/errors-alert errors)
      (ui/field "Title" [:input.form-input {:name "title" :value (:title v) :required true
                                            :maxlength (:title recipes/limits)}])
      (ui/field "Description" [:textarea.form-input {:name "description" :rows 3} (:description v)])
      [:div.form-row
       (ui/field "Servings" [:input.form-input {:name "servings" :type "number" :min 1 :value (:servings v)}])
       (ui/field "Prep time (minutes)" [:input.form-input {:name "prep_minutes" :type "number" :min 0 :value (:prep_minutes v)}])
       (ui/field "Cook time (minutes)" [:input.form-input {:name "cook_minutes" :type "number" :min 0 :value (:cook_minutes v)}])]
      (ui/field "Tags" [:input.form-input {:name "tags" :value (:tags v) :placeholder "dinner, vegetarian"}]
                "Separate tags with commas.")
      (image-input recipe staged)
      (rating-input (:rating v))
      (ui/field "Ingredients" [:textarea.form-input.mono {:name "ingredients" :rows 10
                                                          :placeholder "2 cups flour\n1 tsp salt\n3 eggs, beaten"}
                               (:ingredients v)]
                "One ingredient per line.")
      (ui/field "Steps" [:textarea.form-input {:name "steps" :rows 10} (:steps v)]
                "One step per line.")
      (ui/field "Source" [:input.form-input {:name "source_url" :type "url" :value (:source_url v)
                                             :placeholder "https://"}])
      (into [:div.form-actions]
            (for [[action label cls] (get buttons (if new? :new (:status recipe)))]
              [:button.btn {:type "submit" :name "action" :value action :class cls} label]))])))

(defn edit-page
  [req user id]
  (if-let [recipe (recipes/by-id (u/parse-int id))]
    (layout/app-page req user (editor-main user recipe))
    (auth/not-found)))

;;; Saving

(defn next-status
  "The status after pressing ACTION on a recipe in CURRENT state (nil for new), or nil
  when that button isn't offered there."
  [current action]
  (case [(or current "new") action]
    (["new" "draft"] ["draft" "draft"]) "draft"
    (["new" "publish"] ["draft" "publish"] ["published" "save"]) "published"
    ["published" "revert"] "draft"
    nil))

(defn- store-upload!
  "Turn the finished upload ID into a stored image. Returns [image-name error]."
  [id user]
  (try
    (let [{:keys [file]} (uploads/take! id (:id user))]
      (try [(images/store! file) nil]
           (finally (.delete ^java.io.File file))))
    (catch clojure.lang.ExceptionInfo e
      [nil (or (:user-message (ex-data e)) "The image couldn't be saved.")])
    (catch Exception e
      (log/warn e "Storing an image failed")
      [nil "The image couldn't be saved."])))

(defn- saved-response
  [recipe-id message]
  (-> (r/h-respond (detail-main (recipes/by-id recipe-id) message))
      (assoc-in [:headers "HX-Push-Url"] (recipe-path recipe-id))))

(defn- save
  "Create (RECIPE nil) or update RECIPE from the editor form."
  [req user recipe]
  (let [params (:params req)
        status (next-status (:status recipe) (u/single (:action params)))
        [parsed errors] (recipes/parse-form params)
        errors (cond-> errors (nil? status) (conj "Unknown action."))
        upload-id (some-> (:image_upload params) str/trim not-empty)
        redisplay #(r/h-respond (editor-main user recipe %1 %2))]
    (if (seq errors)
      (redisplay params errors)
      (let [[image-name image-error] (when upload-id (store-upload! upload-id user))]
        (if image-error
          (redisplay (dissoc params :image_upload) [image-error])
          (let [id (if recipe
                     (do (recipes/update! (:id recipe) parsed status) (:id recipe))
                     (recipes/create! parsed status (:id user)))]
            (cond
              image-name (images/delete! (recipes/set-image! id image-name))
              (= "1" (:remove_image params)) (images/delete! (recipes/set-image! id nil)))
            (saved-response id {:kind :success
                                :text (case status "published" "Published." "Saved as a draft.")})))))))

(defn api-create
  [req user]
  (save req user nil))

(defn api-update
  [req user id]
  (if-let [recipe (recipes/by-id (u/parse-int id))]
    (save req user recipe)
    (auth/not-found)))

(defn api-delete
  [_req _user id]
  (if-let [recipe (recipes/by-id (u/parse-int id))]
    (do (images/delete! (recipes/delete! (:id recipe)))
        {:status 200 :body ""
         :headers {"HX-Location" (json/write-str {:path "/app" :target "#main" :select "#main" :swap "outerHTML"})}})
    (auth/not-found)))

;;; Add and import

(defn add-main
  "The Add page: start a blank recipe or import one from a web page. On a failed import
  URL is kept and, when the page has no recipe data, a blank editor is offered with it."
  [& [{:keys [url message offer-blank?]}]]
  (layout/main
   "add"
   [:h1.dashboard-title "Add a recipe"]
   [:div.add-choices
    [:section.card.add-choice
     [:div.card-header [:h2 "Import from URL"]]
     [:p.muted "Paste the address of a recipe page. Larder reads the recipe data most recipe sites publish "
      "and saves it, published, with its image."]
     (ui/message-alert message)
     (when offer-blank?
       [:p [:a.btn.btn-secondary.small (ui/nav-attrs (str "/app/recipes/new?blank=1&source_url="
                                                          (java.net.URLEncoder/encode (str url) "UTF-8")))
            "Write it in the editor instead"]])
     [:form.inline-form.import-form {:hx-post "/api/recipes/import" :hx-target "#main" :hx-swap "outerHTML"
                                     :hx-disabled-elt "find button"}
      [:input.form-input {:type "url" :name "url" :value url :required true :placeholder "https://"
                          :aria-label "Recipe address"}]
      [:button.btn.btn-primary {:type "submit"} [:span.when-idle "Import"] [:span.when-busy "Importing…"]]]]
    [:section.card.add-choice
     [:div.card-header [:h2 "New recipe"]]
     [:p.muted "Type a recipe in yourself. Save it as a draft until it's ready to publish."]
     [:a.btn.btn-secondary (ui/nav-attrs "/app/recipes/new?blank=1") "Start a blank recipe"]]]))

(defn new-page
  "/app/recipes/new: the choice, or with ?blank=1 the editor (source_url may be prefilled)."
  [req user]
  (let [{:keys [blank source_url]} (:params req)]
    (layout/app-page req user
                     (if blank
                       (editor-main user nil (when (seq source_url) {:servings 4 :source_url source_url}))
                       (add-main)))))

(defn api-import
  "Import the recipe at the posted URL and show it."
  [req user]
  (let [url (str/trim (str (get-in req [:params :url])))]
    (try
      (let [imported (first (importer/import-source {:kind :web :url url}))
            {:keys [id warnings]} (importer/save! imported (:id user))]
        (saved-response id {:kind (if (seq warnings) :info :success)
                            :text (str/join " " (cons "Imported and published." warnings))}))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [user-message no-recipe]} (ex-data e)]
          (when-not user-message (log/warn e "Import failed for" url))
          (r/h-respond (add-main {:url url
                                  :message {:kind :error :text (or user-message "The import failed.")}
                                  :offer-blank? no-recipe})))))))

;;; Uploads and images

(def upload-kinds
  "Upload kind -> largest file accepted."
  {"image" images/max-bytes})

(defn- upload-error
  [status msg]
  {:status status :headers {"Content-Type" "text/plain"} :body msg})

(defn api-upload-start
  "POST /api/uploads with filename, size and kind; responds with {id, chunkSize}."
  [req user]
  (let [{:keys [filename size kind]} (:params req)]
    (if-let [max-bytes (upload-kinds kind)]
      (try
        (let [{:keys [id chunk-bytes]} (uploads/start! (:id user) filename (u/parse-int size) max-bytes)]
          (r/respond-generic (json/write-str {:id id :chunkSize chunk-bytes}) "application/json"))
        (catch clojure.lang.ExceptionInfo e
          (upload-error 400 (or (:user-message (ex-data e)) "The upload failed."))))
      (upload-error 400 "Unknown upload kind."))))

(defn api-upload-chunk
  "PUT /api/uploads/:id?offset=N with the raw bytes of one piece; responds with the
  bytes received so far (409 with that count when OFFSET doesn't match)."
  [req user id]
  (let [offset (u/parse-int (get-in req [:params :offset]))]
    (try
      (if (nil? offset)
        (upload-error 400 "Missing offset.")
        (let [result (uploads/append! id (:id user) offset (:body req))]
          (if (map? result)
            (upload-error 409 (str (:conflict result)))
            (r/respond-generic (str result) "text/plain"))))
      (catch clojure.lang.ExceptionInfo e
        (upload-error 400 (or (:user-message (ex-data e)) "The upload failed."))))))

(defn api-upload-delete
  [_req user id]
  (uploads/discard! id (:id user))
  {:status 204 :body ""})

(defn media
  "GET /media/:file: images of published recipes for everyone, of drafts for signed-in
  users. Always served as image/jpeg (they are re-encoded on the way in)."
  [req file]
  (let [recipe (recipes/by-image file)
        f (images/media-file file)
        published? (= "published" (:status recipe))]
    (if (and recipe f (or published? (auth/current-user req)))
      {:status 200
       :headers {"Content-Type" "image/jpeg"
                 ;; File names are random and never reused, so they can be cached.
                 "Cache-Control" (if published? "public, max-age=604800" "private, max-age=604800")}
       :body f}
      (auth/not-found))))
