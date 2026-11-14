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

(ns com.larder.pages.layout
  "The logged-in application shell: header, sidebar, main content."
  (:require [clojure.string :as str]
            [com.larder.global-settings :as gs]
            [com.larder.response :as r]
            [com.larder.ui :as ui]))

(def sections
  "Sidebar entries below Add: [key label href icon-fn]."
  [["recipes" "Recipes" "/app" ui/recipes-icon]
   ["meal-plan" "Meal plan" "/app/meal-plan" ui/calendar-icon]
   ["pantry" "Pantry" "/app/pantry" ui/pantry-icon]
   ["grocery" "Grocery" "/app/grocery" ui/grocery-icon]])

(defn section-for-path
  "The sidebar entry to highlight for the URL path PATH, or nil."
  [path]
  (let [path (str/replace (or path "") #"[?#].*$" "")]
    (cond
      (= path "/app/recipes/new") "add"
      (or (= path "/app") (= path "/app/") (str/starts-with? path "/app/recipes")) "recipes"
      :else (some (fn [[k _ href]] (when (and (not= href "/app") (str/starts-with? path href)) k)) sections))))

(defn main
  "The swappable main content element for SECTION (a key of `sections`, \"add\" or nil).
  main.js reads data-section after each swap to move the sidebar highlight."
  [section & body]
  (into [:main#main.main-content {:data-section (or section "")}] body))

(defn sidebar
  [active]
  [:aside.sidebar
   [:div.nav-section
    [:a.nav-button (merge (ui/nav-attrs "/app/recipes/new") {:data-section "add"
                                                             :class (when (= "add" active) "active")})
     (ui/add-icon) "Add"]]
   [:div.section-divider
    (for [[k label href icon] sections]
      [:a.nav-button (merge (ui/nav-attrs href) {:data-section k :class (when (= k active) "active")})
       (icon) [:span.nav-label label]])]])

(defn header
  [user]
  [:header.header
   [:a.app-logo (ui/nav-attrs "/app") (ui/app-logo-icon) [:span (gs/site-name)]]
   [:div.user-section
    [:span.username (:username user)]
    [:a {:href "/" :title "Public dashboard"} (ui/globe-icon)]
    [:a (merge (ui/nav-attrs "/app/settings") {:title "Settings"}) (ui/settings-icon)]
    [:a {:href "/logout" :title "Log out"} (ui/logout-icon)]]])

(defn app-page
  "Respond with CONTENT (a `main` element) inside the app shell. htmx navigation
  requests only need the main element, so the shell is skipped for them."
  [req user content]
  (if (and (= "true" (get-in req [:headers "hx-request"]))
           (not= "true" (get-in req [:headers "hx-history-restore-request"])))
    (r/h-respond content)
    (r/h-respond
     (ui/std-header
      (gs/site-name)
      (ui/link-stylesheet "/css/main.css")
      [:body
       (header user)
       (sidebar (section-for-path (:uri req)))
       content
       (ui/link-js-script "/js/main.js")]))))
