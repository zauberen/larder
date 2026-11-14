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
  (:require [clojure.string :as str]
            [com.larder.auth :as auth]
            [com.larder.global-settings :as gs]
            [com.larder.pages.grocery :as grocery-page]
            [com.larder.pages.recipe-view :as view]
            [com.larder.recipes :as recipes]
            [com.larder.status :as status]
            [com.larder.util :as u]
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
  is open to everyone."
  []
  (cond-> [["recipes" "Recipes" "/"]]
    (= "open" (gs/grocery-access)) (conj ["grocery" "Grocery list" "/grocery"])))

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

(defn public-recipe-list
  [q]
  (let [found (recipes/search {:q q :status "published"})
        tags (status/recipe-statuses (status/snapshot) (map :id found))]
    [:div#recipe-list
     (view/card-grid (for [rec found]
                       (view/recipe-card rec {:href (str "/recipes/" (:id rec))}
                                         (view/status-tag (tags (:id rec)) #{:ready})))
                     (if (seq (str/trim (or q ""))) "No recipes match." "No recipes have been published yet."))]))

(defn public-page
  [req]
  (let [q (get-in req [:params :q])]
    (public-shell req "recipes"
                  [:h1.dashboard-title "Recipes"]
                  [:form.list-filters {:action "/" :method "get"
                                       :hx-get "/" :hx-trigger "input changed delay:300ms, change"
                                       :hx-target "#recipe-list" :hx-select "#recipe-list" :hx-swap "outerHTML"
                                       :hx-push-url "true" :role "search"}
                   [:input.form-input {:type "search" :name "q" :value q :placeholder "Search recipes"
                                       :aria-label "Search recipes"}]]
                  (public-recipe-list q))))

(defn recipe-page
  "A published recipe for visitors. Drafts are not found."
  [req id]
  (if-let [recipe (recipes/published-by-id (u/parse-int id))]
    (public-shell req "recipes"
                  [:a.back-link {:href "/"} "← All recipes"]
                  [:h1.dashboard-title (:title recipe)]
                  ;; Visitors see whether it's pantry ready, never the amounts.
                  (view/status-tag (status/recipe-status (status/snapshot) (:ingredients recipe)) #{:ready})
                  (view/detail-body recipe))
    (auth/not-found)))

(defn grocery-page
  "/grocery (TOKEN nil) and /g/:token: the grocery list when the access setting allows."
  [req token]
  (if (grocery-page/access req token)
    (public-shell req "grocery" (grocery-page/public-body {:app? false :token token}))
    (auth/not-found)))
