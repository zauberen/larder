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

(ns com.larder.pages.recipe-view
  "Recipe display pieces shared by the app and the public dashboard."
  (:require [clojure.string :as str]
            [com.larder.recipes :as recipes]
            [com.larder.ui :as ui]))

(defn image-url
  [recipe]
  (when-let [p (:image_path recipe)] (str "/media/" p)))

(defn stars
  "The rating as five stars, or nil when unrated."
  [rating]
  (when rating
    [:span.stars {:title (str rating " out of 5") :aria-label (str rating " out of 5 stars")}
     [:span.stars-on (apply str (repeat rating "★"))]
     [:span.stars-off (apply str (repeat (- 5 rating) "★"))]]))

(defn tag-list
  [tags]
  (when (seq tags)
    (into [:div.tag-list] (for [t tags] [:span.tag t]))))

(defn recipe-card
  "A card linking to HREF. LINK-ATTRS are the link's attributes (nav-attrs in the app)."
  [recipe link-attrs & badges]
  [:a.recipe-card link-attrs
   (if-let [src (image-url recipe)]
     [:img.recipe-card-image {:src src :alt "" :loading "lazy"}]
     [:div.recipe-card-image.placeholder (ui/recipes-icon)])
   [:div.recipe-card-body
    [:span.recipe-card-title (:title recipe)]
    (into [:div.recipe-card-meta (stars (:rating recipe))] badges)]])

(defn card-grid
  [cards empty-text]
  (if (seq cards)
    (into [:div.recipe-grid] cards)
    (ui/empty-state [:p.muted empty-text])))

(defn facts
  "Servings and times."
  [recipe]
  (let [items (remove nil? [(when-let [s (:servings recipe)] ["Serves" (str s)])
                            (when-let [m (recipes/format-minutes (:prep_minutes recipe))] ["Prep" m])
                            (when-let [m (recipes/format-minutes (:cook_minutes recipe))] ["Cook" m])])]
    (when (seq items)
      (into [:dl.recipe-facts]
            (for [[k v] items] [:div [:dt k] [:dd v]])))))

(defn ingredient-list
  "LINE-FN renders one recipe_ingredients row (default: its original text)."
  ([recipe] (ingredient-list recipe (fn [line] (:original_text line))))
  ([recipe line-fn]
   (if (seq (:ingredients recipe))
     (into [:ul.ingredient-list] (for [line (:ingredients recipe)] [:li (line-fn line)]))
     [:p.muted "No ingredients listed."])))

(defn step-list
  [recipe]
  (let [steps (recipes/split-lines (:steps recipe))]
    (if (seq steps)
      (into [:ol.step-list] (for [s steps] [:li s]))
      [:p.muted "No steps written yet."])))

(defn detail-body
  "Image, description, facts, ingredients and steps. EXTRA-META goes beside the rating."
  [recipe & extra-meta]
  [:div.recipe-detail
   [:div.recipe-hero
    (when-let [src (image-url recipe)]
      [:img.recipe-image {:src src :alt ""}])
    [:div.recipe-summary
     (into [:div.recipe-meta (stars (:rating recipe))] extra-meta)
     (when-let [d (:description recipe)]
       (into [:div.recipe-description] (for [p (str/split d #"\n\s*\n")] [:p p])))
     (facts recipe)]]
   [:div.recipe-columns
    [:section [:h2 "Ingredients"] (ingredient-list recipe)]
    [:section [:h2 "Steps"] (step-list recipe)]]])
