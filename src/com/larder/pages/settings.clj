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

(ns com.larder.pages.settings
  "Settings: your account for everyone; user management and site settings for admins."
  (:require [clojure.string :as str]
            [com.larder.auth :as auth]
            [com.larder.global-settings :as gs]
            [com.larder.pages.connect :as connect]
            [com.larder.pages.grocery :as grocery-page]
            [com.larder.pages.layout :as layout]
            [com.larder.pages.server-config :as server-config]
            [com.larder.response :as r]
            [com.larder.ui :as ui]
            [com.larder.users :as users]
            [com.larder.util :as u]))

(def tab-defs
  "[key label admin-only?]"
  [["account" "My account" false]
   ["keys" "Claude" false]
   ["users" "Users" true]
   ["site" "Site" true]])

;;; Users

(defn- user-row
  [me user]
  (let [self? (= (:id me) (:id user))
        enabled? (= 1 (:enabled user))
        api (str "/api/users/" (:id user))
        target {:hx-target "#users-card" :hx-swap "outerHTML"}]
    [:tr {:id (str "user-" (:id user)) :class (when-not enabled? "disabled-row")}
     [:td [:strong (:username user)] (when self? [:span.muted " (you)"])]
     [:td (if self?
            [:span.badge (:role user)]
            (ui/select "role" (map (fn [role] [role role]) users/roles) (:role user)
                       (merge {:class "inline" :hx-post (str api "/role") :hx-trigger "change"} target)))]
     [:td (if enabled? [:span.badge.online "Enabled"] [:span.badge.banned "Disabled"])]
     [:td.actions
      [:button.btn.btn-secondary.small (merge {:hx-post (str api "/password")
                                               :data-prompt (str "New password for " (:username user) " (min 8 characters)")
                                               :data-prompt-type "password" :data-prompt-min-length 8}
                                              target)
       "Reset password"]
      (when-not self?
        (list
         " "
         (if enabled?
           [:button.btn.btn-secondary.small (merge {:hx-post (str api "/disable")
                                                    :hx-confirm (str "Disable " (:username user) "? They are signed out at once and can't log in until enabled again.")}
                                                   target)
            "Disable"]
           [:button.btn.btn-secondary.small (merge {:hx-post (str api "/enable")} target) "Enable"])
         " "
         [:button.btn.btn-danger.small (merge {:hx-post (str api "/delete")
                                               :hx-confirm (str "Remove " (:username user) "? This can't be undone.")}
                                              target)
          "Remove"]))]]))

(defn users-card
  [me & [message errors]]
  [:div#users-card
   [:section.card
    [:div.card-header [:h2 "Users"]]
    (ui/message-alert message)
    [:div.table-scroll
     [:table.table
      [:thead [:tr [:th "User"] [:th "Role"] [:th "Status"] [:th]]]
      [:tbody (for [user (users/all-users)] (user-row me user))]]]
    [:p.form-hint {:style "margin-top: 1rem"}
     "Everyone can manage recipes, the meal plan, the pantry and the grocery list. "
     "Admins can also manage users and site settings."]]
   [:section.card
    [:div.card-header [:h2 "Add user"]]
    (ui/errors-alert errors)
    [:form.form {:hx-post "/api/users" :hx-target "#users-card" :hx-swap "outerHTML"}
     [:div.form-row
      (ui/field "Username" [:input.form-input {:name "username" :required true :autocomplete "off"}])
      (ui/field "Password" [:input.form-input {:name "password" :type "password" :required true
                                                :minlength 8 :autocomplete "new-password"}])]
     (ui/field "Role" (ui/select "role" [["user" "User"] ["admin" "Admin"]] "user"))
     [:div.form-actions [:button.btn.btn-primary {:type "submit"} "Add user"]]]]])

(defn api-create-user
  [req me]
  (let [{:keys [username password role]} (:params req)
        username (some-> username str/trim)
        errors (users/validate-new username password role)]
    (if (seq errors)
      (r/h-respond (users-card me nil errors))
      (do (users/create! username password role)
          (r/h-respond (users-card me {:kind :success :text (str "Added " username ".")}))))))

(defn- user-action!
  "Apply ACTION to USER on behalf of ME. Returns the message to show."
  [req me user action]
  (let [self? (= (:id me) (:id user))
        role (get-in req [:params :role])]
    (cond
      (nil? user) {:kind :error :text "No such user."}

      (= "role" action)
      (cond self? {:kind :error :text "You can't change your own role."}
            (not (some #{role} users/roles)) {:kind :error :text "Unknown role."}
            (and (users/last-admin? user) (not= "admin" role)) {:kind :error :text "There must be at least one admin."}
            :else (do (users/set-role! (:id user) role)
                      {:kind :success :text (str (:username user) " is now " role ".")}))

      (= "password" action)
      (let [password (get-in req [:headers "hx-prompt"])]
        (if (< (count (or password "")) 8)
          {:kind :error :text "Passwords must be at least 8 characters."}
          (do (users/set-password! (:id user) password)
              {:kind :success :text (str "Password changed for " (:username user) ".")})))

      (= "disable" action)
      (cond self? {:kind :error :text "You can't disable yourself."}
            (users/last-admin? user) {:kind :error :text "There must be at least one admin."}
            :else (do (users/set-enabled! (:id user) false)
                      {:kind :success :text (str "Disabled " (:username user) ".")}))

      (= "enable" action)
      (do (users/set-enabled! (:id user) true)
          {:kind :success :text (str "Enabled " (:username user) ".")})

      (= "delete" action)
      (cond self? {:kind :error :text "You can't remove yourself."}
            (users/last-admin? user) {:kind :error :text "There must be at least one admin."}
            :else (do (users/delete! (:id user))
                      {:kind :success :text (str "Removed " (:username user) ".")}))

      :else {:kind :error :text "Unknown action."})))

(defn api-user-action
  "Role, password, disable, enable and delete actions on a user."
  [req me user-id action]
  (let [user (users/by-id (u/parse-int user-id))]
    (r/h-respond (users-card me (user-action! req me user action)))))

;;; Account

(defn account-card
  [me & [message]]
  [:section#account-card.card
   [:div.card-header [:h2 "Change password"]]
   (ui/message-alert message)
   [:form.form {:hx-post "/api/account/password" :hx-target "#account-card" :hx-swap "outerHTML"}
    (ui/field "Current password" [:input.form-input {:name "current" :type "password" :required true
                                                      :autocomplete "current-password"}])
    (ui/field "New password" [:input.form-input {:name "password" :type "password" :required true
                                                  :minlength 8 :autocomplete "new-password"}])
    (ui/field "Confirm new password" [:input.form-input {:name "confirm" :type "password" :required true
                                                          :minlength 8 :autocomplete "new-password"}])
    [:div.form-actions [:button.btn.btn-primary {:type "submit"} "Change password"]]]
   [:p.form-hint {:style "margin-top: 1rem"}
    "Signed in as " [:strong (:username me)] " (" (:role me) ")."]])

(defn api-change-password
  [req me]
  (let [{:keys [current password confirm]} (:params req)
        message (cond
                  (not (users/check-password (:username me) current)) {:kind :error :text "Your current password is wrong."}
                  (< (count (or password "")) 8) {:kind :error :text "Passwords must be at least 8 characters."}
                  (not= password confirm) {:kind :error :text "The new passwords don't match."}
                  :else (do (users/set-password! (:id me) password)
                            {:kind :success :text "Password changed."}))]
    (r/h-respond (account-card me message))))

;;; Site

(defn site-card
  [& [message values]]
  (let [settings (merge (gs/all-settings) values)]
    [:section#site-card.card
     [:div.card-header [:h2 "Site"]]
     (ui/message-alert message)
     [:form.form {:hx-post "/api/site/settings" :hx-target "#site-card" :hx-swap "outerHTML"}
      (ui/field "Site name"
                [:input.form-input {:name "site_name" :required true :maxlength 60
                                    :value (get settings "site_name")}]
                "Shown in the header and the browser tab.")
      (ui/field "Public base URL"
                [:input.form-input {:name "public_base_url" :type "url"
                                    :value (get settings "public_base_url")
                                    :placeholder "https://larder.example.com"}]
                "The address people and Claude use to reach Larder, used in share links and sign-in metadata. Leave blank to use the address each page was opened with.")
      [:div.form-actions [:button.btn.btn-primary {:type "submit"} "Save"]]]]))

(defn api-save-site
  [req _me]
  (let [{:keys [site_name public_base_url]} (:params req)
        site-name (str/trim (or site_name ""))
        base-url (str/trim (or public_base_url ""))
        typed {"site_name" site_name "public_base_url" public_base_url}]
    (cond
      (not (<= 1 (count site-name) 60))
      (r/h-respond (site-card {:kind :error :text "Give the site a name of up to 60 characters."} typed))
      (and (seq base-url) (not (gs/valid-base-url? base-url)))
      (r/h-respond (site-card {:kind :error :text "The public base URL should look like https://larder.example.com."} typed))
      :else
      (do (gs/put! "site_name" site-name)
          (gs/put! "public_base_url" (str/replace base-url #"/+$" ""))
          (r/h-respond (site-card {:kind :success :text "Saved."}))))))

;;; Page

(defn settings-page
  [req me tab]
  (let [visible (filter (fn [[_ _ admin-only]] (or (not admin-only) (auth/admin? me))) tab-defs)
        tab (or tab "account")]
    (if-not (some #(= tab (first %)) visible)
      (auth/not-found)
      (layout/app-page
       req me
       (layout/main
        nil
        [:h1.dashboard-title "Settings"]
        (ui/tabs tab (for [[k label] visible] [k label (str "/app/settings/" k)]))
        [:div.tab-body.settings-layout
         (case tab
           "account" (account-card me)
           "keys" (list (connect/keys-card req me) (connect/connections-card req me))
           "users" (users-card me)
           "site" (list (site-card) (grocery-page/access-card req) (connect/mcp-card) (server-config/config-card)))])))))
