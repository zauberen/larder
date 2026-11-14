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

(ns com.larder.pages.login
  (:require [com.larder.ui :as ui]
            [com.larder.response :as r]
            [com.larder.global-settings :as gs]))

(defn login-page
  "Returns the login page as a ring response. NEXT is where to go after logging in."
  [& [next]]
  (r/h-respond
   (ui/std-header
    (gs/site-name)
    (ui/link-stylesheet "/css/login.css")
    [:body
     [:div.login-container {:hx-get "/api/null" :hx-trigger "load" :hx-push-url (if next (str "/login?next=" (java.net.URLEncoder/encode (str next) "UTF-8")) "/login") :hx-swap "none"}
      [:h1.app-name (gs/site-name)]
      [:div#error-message.error-message "Username or password incorrect"]
      [:form#login-form {:hx-post "/api/login"
                         :hx-target "#login-form"
                         :hx-swap "outerHTML"
                         :hx-indicator "#login-button"}
       (when next [:input {:type "hidden" :name "next" :value next}])
       [:div.form-group
        [:label.form-label {:for "username"}
         "Username"]
        [:input#username.form-input {:type "text"
                                     :name "username"
                                     :placeholder "Enter your username"
                                     :required ""
                                     :autocomplete "username"}]]
       [:div.form-group
        [:label.form-label {:for "password"}
         "Password"]
        [:input#password.form-input {:type "password"
                                     :name "password"
                                     :placeholder "Enter your password"
                                     :required ""
                                     :autocomplete "current-password"}]]
       [:div.button-container
        [:a.back-button {:href "/"} "Back"]
        [:button#login-button.login-button {:type "submit"
                                            :hx-disable-elt "this"
                                            :hx-indicator "this"}
         [:span.button-text "Log In"]]]]]
     (ui/link-js-script "/js/login.js")])))

