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
            [com.larder.pages.layout :as layout]
            [com.larder.pages.recipe-view :as view]
            [com.larder.recipes :as recipes]
            [com.larder.response :as r]
            [com.larder.ui :as ui]
            [com.larder.uploads :as uploads]
            [com.larder.util :as u]))

(defn recipe-path [id] (str "/app/recipes/" id))

(defn- draft-badge [] [:span.badge.draft "Draft"])

;;; List

(defn recipe-list
  [params]
  (let [found (recipes/search {:q (:q params) :status (:status params)})
        filtered? (or (seq (str/trim (or (:q params) ""))) (recipes/statuses (:status params)))]
    [:div#recipe-list
     (view/card-grid (for [rec found]
                       (apply view/recipe-card rec (ui/nav-attrs (recipe-path (:id rec)))
                              (when (= "draft" (:status rec)) [(draft-badge)])))
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

(defn detail-main
  [recipe & [message]]
  (layout/main
   "recipes"
   [:div.page-top
    [:div
     [:h1.dashboard-title.compact (:title recipe)]
     [:div.recipe-subline
      (when (= "draft" (:status recipe)) (draft-badge))
      (view/tag-list (:tags recipe))
      (when-let [src (:source_url recipe)]
        [:a.source-link {:href src :target "_blank" :rel "noopener noreferrer"} "Source"])]]
    [:div.page-actions
     [:a.btn.btn-secondary (ui/nav-attrs (str (recipe-path (:id recipe)) "/edit")) "Edit"]
     [:button.btn.btn-danger {:hx-post (str "/api/recipes/" (:id recipe) "/delete")
                              :hx-confirm (str "Delete " (:title recipe) "? This can't be undone.")}
      "Delete"]]]
   (ui/message-alert message)
   (view/detail-body recipe)))

(defn recipe-page
  [req user id]
  (if-let [recipe (recipes/by-id (u/parse-int id))]
    (layout/app-page req user (detail-main recipe))
    (auth/not-found)))

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

(defn new-page
  [req user]
  (layout/app-page req user (editor-main user nil)))

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
