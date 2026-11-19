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

(ns com.larder.pages.grocery
  "The grocery list: the logged-in page, the public tab and the share link. One list
  renderer and one completion handler serve all three; CTX says which one is showing:
  {:app? bool :token share-token-or-nil}."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [com.larder.auth :as auth]
            [com.larder.global-settings :as gs]
            [com.larder.grocery :as grocery]
            [com.larder.ingredients :as ingredients]
            [com.larder.pages.ingredients :as ing-page]
            [com.larder.pages.layout :as layout]
            [com.larder.pantry :as pantry]
            [com.larder.ratelimit :as rl]
            [com.larder.response :as r]
            [com.larder.stores :as stores]
            [com.larder.ui :as ui]
            [com.larder.units :as units]
            [com.larder.util :as u]))

;;; Access

(defn access
  "How REQ may use the grocery list with share TOKEN: {:user u} when signed in,
  {:public? true} when the access setting lets visitors in, else nil."
  [req token]
  (if-let [user (auth/current-user req)]
    {:user user}
    (case (gs/grocery-access)
      "open" {:public? true}
      "share" (when (gs/valid-share-token? token) {:public? true})
      nil)))

(defn- list-url
  [ctx]
  (cond (:app? ctx) "/app/grocery"
        (:token ctx) (str "/g/" (:token ctx))
        :else "/grocery"))

(defn- token-vals
  "hx-vals carrying the share token, if any."
  [ctx]
  (when-let [t (:token ctx)] {:hx-vals (json/write-str {:t t})}))

;;; List

(defn- check-button
  [ctx item]
  (let [url (str "/api/grocery/items/" (:id item) "/complete")
        label (str "Got " (grocery/label item))]
    (if (= "grocery" (:section item))
      [:button.check-button (merge {:hx-get (str url (when-let [t (:token ctx)] (str "?t=" (java.net.URLEncoder/encode t "UTF-8"))))
                                    :hx-target "#modal" :aria-label label :title label})]
      [:button.check-button (merge {:hx-post url :hx-target "#modal" :aria-label label :title label}
                                   (token-vals ctx))])))

(defn- item-row
  [ctx store-options item]
  [:li.grocery-item {:id (str "grocery-" (:id item))}
   (check-button ctx item)
   [:div.grocery-item-main
    [:span.grocery-item-name (grocery/label item)]
    (when-let [a (grocery/amount-text (:quantity item) (:unit item))] [:span.grocery-amount a])
    (when (and (:app? ctx) (not= "manual" (:origin item)))
      [:span.badge.origin (case (:origin item) "recipe" "recipe" "mealplan" "meal plan" "mcp" "Claude" (:origin item))])]
   (when (:app? ctx)
     [:div.grocery-item-actions
      (ui/select "store_id" store-options (:store_id item)
                 {:class "store-select" :hx-post (str "/api/grocery/items/" (:id item) "/store") :hx-trigger "change"
                  :hx-swap "none" :aria-label (str "Store for " (grocery/label item))})
      [:button.icon-button {:hx-post (str "/api/grocery/items/" (:id item) "/delete") :hx-swap "none"
                            :aria-label (str "Remove " (grocery/label item)) :title "Remove"} "×"]])])

(defn- section-block
  [ctx store-options title section items]
  (let [items (filter #(= section (:section %)) items)
        groups (partition-by :store_name items)]
    [:section.grocery-section
     [:h2 title [:span.count (count items)]]
     (if (empty? items)
       [:p.muted.empty-line "Nothing to buy."]
       (for [group groups]
         [:div.store-group
          (when (or (:store_name (first group)) (> (count groups) 1))
            [:h3.store-name (or (:store_name (first group)) "Any store")])
          (into [:ul.grocery-items] (map #(item-row ctx store-options %) group))]))]))

(defn- done-block
  [ctx done]
  (when (seq done)
    [:details.completed
     [:summary (str "Completed (" (count done) ")")]
     (into [:ul.grocery-items.done]
           (for [item done]
             [:li.grocery-item.done
              [:span.check-button.checked {:aria-hidden "true"}]
              [:div.grocery-item-main
               [:span.grocery-item-name (grocery/label item)]
               (when (= "grocery" (:section item))
                 [:span.grocery-amount (or (grocery/amount-text (:purchased_quantity item) (:purchased_unit item))
                                           "amount unknown")])]
              [:button.btn.btn-secondary.small (merge {:hx-post (str "/api/grocery/items/" (:id item) "/undo")
                                                       :hx-target "#modal"}
                                                      (token-vals ctx))
               "Undo"]]))
     (when (:app? ctx)
       [:button.btn.btn-secondary.small.clear-done {:hx-post "/api/grocery/clear-completed" :hx-target "#modal"
                                                    :hx-confirm "Clear every completed item? They can't be undone afterwards."}
        "Clear completed"])]))

(defn grocery-list
  [ctx]
  (let [items (grocery/open-items)
        done (grocery/done-items)
        store-options (cons ["" "Any store"] (map (juxt :id :name) (stores/all)))]
    [:div#grocery-list {:hx-get (list-url ctx) :hx-select "#grocery-list" :hx-swap "outerHTML"
                        :hx-trigger "grocery-changed from:body, every 30s" :hx-disinherit "*"}
     [:div.grocery-columns
      (section-block ctx store-options "Grocery" "grocery" items)
      (section-block ctx store-options "Misc" "misc" items)]
     (done-block ctx done)]))

;;; Size dialog

(defn size-dialog
  [ctx item & [values error]]
  (let [[q unit] (grocery/purchase-default item)
        q (if values (:quantity values) (units/input-qty q))
        unit (if values (:unit values) unit)
        url (str "/api/grocery/items/" (:id item) "/complete")]
    [:dialog.bs-dialog {:data-modal true :aria-labelledby "size-dialog-title"}
     [:form.form (merge {:hx-post url :hx-target "#modal"} (token-vals ctx))
      [:h2#size-dialog-title (str "How much " (grocery/label item) " did you get?")]
      [:p.muted "This goes into the pantry with today's date."]
      (when error (ui/alert :error error))
      [:div.inline-form
       [:input.form-input {:type "number" :name "quantity" :step "any" :min 0 :value q :autofocus true
                           :aria-label "Quantity"}]
       (ui/select "unit" (ing-page/unit-options "—") unit {:aria-label "Unit" :class "unit-input"})]
      [:div.form-actions.size-actions
       [:button.btn.btn-secondary {:type "button" :data-close-modal true} "Cancel"]
       [:button.btn.btn-secondary {:type "submit" :name "skip" :value "1" :formnovalidate true} "Skip amount"]
       [:button.btn.btn-primary {:type "submit" :name "confirm" :value "1"} "Confirm"]]]]))

(defn- changed
  "Close the dialog and refresh the lists."
  [& [extra-trigger]]
  {:status 200 :body ""
   :headers {"Content-Type" "text/html; charset=UTF-8"
             "HX-Trigger" (str/join ", " (remove nil? ["grocery-changed" extra-trigger]))}})

(defn- token-param [req] (u/single (get-in req [:params :t])))

(defn- with-access
  "Call (F access ctx) when REQ may use the grocery list. Visitors' writes are rate limited."
  [req write? f]
  (let [token (token-param req)
        acc (access req token)
        ctx {:app? false :token (when-not (:user acc) token)}]
    (cond
      (nil? acc) (auth/not-found)
      (and write? (:public? acc) (rl/limited? :grocery-public (rl/client-address req))) (rl/too-many-requests)
      :else (do (when (and write? (:public? acc)) (rl/record! :grocery-public (rl/client-address req)))
                (f acc ctx)))))

(defn api-dialog
  "GET the size dialog for a grocery item."
  [req id]
  (with-access req false
    (fn [_acc ctx]
      (let [item (grocery/by-id (u/parse-int id))]
        (if (and item (nil? (:done_at item)) (= "grocery" (:section item)))
          (r/h-respond (size-dialog ctx item))
          (changed))))))

(defn api-complete
  "Complete an item: grocery items with the size dialog's amount (or none with Skip
  amount), misc items straight away."
  [req id]
  (with-access req true
    (fn [acc ctx]
      (let [item (grocery/by-id (u/parse-int id))
            params (:params req)
            skip? (= "1" (u/single (:skip params)))]
        (cond
          (or (nil? item) (:done_at item)) (changed)
          (or (= "misc" (:section item)) skip?)
          (do (grocery/complete! item nil nil (:id (:user acc))) (changed "pantry-changed"))
          :else
          (let [[q unit errors] (pantry/parse-amount (u/single (:quantity params)) (u/single (:unit params)))]
            (if (seq errors)
              (r/h-respond (size-dialog ctx item {:quantity (u/single (:quantity params)) :unit (u/single (:unit params))}
                                        (first errors)))
              (do (grocery/complete! item q unit (:id (:user acc))) (changed "pantry-changed")))))))))

(defn api-undo
  [req id]
  (with-access req true
    (fn [_acc _ctx]
      (when-let [item (grocery/by-id (u/parse-int id))]
        (when (:done_at item) (grocery/undo! item)))
      (changed "pantry-changed"))))

;;; Pages

(def ^:private add-store-input
  (fn [value]
    [:input.form-input {:name "store" :list "store-names" :value value :placeholder "optional" :maxlength 60
                        :autocomplete "off" :aria-label "Store"}]))

(defn- add-forms
  [& [{:keys [section values errors]}]]
  [:div#grocery-add.grocery-add
   [:form.card.add-row {:hx-post "/api/grocery" :hx-target "#grocery-add" :hx-swap "outerHTML"}
    [:input {:type "hidden" :name "section" :value "grocery"}]
    [:div.card-header [:h2 "Add groceries"]]
    (when (= "grocery" section) (ui/errors-alert errors))
    [:div.add-row-fields.grocery-fields
     (ui/field "Ingredient" [:input.form-input {:name "ingredient" :list "ingredient-names" :required true :maxlength 100
                                                :autocomplete "off" :value (when (= "grocery" section) (:ingredient values))}])
     (ui/field "Amount" [:input.form-input {:name "quantity" :type "number" :step "any" :min 0
                                            :value (when (= "grocery" section) (:quantity values))}])
     (ui/field "Unit" (ui/select "unit" (ing-page/unit-options "—") (when (= "grocery" section) (:unit values))))
     (ui/field "Store" (add-store-input (when (= "grocery" section) (:store values))))
     [:button.btn.btn-primary {:type "submit"} "Add"]]
    [:span.form-hint "Goes into the pantry when you check it off. Leave the amount blank to use the usual size."]]
   [:form.card.add-row {:hx-post "/api/grocery" :hx-target "#grocery-add" :hx-swap "outerHTML"}
    [:input {:type "hidden" :name "section" :value "misc"}]
    [:div.card-header [:h2 "Add something else"]]
    (when (= "misc" section) (ui/errors-alert errors))
    [:div.add-row-fields.misc-fields
     (ui/field "Item" [:input.form-input {:name "text" :required true :maxlength 200 :placeholder "Paper towels"
                                          :value (when (= "misc" section) (:text values))}])
     (ui/field "Store" (add-store-input (when (= "misc" section) (:store values))))
     [:button.btn.btn-primary {:type "submit"} "Add"]]
    [:span.form-hint "Not tracked in the pantry."]]
   (into [:datalist#ingredient-names] (for [i (ingredients/all)] [:option {:value (:name i)}]))
   (into [:datalist#store-names] (for [s (stores/all)] [:option {:value (:name s)}]))])

(defn stores-card
  [& [message]]
  [:section#stores-card.card.stores-card {:hx-get "/app/grocery" :hx-select "#stores-card" :hx-swap "outerHTML"
                                          :hx-trigger "grocery-changed from:body" :hx-disinherit "*"}
   [:div.card-header [:h2 "Stores"]]
   (ui/message-alert message)
   (let [all (stores/all)]
     (if (empty? all)
       [:p.muted "No stores yet. Typing a store on an item adds it."]
       (into [:ul.store-list]
             (for [s all]
               [:li
                [:div [:strong (:name s)] (when (:notes s) [:span.muted (str " — " (:notes s))])]
                [:button.icon-button {:hx-post (str "/api/stores/" (:id s) "/delete") :hx-target "#stores-card"
                                      :hx-swap "outerHTML" :hx-confirm (str "Remove " (:name s) "? Its items keep their place on the list without a store.")
                                      :aria-label (str "Remove " (:name s)) :title "Remove"} "×"]]))))
   [:form.inline-form {:hx-post "/api/stores" :hx-target "#stores-card" :hx-swap "outerHTML"}
    [:input.form-input {:name "name" :required true :maxlength 60 :placeholder "Store name" :aria-label "Store name"}]
    [:input.form-input {:name "notes" :maxlength 500 :placeholder "Notes (optional)" :aria-label "Notes"}]
    [:button.btn.btn-secondary.small {:type "submit"} "Add store"]]])

(defn- share-link
  "The share link to copy, while the access setting lets it work."
  [req]
  (when (#{"open" "share"} (gs/grocery-access))
    [:div.grocery-share
     [:span.form-label "Share link"]
     (ui/copy-field (str (layout/site-url req) "/g/" (gs/share-token)))]))

(defn grocery-page
  [req user]
  (layout/app-page
   req user
   (layout/main
    "grocery"
    [:div.page-top.grocery-top
     [:h1.dashboard-title "Grocery"]
     (share-link req)]
    (add-forms)
    (grocery-list {:app? true})
    (stores-card)
    [:div#modal])))

(defn public-body
  "The grocery list for visitors (public tab and share link)."
  [ctx]
  (list [:h1.dashboard-title "Grocery list"]
        (grocery-list ctx)
        [:div#modal]))

;;; Logged-in API

(defn api-add
  [req user]
  (let [{:keys [section ingredient text quantity unit store] :as params} (:params req)
        store-errors (if (> (count (str/trim (str store))) 60) ["Store names can be up to 60 characters."] [])
        redisplay (fn [errors] (r/h-respond (add-forms {:section section :values params :errors errors})))]
    (case section
      "grocery"
      (let [[q un errors] (pantry/parse-amount quantity unit)
            name (str/trim (str ingredient))
            errors (cond-> (into errors store-errors)
                     (str/blank? name) (conj "Enter an ingredient.")
                     (> (count name) 100) (conj "Ingredient names can be up to 100 characters."))]
        (if (seq errors)
          (redisplay errors)
          (let [[ing-id created?] (ingredients/find-or-create! name un)]
            (when created? (ingredients/rematch-unmatched!))
            (grocery/add-ingredient! {:ingredient_id ing-id :quantity q :unit un
                                      :store_id (stores/find-or-create! store) :origin "manual" :added_by (:id user)})
            (-> (r/h-respond (add-forms)) (assoc-in [:headers "HX-Trigger"] "grocery-changed")))))
      "misc"
      (let [t (str/trim (str text))
            errors (cond-> store-errors
                     (str/blank? t) (conj "Enter an item.")
                     (> (count t) 200) (conj "Items can be up to 200 characters."))]
        (if (seq errors)
          (redisplay errors)
          (do (grocery/add-misc! {:text t :store_id (stores/find-or-create! store) :added_by (:id user)})
              (-> (r/h-respond (add-forms)) (assoc-in [:headers "HX-Trigger"] "grocery-changed")))))
      (redisplay ["Unknown section."]))))

(defn api-set-store
  [req _user id]
  (let [store-id (u/parse-int (get-in req [:params :store_id]))]
    (if (grocery/by-id (u/parse-int id))
      (do (grocery/set-store! (u/parse-int id) (when (and store-id (stores/by-id store-id)) store-id))
          (changed))
      (auth/not-found))))

(defn api-delete
  [_req _user id]
  (grocery/delete! (u/parse-int id))
  (changed))

(defn api-clear-completed
  [_req _user]
  (grocery/clear-done!)
  (changed))

(defn api-add-store
  [req _user]
  (let [{:keys [name notes]} (:params req)
        errors (stores/validate (str/trim (str name)) notes)]
    (if (seq errors)
      (r/h-respond (stores-card {:kind :error :text (str/join " " errors)}))
      (do (stores/create! name notes)
          (-> (r/h-respond (stores-card)) (assoc-in [:headers "HX-Trigger"] "grocery-changed"))))))

(defn api-delete-store
  [_req _user id]
  (stores/delete! (u/parse-int id))
  (-> (r/h-respond (stores-card)) (assoc-in [:headers "HX-Trigger"] "grocery-changed")))

;;; Site settings card

(defn access-card
  [req & [message]]
  (let [level (gs/grocery-access)
        link (str (layout/site-url req) "/g/" (gs/share-token))]
    [:section#grocery-access-card.card
     [:div.card-header [:h2 "Grocery list access"]]
     (ui/message-alert message)
     [:form.form {:hx-post "/api/site/grocery-access" :hx-target "#grocery-access-card" :hx-swap "outerHTML"
                  :hx-trigger "change"}
      (for [[v label hint] [["open" "Open" "Anyone who visits the site can see and check off the list. It gets a tab on the public dashboard."]
                            ["share" "Share link" "Anyone with the link below can see and check off the list."]
                            ["login" "Login required" "Only signed-in users."]]]
        [:label.checkbox.radio-option
         [:input {:type "radio" :name "grocery_access" :value v :checked (= v level)}]
         [:span [:strong label] [:span.form-hint.block hint]]])]
     [:div.form-field {:style "margin-top: 1rem"}
      [:span.form-label "Share link"]
      (ui/copy-field link)
      [:span.form-hint "Works while access is Open or Share link. Adding items always needs a login."]]
     [:div.form-actions
      [:button.btn.btn-danger.small {:hx-post "/api/site/grocery-link" :hx-target "#grocery-access-card" :hx-swap "outerHTML"
                                     :hx-confirm "Make a new share link? The old one stops working."}
       "Regenerate link"]]]))

(defn api-save-access
  [req _me]
  (let [v (get-in req [:params :grocery_access])]
    (if (some #{v} gs/grocery-access-levels)
      (do (gs/put! "grocery_access" v)
          (r/h-respond (access-card req {:kind :success :text "Saved."})))
      (r/h-respond (access-card req {:kind :error :text "Unknown access level."})))))

(defn api-regenerate-link
  [req _me]
  (gs/regenerate-share-token!)
  (r/h-respond (access-card req {:kind :success :text "New link made. The old one no longer works."})))
