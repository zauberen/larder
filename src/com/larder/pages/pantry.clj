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

(ns com.larder.pages.pantry
  "The pantry page: every row in stock, filters, in-place edits, add and bulk delete."
  (:require [clojure.string :as str]
            [com.larder.auth :as auth]
            [com.larder.ingredients :as ingredients]
            [com.larder.pages.ingredients :as ing-page]
            [com.larder.pages.layout :as layout]
            [com.larder.pantry :as pantry]
            [com.larder.response :as r]
            [com.larder.ui :as ui]
            [com.larder.units :as units]
            [com.larder.util :as u]
            [ring.util.codec :as codec]))

(def source-labels {"grocery" "Grocery" "manual" "Manual" "mcp" "Claude"})

(defn- filter-query
  "The current filters as a query string, for refreshing the table."
  [params]
  (str/join "&" (for [k [:q :source :from :to]
                      :let [v (get params k)]
                      :when (seq (str v))]
                  (str (name k) "=" (java.net.URLEncoder/encode (str v) "UTF-8")))))

(defn row
  "One pantry row. ERROR marks the amount as invalid."
  [item & [error]]
  (let [api (str "/api/pantry/" (:id item))
        edit {:hx-post api :hx-trigger "change" :hx-include "closest tr" :hx-target "closest tr" :hx-swap "outerHTML"}]
    [:tr {:id (str "pantry-" (:id item))}
     [:td.select-cell [:input {:type "checkbox" :name "ids" :value (:id item) :form "pantry-delete"
                               :aria-label (str "Select " (:ingredient_name item))}]]
     [:td
      [:button.link-button {:hx-get (str "/app/ingredients/" (:ingredient_id item) "/edit") :hx-target "#modal"
                            :title "Edit ingredient"}
       (:ingredient_name item)]
      (when (= 1 (:always_have item)) [:span.badge.always " Always have"])]
     [:td [:input.form-input.qty-input (merge edit {:name "quantity" :type "number" :step "any" :min 0
                                                    :value (let [q (:quantity item)] (if (string? q) q (units/input-qty q)))
                                                    :placeholder "?"
                                                    :aria-label (str "Quantity of " (:ingredient_name item))
                                                    :aria-invalid (when error "true") :title error})]]
     [:td (ui/select "unit" (ing-page/unit-options "—") (:unit item)
                     (merge edit {:class "unit-input" :aria-label (str "Unit of " (:ingredient_name item))}))]
     [:td.nowrap (u/format-date (:added_at item))]
     [:td [:span.badge.source (source-labels (:source item) (:source item))]]]))

(defn table
  [params]
  (let [rows (pantry/search params)
        filtered? (some #(seq (str (get params %))) [:q :source :from :to])]
    [:div#pantry-table {:hx-get (str "/app/pantry?" (filter-query params)) :hx-select "#pantry-table"
                        :hx-trigger "ingredients-changed from:body, pantry-changed from:body"
                        :hx-swap "outerHTML" :hx-disinherit "*"}
     (if (empty? rows)
       (ui/empty-state [:p.muted (if filtered? "Nothing in the pantry matches." "The pantry is empty.")])
       [:div.table-scroll
        [:table.table.pantry-table
         [:thead [:tr
                  [:th.select-cell [:input {:type "checkbox" :data-select-all "ids" :aria-label "Select all shown"}]]
                  [:th "Ingredient"] [:th "Quantity"] [:th "Unit"] [:th "Added"] [:th "Source"]]]
         [:tbody (for [item rows] (row item))]]])]))

(defn- add-form
  [& [values errors]]
  [:form#pantry-add.card.add-row {:hx-post "/api/pantry" :hx-target "#pantry-add" :hx-swap "outerHTML"}
   [:div.card-header [:h2 "Add to pantry"]]
   (ui/errors-alert errors)
   [:div.add-row-fields
    (ui/field "Ingredient" [:input.form-input {:name "ingredient" :list "ingredient-names" :required true
                                               :value (:ingredient values) :autocomplete "off"
                                               :maxlength 100}])
    (ui/field "Quantity" [:input.form-input {:name "quantity" :type "number" :step "any" :min 0
                                             :value (:quantity values)}])
    (ui/field "Unit" (ui/select "unit" (ing-page/unit-options "—") (:unit values)))
    [:button.btn.btn-primary {:type "submit"} "Add"]]
   [:span.form-hint "A name that isn't an ingredient yet creates one. Leave the quantity blank if you don't know it."]
   (into [:datalist#ingredient-names] (for [i (ingredients/all)] [:option {:value (:name i)}]))])

(defn pantry-page
  [req user]
  (let [params (:params req)]
    (layout/app-page
     req user
     (layout/main
      "pantry"
      [:div.page-top
       [:h1.dashboard-title "Pantry"]
       [:form#pantry-delete {:hx-post "/api/pantry/delete" :hx-target "#pantry-table" :hx-swap "outerHTML"}
        [:input {:type "hidden" :name "filters" :value (filter-query params)}]
        [:button.btn.btn-danger {:type "submit" :hidden true :data-selection-action "ids"
                                 :data-confirm-template "Delete {n} pantry {rows}?"}
         "Delete selected"]]]
      (add-form)
      [:form.list-filters {:hx-get "/app/pantry" :hx-trigger "input changed delay:300ms, change"
                           :hx-target "#pantry-table" :hx-select "#pantry-table" :hx-swap "outerHTML"
                           :hx-push-url "true" :role "search"}
       [:input.form-input {:type "search" :name "q" :value (:q params) :placeholder "Search ingredients"
                           :aria-label "Search ingredients"}]
       (ui/select "source" (cons ["" "Any source"] (map (juxt identity source-labels) pantry/sources))
                  (:source params) {:aria-label "Source"})
       [:label.date-filter [:span "Added from"]
        [:input.form-input {:type "date" :name "from" :value (:from params)}]]
       [:label.date-filter [:span "to"]
        [:input.form-input {:type "date" :name "to" :value (:to params)}]]]
      (table params)
      [:div#modal]))))

;;; API

(defn api-add
  [req user]
  (let [{:keys [ingredient quantity unit] :as params} (:params req)
        [q un amount-errors] (pantry/parse-amount quantity unit)
        name (str/trim (str ingredient))
        errors (cond-> amount-errors
                 (str/blank? name) (conj "Enter an ingredient.")
                 (> (count name) 100) (conj "Ingredient names can be up to 100 characters."))]
    (if (seq errors)
      (r/h-respond (add-form params errors))
      (let [[id created?] (ingredients/find-or-create! name un)]
        (pantry/add! {:ingredient_id id :quantity q :unit un :source "manual" :added_by (:id user)})
        (when created? (ingredients/rematch-unmatched!))
        (-> (r/h-respond (add-form))
            (assoc-in [:headers "HX-Trigger"] "pantry-changed"))))))

(defn api-update
  "In-place edit of a row's quantity and unit."
  [req _user id]
  (if-let [item (pantry/by-id (u/parse-int id))]
    (let [{:keys [quantity unit]} (:params req)
          [q un errors] (pantry/parse-amount (u/single quantity) (u/single unit))]
      (if (seq errors)
        (r/h-respond (row (assoc item :quantity (u/single quantity) :unit (u/single unit)) (first errors)))
        (do (pantry/set-amount! (:id item) q un)
            (r/h-respond (row (pantry/by-id (:id item)))))))
    (auth/not-found)))

(defn api-delete
  [req _user]
  (let [ids (->> (get-in req [:params :ids]) (#(if (sequential? %) % [%])) (keep u/parse-int) distinct)
        filters (codec/form-decode (str (get-in req [:params :filters])))
        params (if (map? filters) (update-keys filters keyword) {})]
    (pantry/delete! ids)
    (r/h-respond (table params))))
