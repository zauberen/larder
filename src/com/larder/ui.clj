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

(ns com.larder.ui
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [ring.util.response :as ring-response]))

(defn static-path [path]
  (if-some [last-modified (some-> (io/resource (str "public" path))
                                  ring-response/resource-data
                                  :last-modified
                                  (.getTime))]
    (str path "?t=" last-modified)
    path))

(defn link-stylesheet
  "Generates a stylesheet link for the given resource."
  [resource-path]
  [:link {:rel "stylesheet" :type "text/css" :href (static-path resource-path)}])

(defn link-js-script
  "Generates a script link for the given resource."
  [resource-path]
  [:script {:src (static-path resource-path)}])

(defn std-header
  "The html document: TITLE in the browser tab, SCRIPTS (stylesheets etc.) in the head, then BODY."
  [title scripts body]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
    [:title title]
    [:link {:rel "icon" :type "image/svg+xml" :href (static-path "/img/icon.svg")}]
    [:link {:rel "stylesheet" :type "text/css" :href (static-path "/css/theme.css")}]
    (when (not (nil? scripts)) scripts)
    ;; Vendored in resources/public/js/vendor so the app works without internet access.
    (link-js-script "/js/vendor/htmx-2.0.4.min.js")]
   body])

;;; Icons

(defn app-logo-icon
  []
  [:img.app-logo-icon {:src (static-path "/img/icon.svg") :alt ""}])

(defn- stroke-icon
  "A 24x24 outline icon of class CLS drawn with the path data in DS."
  [cls & ds]
  (into [:svg {:class cls :fill "none" :stroke "currentColor" :viewBox "0 0 24 24" :aria-hidden "true"}]
        (for [d ds]
          [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d d}])))

(defn settings-icon
  []
  (stroke-icon "header-icon settings-icon"
               "M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
               "M15 12a3 3 0 11-6 0 3 3 0 016 0z"))

(defn logout-icon
  []
  (stroke-icon "header-icon logout-icon"
               "M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1"))

(defn globe-icon
  []
  (stroke-icon "header-icon"
               "M12 21a9 9 0 100-18 9 9 0 000 18zm0 0c2.5-2.4 3.75-5.4 3.75-9S14.5 5.4 12 3m0 18c-2.5-2.4-3.75-5.4-3.75-9S9.5 5.4 12 3M3.6 9h16.8M3.6 15h16.8"))

(defn add-icon
  []
  (stroke-icon "nav-icon" "M12 6v6m0 0v6m0-6h6m-6 0H6"))

(defn recipes-icon
  "An open book."
  []
  (stroke-icon "nav-icon"
               "M12 6.25c-1.8-1.3-4.3-2-7-2v13.5c2.7 0 5.2.7 7 2m0-13.5c1.8-1.3 4.3-2 7-2v13.5c-2.7 0-5.2.7-7 2m0-13.5v13.5"))

(defn calendar-icon
  []
  (stroke-icon "nav-icon" "M8 7V3m8 4V3M4 11h16M6 5h12a2 2 0 012 2v12a2 2 0 01-2 2H6a2 2 0 01-2-2V7a2 2 0 012-2z"))

(defn pantry-icon
  "A storage jar."
  []
  (stroke-icon "nav-icon" "M9 3h6v3H9zM7 6h10a2 2 0 012 2v11a2 2 0 01-2 2H7a2 2 0 01-2-2V8a2 2 0 012-2zM5 13h14"))

(defn grocery-icon
  "A shopping cart."
  []
  (stroke-icon "nav-icon"
               "M3 3h2l.4 2M7 13h10l4-8H5.4M7 13L5.4 5M7 13l-2.3 2.3c-.6.6-.2 1.7.7 1.7H17m0 0a2 2 0 100 4 2 2 0 000-4zm-8 2a2 2 0 11-4 0 2 2 0 014 0z"))

;;; Building blocks

(defn nav-attrs
  "Attributes for an in-app link: htmx swaps only the main content and pushes the URL,
  so the sidebar stays in place."
  [href]
  {:href href
   :hx-get href
   :hx-target "#main"
   :hx-select "#main"
   :hx-swap "outerHTML"
   :hx-push-url "true"})

(defn alert
  "A message box. KIND is :error, :success or :info."
  [kind & body]
  (into [:div.alert {:class (name kind)}] body))

(defn errors-alert
  [errors]
  (when (seq errors)
    (alert :error (into [:ul] (map (fn [e] [:li e]) errors)))))

(defn message-alert
  "The alert for a {:kind :text} MESSAGE, or nil."
  [message]
  (when message (alert (:kind message) (:text message))))

(defn json-attr
  "Encode DATA for use in a data-* attribute."
  [data]
  (json/write-str data))

(defn tabs
  "A tab bar. TABS is a seq of [key label href]; ACTIVE is the selected key."
  [active tabs]
  (into [:nav.tabs]
        (for [[k label href] tabs]
          [:a.tab (merge (nav-attrs href) {:class (when (= k active) "active")}) label])))

(defn copy-field
  "A read-only value with a copy button."
  [value]
  [:div.copy-field
   [:code.copy-value {:title value} value]
   [:button.copy-button {:type "button" :data-copy value} "Copy"]])

(defn field
  "A labelled form field around INPUT, with an optional HINT."
  [label input & [hint]]
  [:label.form-field
   [:span.form-label label]
   input
   (when hint [:span.form-hint hint])])

(defn select
  "A select named NAME. OPTIONS is a seq of [value label]."
  [name options selected & [attrs]]
  (into [:select.form-input (merge {:name name} attrs)]
        (for [[value label] options]
          [:option {:value value :selected (= (str value) (str selected))} label])))

(defn empty-state
  [& body]
  (into [:div.empty-state] body))
