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

(ns com.larder.pages.mealplan
  "The meal plan page: a recipe bar and a week or month calendar. The grids are rendered
  here; main.js handles dragging (and tap-then-tap on touch screens) and posts the
  recipe or entry and the day, and the affected day cells are swapped."
  (:require [clojure.string :as str]
            [com.larder.auth :as auth]
            [com.larder.mealplan :as mp]
            [com.larder.pages.layout :as layout]
            [com.larder.pages.recipe-view :as view]
            [com.larder.recipes :as recipes]
            [com.larder.response :as r]
            [com.larder.status :as status]
            [com.larder.ui :as ui]
            [com.larder.units :as units]
            [com.larder.util :as u]
            [hiccup2.core :as h])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(defn- fmt [^LocalDate d pattern] (.format d (DateTimeFormatter/ofPattern pattern Locale/ENGLISH)))

(defn- day-id [d] (str "day-" d))

;;; Calendar

(defn- entry-card
  [snap lines-by-recipe entry]
  (let [tag (status/recipe-status snap (get lines-by-recipe (:recipe_id entry)) (mp/scale entry))
        api (str "/api/meal-plan/entries/" (:id entry))
        cooked? (some? (:cooked_at entry))]
    [:div.mp-entry {:draggable "true" :data-entry-id (:id entry) :class (when cooked? "cooked")}
     [:div.mp-entry-top
      [:a.mp-entry-title (ui/nav-attrs (str "/app/recipes/" (:recipe_id entry))) (:title entry)]
      [:button.icon-button.mp-remove {:hx-post (str api "/delete") :hx-target "closest .mp-day" :hx-swap "outerHTML"
                                      :aria-label (str "Remove " (:title entry)) :title "Remove"} "×"]]
     [:div.mp-entry-meta
      (if cooked?
        [:span.badge.cooked "✓ Cooked"]
        (view/status-tag tag))
      [:label.mp-servings
       [:input.form-input {:type "number" :name "servings" :min 0.25 :step "any" :value (units/input-qty (:servings entry))
                           :hx-post (str api "/servings") :hx-trigger "change" :hx-target "closest .mp-day"
                           :hx-swap "outerHTML" :aria-label (str "Servings of " (:title entry)) :title "Servings"}]
       [:span.mp-servings-label "servings"]]
      (when-not cooked?
        [:button.btn.btn-secondary.small.mp-made
         {:hx-get (str "/api/recipes/" (:recipe_id entry) "/made?entry=" (:id entry)
                       (when (:servings entry) (str "&servings=" (units/input-qty (:servings entry)))))
          :hx-target "#modal"}
         "Made it"])]]))

(defn day-cell
  "One day of the calendar. CTX: {:snap :lines :month (LocalDate or nil)}."
  [{:keys [snap lines month]} ^LocalDate date entries]
  (let [outside? (and month (not= (.getMonthValue date) (.getMonthValue ^LocalDate month)))]
    [:div.mp-day {:id (day-id date) :data-day (str date)
                  :class (not-empty (str/join " " (remove nil? [(when (= date (mp/today)) "today") (when outside? "outside")])))}
     [:div.mp-day-label
      [:span.mp-weekday (fmt date "EEE")]
      [:span.mp-date (fmt date (if month "d" "MMM d"))]]
     (into [:div.mp-entries] (map #(entry-card snap lines %) entries))
     [:div.mp-drop-hint "Drop a recipe here"]]))

(defn- render-ctx
  [entries & [month]]
  {:snap (status/snapshot)
   :lines (let [ids (distinct (map :recipe_id entries))]
            (into {} (for [id ids] [id (:ingredients (recipes/by-id id))])))
   :month month})

(defn day-response
  "The day cell for DATE, plus out-of-band cells for OTHER-DATES (a move changes two)."
  [date & other-dates]
  (let [dates (cons date other-dates)
        all (mapcat mp/entries-on dates)
        ctx (render-ctx all)]
    (r/respond (apply str
                      (for [[i d] (map-indexed vector dates)]
                        (str (h/html (cond-> (day-cell ctx d (mp/entries-on d))
                                       (pos? i) (assoc-in [1 :hx-swap-oob] "true")))))))))

(defn- calendar
  [view ^LocalDate date]
  (let [dates (if (= "month" view) (mp/month-grid date) (mp/week-of date))
        entries (mp/entries-between (first dates) (last dates))
        by-date (group-by :date entries)
        ctx (render-ctx entries (when (= "month" view) date))
        step (if (= "month" view) #(.plusMonths ^LocalDate date %) #(.plusWeeks ^LocalDate date %))
        href (fn [v d] (str "/app/meal-plan?view=" v "&date=" d))
        title (if (= "month" view)
                (fmt date "MMMM yyyy")
                (str (fmt (first dates) "MMM d") " – " (fmt (last dates) (if (= (.getMonthValue ^LocalDate (first dates)) (.getMonthValue ^LocalDate (last dates))) "d, yyyy" "MMM d, yyyy"))))]
    [:section#mp-calendar.mp-calendar {:data-from (str (first dates)) :data-to (str (last dates))
                                       :hx-get (href view date) :hx-select "#mp-calendar" :hx-target "this"
                                       :hx-swap "outerHTML" :hx-trigger "recipe-cooked from:body" :hx-disinherit "*"}
     [:div.mp-toolbar
      [:div.mp-nav
       [:a.btn.btn-secondary.small (ui/nav-attrs (href view (step -1))) "‹ Previous"]
       [:a.btn.btn-secondary.small (ui/nav-attrs (href view (mp/today))) "Today"]
       [:a.btn.btn-secondary.small (ui/nav-attrs (href view (step 1))) "Next ›"]]
      [:h2.mp-title title]
      [:div.mp-views
       [:a.tab (merge (ui/nav-attrs (href "week" date)) {:class (when (not= "month" view) "active")}) "Week"]
       [:a.tab (merge (ui/nav-attrs (href "month" date)) {:class (when (= "month" view) "active")}) "Month"]]]
     [:div.mp-actions
      [:button.btn.btn-secondary.small {:hx-post "/api/meal-plan/grocery" :hx-vals (ui/json-attr {:from (str (first dates)) :to (str (last dates))})
                                        :hx-target "#recipe-message" :hx-swap "innerHTML"}
       (ui/grocery-icon) "Add to grocery list"]
      [:span.form-hint "Adds what the uncooked meals shown need beyond the pantry and the list."]]
     (when (= "month" view)
       (into [:div.mp-weekdays] (for [d (take 7 dates)] [:span (fmt d "EEE")])))
     (into [:div.mp-grid {:class view}]
           (for [d dates] (day-cell ctx d (get by-date (str d)))))]))

;;; Recipe bar

(defn- recipe-bar
  []
  (let [published (recipes/search {:status "published"})
        tags (status/recipe-statuses (status/snapshot) (map :id published))]
    [:aside.mp-bar
     [:input.form-input {:type "search" :placeholder "Search recipes" :aria-label "Search recipes"
                         :data-filter-list "#mp-recipes"}]
     [:p.form-hint "Drag a recipe onto a day, or tap it and then a day."]
     (if (empty? published)
       [:p.muted "Publish a recipe to plan it."]
       (into [:ul#mp-recipes.mp-recipes]
             (for [rec published]
               [:li.mp-recipe {:draggable "true" :data-recipe-id (:id rec) :data-filter-text (str/lower-case (:title rec))
                               :tabindex "0" :role "button" :aria-label (str "Plan " (:title rec))}
                [:span.mp-recipe-title (:title rec)]
                (view/status-tag (tags (:id rec)))])))]))

(defn meal-plan-page
  [req user]
  (let [{:keys [view date]} (:params req)
        view (if (= "month" view) "month" "week")
        date (or (mp/parse-date date) (mp/today))]
    (layout/app-page
     req user
     (layout/main
      "meal-plan"
      [:h1.dashboard-title "Meal plan"]
      [:div#recipe-message]
      [:div.mp-layout
       (recipe-bar)
       (calendar view date)]
      [:div#modal]))))

;;; API

(defn api-add
  [req _user]
  (let [{:keys [recipe_id date]} (:params req)
        recipe (recipes/by-id (u/parse-int recipe_id))
        d (mp/parse-date date)]
    (if (and recipe d)
      (do (mp/add! (:id recipe) d) (day-response d))
      {:status 400 :headers {"Content-Type" "text/plain"} :body "Unknown recipe or date."})))

(defn api-move
  [req _user id]
  (let [entry (mp/by-id (u/parse-int id))
        d (mp/parse-date (get-in req [:params :date]))]
    (cond
      (or (nil? entry) (nil? d)) {:status 400 :headers {"Content-Type" "text/plain"} :body "Unknown entry or date."}
      (= (str d) (:date entry)) (day-response d)
      :else (do (mp/move! (:id entry) d)
                (day-response d (LocalDate/parse (:date entry)))))))

(defn api-servings
  [req _user id]
  (if-let [entry (mp/by-id (u/parse-int id))]
    (let [s (some-> (get-in req [:params :servings]) str str/trim (str/replace "," ".") parse-double)]
      (when (and s (< 0 s 10000)) (mp/set-servings! (:id entry) s))
      (day-response (LocalDate/parse (:date entry))))
    (auth/not-found)))

(defn api-delete
  [_req _user id]
  (if-let [entry (mp/by-id (u/parse-int id))]
    (do (mp/delete! (:id entry)) (day-response (LocalDate/parse (:date entry))))
    (auth/not-found)))

(defn api-grocery
  [req user]
  (let [{:keys [from to]} (:params req)
        from (mp/parse-date from) to (mp/parse-date to)]
    (if (and from to (not (.isAfter from to)) (<= (.until from to java.time.temporal.ChronoUnit/DAYS) 62))
      (let [{:keys [added covered]} (mp/add-to-grocery! from to (:id user))]
        (-> (r/h-respond (ui/alert (if (pos? added) :success :info)
                                   (cond
                                     (and (zero? added) (zero? covered)) "Nothing planned needs buying."
                                     (zero? added) "Everything the plan needs is in the pantry or on the list."
                                     :else (str "Added " added (if (= 1 added) " item" " items") " to the grocery list."))))
            (assoc-in [:headers "HX-Trigger"] "grocery-changed")))
      (r/h-respond (ui/alert :error "Choose a range of up to two months.")))))
