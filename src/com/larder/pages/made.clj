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

(ns com.larder.pages.made
  "The Made it dialog, opened from a recipe or (later) a meal plan entry: what to take
  out of the pantry, scaled to the servings cooked, with a Ran out answer per ingredient."
  (:require [clojure.string :as str]
            [com.larder.auth :as auth]
            [com.larder.cooking :as cooking]
            [com.larder.pages.ingredients :as ing-page]
            [com.larder.pantry :as pantry]
            [com.larder.recipes :as recipes]
            [com.larder.response :as r]
            [com.larder.status :as status]
            [com.larder.ui :as ui]
            [com.larder.units :as units]
            [com.larder.util :as u]
            [hiccup2.core :as h]))

(defn scale
  "Factor for cooking SERVINGS of RECIPE (1 when either is unknown)."
  [recipe servings]
  (if (and servings (pos? servings) (:servings recipe) (pos? (:servings recipe)))
    (/ (double servings) (:servings recipe))
    1.0))

(defn- servings-param
  [s]
  (let [n (some-> s str str/trim (str/replace "," ".") parse-double)]
    (when (and n (< 0 n 10000)) n)))

(defn items
  "One entry per matched line: the line, its ingredient and the amount to deduct."
  [snap recipe servings]
  (let [k (scale recipe servings)]
    (for [line (:ingredients recipe)
          :when (:ingredient_id line)
          :let [ing (get-in snap [:ingredients (:ingredient_id line)])]]
      {:line line
       :ingredient ing
       :always? (= 1 (:always_have ing))
       :quantity (some-> (:quantity line) (* k))
       :unit (or (:unit line) (pantry/default-unit ing))})))

(defn- item-row
  [{:keys [line ingredient always? quantity unit]} values]
  (let [id (:id line)
        v #(get values (keyword (str % "_" id)))]
    [:li.made-item
     [:div.made-name
      [:strong (:name ingredient)]
      [:span.muted (:original_text line)]]
     (if always?
       [:span.muted.made-always "Always have — not deducted"]
       (list
        [:div.inline-form.made-amount
         [:input.form-input {:type "number" :step "any" :min 0 :name (str "q_" id)
                             :value (if values (v "q") (units/input-qty quantity))
                             :placeholder "none" :aria-label (str "Amount of " (:name ingredient) " used")}]
         (ui/select (str "u_" id) (ing-page/unit-options "—") (if values (v "u") unit)
                    {:class "unit-input" :aria-label (str "Unit of " (:name ingredient))})]
        [:label.checkbox.ran-out
         [:input {:type "checkbox" :name (str "out_" id) :value "1" :checked (= "1" (v "out"))}]
         [:span "Ran out"]]))]))

(defn dialog
  "The Made it dialog for RECIPE cooked for SERVINGS (ENTRY-ID: a meal plan entry).
  VALUES are typed values to show again after an error."
  [recipe servings entry-id & [values error]]
  (let [snap (status/snapshot)
        servings (or servings (:servings recipe))
        entries (items snap recipe servings)
        unmatched (count (remove :ingredient_id (:ingredients recipe)))
        base (str "/api/recipes/" (:id recipe) "/made")]
    [:dialog.bs-dialog.wide-dialog {:data-modal true :aria-labelledby "made-title"}
     [:form.form {:hx-post base :hx-target "#modal"}
      [:h2#made-title (str "Made " (:title recipe))]
      [:p.muted "These amounts come out of the pantry, oldest first. Change any that differ, "
       "and tick Ran out for anything that's finished."]
      (when error (ui/alert :error error))
      (when entry-id [:input {:type "hidden" :name "entry" :value entry-id}])
      [:label.form-field.made-servings
       [:span.form-label "Servings cooked"]
       [:input.form-input {:type "number" :name "servings" :min 0.25 :step "any"
                           :value (units/input-qty servings)
                           :hx-get base :hx-trigger "change" :hx-target "#made-items" :hx-select "#made-items"
                           :hx-swap "outerHTML" :hx-include "this, [name=entry]"}]]
      [:div#made-items
       (if (seq entries)
         (into [:ul.made-list] (map #(item-row % values) entries))
         [:p.muted "None of this recipe's ingredients are matched, so nothing comes out of the pantry."])
       (when (pos? unmatched)
         [:p.form-hint (str unmatched (if (= 1 unmatched) " line isn't" " lines aren't")
                            " matched to an ingredient and won't be deducted.")])]
      [:label.checkbox
       [:input {:type "checkbox" :name "add_to_grocery" :value "1"
                :checked (if values (= "1" (:add_to_grocery values)) true)}]
       [:span "Add what ran out to the grocery list"]]
      [:div.form-actions
       [:button.btn.btn-secondary {:type "button" :data-close-modal true} "Cancel"]
       [:button.btn.btn-primary {:type "submit"} "Done"]]]]))

(defn api-dialog
  [req _user id]
  (if-let [recipe (recipes/by-id (u/parse-int id))]
    (let [{:keys [servings entry]} (:params req)]
      (r/h-respond (dialog recipe (servings-param (u/single servings)) (u/parse-int (u/single entry)))))
    (auth/not-found)))

(defn api-made
  "Confirm the dialog: deduct, handle Ran out, log the cook."
  [req user id]
  (if-let [recipe (recipes/by-id (u/parse-int id))]
    (let [params (:params req)
          servings (servings-param (u/single (:servings params)))
          entry-id (u/parse-int (u/single (:entry params)))
          snap (status/snapshot)
          parsed (for [{:keys [line] :as e} (items snap recipe servings)
                       :let [lid (:id line)
                             get-p #(u/single (get params (keyword (str % "_" lid))))
                             [q unit errors] (pantry/parse-amount (get-p "q") (get-p "u"))]]
                   (assoc e :quantity q :unit unit :errors errors :ran-out? (= "1" (get-p "out"))
                          :ingredient_id (:ingredient_id line)))
          error (some #(when (and (not (:always? %)) (seq (:errors %)))
                         (str (:name (:ingredient %)) ": " (first (:errors %))))
                      parsed)]
      (cond
        (and (seq (str (:servings params))) (nil? servings))
        (r/h-respond (dialog recipe nil entry-id params "Servings must be a number above zero."))
        error
        (r/h-respond (dialog recipe servings entry-id params error))
        :else
        (let [{:keys [ran-out added-to-grocery]}
              (cooking/made! {:recipe-id (:id recipe) :entry-id entry-id
                              :servings (or servings (:servings recipe)) :user-id (:id user)
                              :items parsed :add-to-grocery? (= "1" (u/single (:add_to_grocery params)))})
              message (str "Made " (:title recipe) ". The pantry is updated"
                           (when (pos? (count ran-out))
                             (str "; " (count ran-out) " ran out"
                                  (when (pos? added-to-grocery) " and went on the grocery list")))
                           ".")]
          {:status 200
           :headers {"Content-Type" "text/html; charset=UTF-8"
                     "HX-Trigger" "pantry-changed, grocery-changed, recipe-cooked"}
           ;; The dialog closes (#modal gets nothing); the message goes to the page.
           :body (str (h/html [:div#recipe-message {:hx-swap-oob "innerHTML"} (ui/alert :success message)]))})))
    (auth/not-found)))
