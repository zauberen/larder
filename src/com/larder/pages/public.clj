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

(ns com.larder.pages.public
  "The public dashboard: published recipes (and, depending on the grocery access
  setting, the grocery list). No login required."
  (:require [com.larder.auth :as auth]
            [com.larder.global-settings :as gs]
            [com.larder.response :as r]
            [com.larder.ui :as ui]))

(defn public-tabs
  "The header tabs. TABS is a seq of [key label href]; ACTIVE is the selected key."
  [active tabs]
  (into [:nav.public-tabs]
        (for [[k label href] tabs]
          [:a.public-tab {:href href :class (when (= k active) "active")} label])))

(defn visible-tabs
  "Tabs shown on the public dashboard. The Grocery list tab joins when the grocery list
  is open to everyone (phase 6)."
  []
  [["recipes" "Recipes" "/"]])

(defn public-shell
  "A public page with the header (logo, tabs, Log in) around BODY."
  [req active & body]
  (r/h-respond
   (ui/std-header
    (gs/site-name)
    (ui/link-stylesheet "/css/main.css")
    [:body.public-body
     (into
      [:main.public-main
       [:div.public-top
        [:a.public-logo {:href "/" :title (gs/site-name)} (ui/app-logo-icon)]
        [:span.public-site-name (gs/site-name)]
        (public-tabs active (visible-tabs))
        [:a.btn.btn-secondary.small.public-login {:href "/app"}
         (if (auth/current-user req) "Open app" "Log in")]]]
      body)
     (ui/link-js-script "/js/main.js")])))

(defn public-page
  [req]
  (public-shell req "recipes"
                [:h1.dashboard-title "Recipes"]
                (ui/empty-state [:p.muted "No recipes have been published yet."])))
