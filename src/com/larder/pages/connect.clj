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

(ns com.larder.pages.connect
  "Settings for connecting Claude: your API keys, and for admins the MCP sign-in
  switches and every active key."
  (:require [clojure.string :as str]
            [com.larder.api-keys :as api-keys]
            [com.larder.global-settings :as gs]
            [com.larder.oauth :as oauth]
            [com.larder.pages.layout :as layout]
            [com.larder.response :as r]
            [com.larder.ui :as ui]
            [com.larder.util :as u]))

(defn mcp-url [req] (str (layout/site-url req) "/mcp"))

(defn- key-table
  [rows & [{:keys [show-user? revoke-url]}]]
  (if (empty? rows)
    [:p.muted "No active keys."]
    [:div.table-scroll
     [:table.table
      [:thead [:tr [:th "Name"] (when show-user? [:th "User"]) [:th "Key"] [:th "Created"] [:th "Last used"] [:th]]]
      [:tbody
       (for [k rows]
         [:tr
          [:td (:name k)]
          (when show-user? [:td (:username k)])
          [:td [:code (str "lk_" (:key_id k) "_…")]]
          [:td.nowrap (u/format-date (:created_at k))]
          [:td.nowrap (if (:last_used_at k) (u/format-ts (:last_used_at k)) "never")]
          [:td.actions [:button.btn.btn-danger.small {:hx-post (revoke-url (:id k)) :hx-target "closest section" :hx-swap "outerHTML"
                                                      :hx-confirm (str "Revoke " (:name k) "? Anything using it loses access at once.")}
                        "Revoke"]]])]]]))

(defn keys-card
  "Your API keys. NEW-KEY is shown once, right after it is created."
  [req me & [{:keys [new-key message errors]}]]
  [:section#keys-card.card
   [:div.card-header [:h2 "API keys"]]
   [:p.muted "An API key lets Claude use Larder as you, through the MCP server at " [:code (mcp-url req)] ". "
    "Keep keys secret; revoke one if it leaks."]
   (when-not (gs/enabled? "mcp_api_keys")
     (ui/alert :info "An admin has turned API key sign-in off, so keys don't work at the moment."))
   (ui/message-alert message)
   (ui/errors-alert errors)
   (when new-key
     [:div.new-key
      (ui/alert :success "Here is your new key. Copy it now: it won't be shown again.")
      (ui/copy-field new-key)
      [:h3 "Claude Code"]
      (ui/copy-field (str "claude mcp add --transport http larder " (mcp-url req)
                          " --header \"Authorization: Bearer " new-key "\""))
      [:h3 "Claude.ai and the desktop app"]
      [:p.form-hint "Add a custom connector with the URL " [:code (mcp-url req)]
       " and a request header " [:code "Authorization"] " set to " [:code (str "Bearer " new-key)]
       ". These connect from Anthropic's cloud, so Larder must be reachable over public HTTPS."]])
   (key-table (api-keys/for-user (:id me)) {:revoke-url #(str "/api/keys/" % "/revoke")})
   [:form.inline-form {:hx-post "/api/keys" :hx-target "#keys-card" :hx-swap "outerHTML" :style "margin-top: 1rem"}
    [:input.form-input {:name "name" :required true :maxlength 60 :placeholder "Key name, e.g. Laptop" :aria-label "Key name"}]
    [:button.btn.btn-primary {:type "submit"} "Create key"]]])

(defn api-create-key
  [req me]
  (let [name (str/trim (str (get-in req [:params :name])))
        errors (api-keys/validate-name name)]
    (if (seq errors)
      (r/h-respond (keys-card req me {:errors errors}))
      (r/h-respond (keys-card req me {:new-key (api-keys/create! (:id me) name)})))))

(defn api-revoke-own
  [req me id]
  (let [ok (api-keys/revoke! (u/parse-int id) (:id me))]
    (r/h-respond (keys-card req me {:message (if ok {:kind :success :text "Key revoked."} {:kind :error :text "No such key."})}))))

;;; OAuth connections

(defn- connection-table
  [rows revoke-url & [show-user?]]
  (if (empty? rows)
    [:p.muted "No connected apps."]
    [:div.table-scroll
     [:table.table
      [:thead [:tr [:th "App"] (when show-user? [:th "User"]) [:th "Connected"] [:th "Last used"] [:th]]]
      [:tbody
       (for [c rows]
         [:tr
          [:td (:client_name c)]
          (when show-user? [:td (:username c)])
          [:td.nowrap (u/format-date (:created_at c))]
          [:td.nowrap (u/format-ts (:last_used_at c))]
          [:td.actions
           [:form {:hx-post (revoke-url c) :hx-target "closest section" :hx-swap "outerHTML"
                   :hx-confirm (str "Disconnect " (:client_name c) "? It will have to sign in again.")}
            [:input {:type "hidden" :name "client_id" :value (:client_id c)}]
            [:input {:type "hidden" :name "user_id" :value (:user_id c)}]
            [:button.btn.btn-danger.small {:type "submit"} "Disconnect"]]]])]]]))

(defn connections-card
  [req me & [message]]
  [:section#connections-card.card
   [:div.card-header [:h2 "Connected apps"]]
   [:p.muted "Apps you signed in to with your Larder login, such as Claude through its connector or Claude Code's "
    [:code "/mcp"] " command. Add Larder to them with the server URL " [:code (mcp-url req)] "."]
   (when-not (gs/enabled? "mcp_oauth")
     (ui/alert :info "An admin has turned OAuth sign-in off, so connected apps don't work at the moment."))
   (ui/message-alert message)
   (connection-table (oauth/connections (:id me)) (constantly "/api/connections/revoke"))])

(defn api-revoke-own-connection
  [req me]
  (oauth/revoke-connection! (str (get-in req [:params :client_id])) (:id me))
  (r/h-respond (connections-card req me {:kind :success :text "Disconnected."})))

;;; Admin

(defn mcp-card
  [& [message]]
  [:section#mcp-card.card
   [:div.card-header [:h2 "Claude (MCP) sign-in"]]
   (ui/message-alert message)
   [:form.form {:hx-post "/api/site/mcp" :hx-target "#mcp-card" :hx-swap "outerHTML" :hx-trigger "change"}
    [:input {:type "hidden" :name "submitted" :value "1"}]
    [:label.checkbox [:input {:type "checkbox" :name "mcp_api_keys" :value "1" :checked (gs/enabled? "mcp_api_keys")}]
     [:span "API keys — users create keys under Settings → Claude."]]
    [:label.checkbox [:input {:type "checkbox" :name "mcp_oauth" :value "1" :checked (gs/enabled? "mcp_oauth")}]
     [:span "OAuth — users sign in with their Larder login from Claude."]]]
   [:h3.subhead "Active API keys"]
   (key-table (api-keys/all-active) {:show-user? true :revoke-url #(str "/api/site/keys/" % "/revoke")})
   [:h3.subhead "Connected OAuth apps"]
   (connection-table (oauth/connections) (constantly "/api/site/connections/revoke") true)])

(defn api-save-mcp
  [req _me]
  (let [p (:params req)]
    (gs/put! "mcp_api_keys" (if (= "1" (:mcp_api_keys p)) "1" "0"))
    (gs/put! "mcp_oauth" (if (= "1" (:mcp_oauth p)) "1" "0"))
    (r/h-respond (mcp-card {:kind :success :text "Saved."}))))

(defn api-revoke-any
  [_req _me id]
  (let [ok (api-keys/revoke! (u/parse-int id))]
    (r/h-respond (mcp-card (if ok {:kind :success :text "Key revoked."} {:kind :error :text "No such key."})))))

(defn api-revoke-any-connection
  [req _me]
  (oauth/revoke-connection! (str (get-in req [:params :client_id])) (u/parse-int (get-in req [:params :user_id])))
  (r/h-respond (mcp-card {:kind :success :text "Disconnected."})))
