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

(ns com.larder.pages.ingredients
  "The ingredient edit dialog (name, how it's measured, density, default purchase size,
  always-have, other names), opened from an ingredient's name in the pantry."
  (:require [clojure.string :as str]
            [com.larder.auth :as auth]
            [com.larder.ingredients :as ingredients]
            [com.larder.response :as r]
            [com.larder.ui :as ui]
            [com.larder.units :as units]
            [com.larder.util :as u]))

(def dimension-options
  [["mass" "By weight (g, kg, oz, lb)"] ["volume" "By volume (ml, cups, spoons)"] ["count" "By count (each, cans, cloves)"]])

(defn unit-options
  "Unit select options with a blank first entry."
  [blank-label]
  (cons ["" blank-label] (map (fn [x] [x x]) units/all-units)))

(defn dialog
  "The edit dialog for ingredient ING. VALUES are typed values to show after errors."
  [ing & [values errors]]
  (let [v (or values
              {:name (:name ing) :dimension (:dimension ing)
               :density (some-> (:density ing) str)
               :default_quantity (units/input-qty (:default_quantity ing))
               :default_unit (:default_unit ing)
               :always_have (= 1 (:always_have ing))
               :aliases (str/join ", " (ingredients/aliases (:id ing)))})]
    [:dialog.bs-dialog.wide-dialog {:data-modal true :aria-labelledby "ingredient-dialog-title"}
     [:form.form {:hx-post (str "/api/ingredients/" (:id ing)) :hx-target "#modal" :hx-swap "innerHTML"}
      [:h2#ingredient-dialog-title (str "Edit " (:name ing))]
      (ui/errors-alert errors)
      (ui/field "Name" [:input.form-input {:name "name" :value (:name v) :required true :maxlength 100}])
      (ui/field "Measured" (ui/select "dimension" dimension-options (:dimension v)))
      (ui/field "Density (grams per millilitre)"
                [:input.form-input {:name "density" :type "number" :step "any" :min 0 :value (:density v)
                                    :placeholder "e.g. 0.53 for flour"}]
                "Lets weights and volumes of this ingredient convert to each other. Leave blank if unknown.")
      [:div.form-field
       [:span.form-label "Default purchase size"]
       [:div.inline-form
        [:input.form-input {:name "default_quantity" :type "number" :step "any" :min 0 :value (:default_quantity v)
                            :aria-label "Default purchase quantity"}]
        (ui/select "default_unit" (unit-options "unit") (:default_unit v) {:aria-label "Default purchase unit"})]
       [:span.form-hint "The usual size you buy, used when adding it to the grocery list."]]
      [:label.checkbox
       [:input {:type "checkbox" :name "always_have" :value "1" :checked (boolean (:always_have v))}]
       [:span "Always have — count it as in stock and never deduct it (salt, water, oil)."]]
      (ui/field "Other names" [:input.form-input {:name "aliases" :value (:aliases v)}]
                "Comma separated. Recipe lines with these names match this ingredient.")
      [:div.form-actions
       [:button.btn.btn-secondary {:type "button" :data-close-modal true} "Cancel"]
       [:button.btn.btn-primary {:type "submit"} "Save"]]]]))

(defn dialog-page
  "GET /app/ingredients/:id/edit: the dialog, swapped into #modal."
  [_req _user id]
  (if-let [ing (ingredients/by-id (u/parse-int id))]
    (r/h-respond (dialog ing))
    (auth/not-found)))

(defn- parse
  [params]
  (let [num (fn [k] (let [s (str/trim (str (get params k "")))]
                      (when (seq s) (parse-double (str/replace s "," ".")))))
        density (num :density)
        dq (num :default_quantity)
        du (some-> (:default_unit params) str/trim not-empty units/normalize)
        aliases (->> (str/split (str (:aliases params)) #",") (map str/trim) (remove str/blank?) distinct vec)
        typed-bad? (fn [k] (and (seq (str/trim (str (get params k "")))) (nil? (num k))))]
    [{:name (str/trim (str (:name params)))
      :dimension (:dimension params)
      :density density
      :default_quantity dq
      :default_unit du
      :always_have (= "1" (:always_have params))
      :aliases aliases}
     (cond-> []
       (typed-bad? :density) (conj "The density must be a number.")
       (typed-bad? :default_quantity) (conj "The default purchase quantity must be a number.")
       (and dq (not (pos? dq))) (conj "The default purchase quantity must be above zero.")
       (and dq (nil? du)) (conj "Choose a unit for the default purchase size.")
       (> (count aliases) 50) (conj "Up to 50 other names."))]))

(defn api-save
  "Save the dialog. On success the dialog closes and listeners of ingredients-changed refresh."
  [req _user id]
  (if-let [ing (ingredients/by-id (u/parse-int id))]
    (let [params (:params req)
          [values errors] (parse params)
          errors (into errors (concat (ingredients/validate values (:id ing))
                                      (ingredients/alias-errors (:id ing) (:aliases values))))]
      (if (seq errors)
        (r/h-respond (dialog ing (assoc params :always_have (:always_have values)) errors))
        (do (ingredients/update! (:id ing) values)
            (ingredients/set-aliases! (:id ing) (:aliases values))
            (ingredients/rematch-unmatched!)
            {:status 200 :body ""
             :headers {"Content-Type" "text/html; charset=UTF-8" "HX-Trigger" "ingredients-changed"}})))
    (auth/not-found)))
