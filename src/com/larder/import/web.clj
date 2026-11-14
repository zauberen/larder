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

(ns com.larder.import.web
  "Import from a web page: schema.org Recipe data in JSON-LD, then in microdata."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.larder.fetch :as fetch]
            [com.larder.import.core :as core])
  (:import (java.io ByteArrayInputStream)
           (java.net URI)
           (org.jsoup Jsoup)
           (org.jsoup.nodes Document Element)))

;;; Values

(defn- text
  "Plain text of a value that may hold HTML and entities."
  [s]
  (when (string? s)
    (some-> (.text (Jsoup/parse ^String s)) str/trim not-empty)))

(defn- html-lines
  "Lines of text in S, treating <br>, paragraphs and list items as line breaks."
  [s]
  (when (string? s)
    (->> (str/split (str/replace s #"(?i)<br\s*/?>|</p>|</li>|</div>" "\n") #"\n")
         (keep text))))

(defn- recipe-type?
  [t]
  (some #(= "recipe" (str/lower-case (str/replace (str %) #"^.*[/#]" "")))
        (if (sequential? t) t [t])))

(defn duration-minutes
  "Minutes in an ISO 8601 duration such as PT1H30M or P0DT45M, or nil."
  [s]
  (when-let [[_ d h m sec] (and (string? s)
                                (re-matches #"(?i)\s*P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?\s*" s))]
    (when (or d h m sec)
      (+ (* 1440 (or (some-> d parse-long) 0))
         (* 60 (or (some-> h parse-long) 0))
         (or (some-> m parse-long) 0)
         (if sec (Math/round (/ (parse-double sec) 60.0)) 0)))))

(defn- first-int
  [x]
  (cond
    (number? x) (long x)
    (string? x) (some-> (re-find #"\d+" x) parse-long)
    (sequential? x) (some first-int x)))

(defn- resolve-url
  [base s]
  (when (and (string? s) (not (str/blank? s)))
    (try (str (.resolve (URI. base) (str/replace (str/trim s) " " "%20")))
         (catch Exception _ nil))))

(defn- image-url
  [base x]
  (cond
    (string? x) (resolve-url base x)
    (map? x) (image-url base (or (get x "url") (get x "contentUrl") (get x "@id")))
    (sequential? x) (some #(image-url base %) x)))

(defn- instructions
  "Step texts from recipeInstructions: text, HowToStep, HowToSection or lists of them."
  [x]
  (cond
    (string? x) (html-lines x)
    (sequential? x) (mapcat instructions x)
    (map? x) (let [t (some-> (get x "@type") str str/lower-case)]
               (cond
                 (and t (str/includes? t "section")) (instructions (get x "itemListElement"))
                 (get x "text") (instructions (get x "text"))
                 (get x "itemListElement") (instructions (get x "itemListElement"))
                 :else (instructions (get x "name"))))))

(defn- times
  [prep cook total]
  (let [prep (duration-minutes prep) cook (duration-minutes cook) total (duration-minutes total)]
    {:prep_minutes prep
     :cook_minutes (or cook (when (and total prep (>= total prep)) (- total prep)) (when-not prep total))}))

;;; JSON-LD

(defn- find-recipe
  "The first schema.org Recipe object in a parsed JSON-LD value."
  [x depth]
  (when (< depth 8)
    (cond
      (map? x) (if (recipe-type? (get x "@type"))
                 x
                 (some #(find-recipe % (inc depth)) (vals x)))
      (sequential? x) (some #(find-recipe % (inc depth)) x))))

(defn- from-json-ld
  [^Document doc base]
  (some (fn [^Element script]
          (when-let [data (try (json/read-str (.data script)) (catch Exception _ nil))]
            (when-let [r (find-recipe data 0)]
              (merge {:title (text (get r "name"))
                      :description (text (get r "description"))
                      :servings (first-int (get r "recipeYield"))
                      :lines (vec (keep text (let [i (or (get r "recipeIngredient") (get r "ingredients"))]
                                               (if (sequential? i) i [i]))))
                      :steps (str/join "\n" (instructions (get r "recipeInstructions")))
                      :image-url (image-url base (get r "image"))}
                     (times (get r "prepTime") (get r "cookTime") (get r "totalTime"))))))
        (.select doc "script[type=application/ld+json]")))

;;; Microdata

(defn- own-props
  "Elements of RECIPE with itemprop PROP that belong to it, not to a nested item."
  [^Element recipe prop]
  (filter (fn [^Element e]
            (let [scope (some (fn [^Element p] (when (.hasAttr p "itemscope") p)) (.parents e))]
              (= scope recipe)))
          (.select recipe (str "[itemprop~=(^|\\s)" prop "(\\s|$)]"))))

(defn- prop-value
  [^Element e base]
  (case (.normalName e)
    "meta" (.attr e "content")
    ("img" "source") (resolve-url base (.attr e "src"))
    ("a" "link") (resolve-url base (.attr e "href"))
    "time" (if (.hasAttr e "datetime") (.attr e "datetime") (.text e))
    (if (.hasAttr e "content") (.attr e "content") (.text e))))

(defn- from-microdata
  [^Document doc base]
  (when-let [^Element r (first (filter #(recipe-type? (str/split (.attr ^Element % "itemtype") #"\s+"))
                                       (.select doc "[itemscope][itemtype]")))]
    (let [v (fn [prop] (some-> (first (own-props r prop)) (prop-value base) str/trim not-empty))
          vs (fn [prop] (keep #(some-> (prop-value % base) str/trim not-empty) (own-props r prop)))
          steps (mapcat (fn [^Element e]
                          (if (.hasAttr e "itemscope")
                            (or (seq (keep #(some-> (prop-value % base) text) (.select e "[itemprop=text]")))
                                [(.text e)])
                            (html-lines (.html e))))
                        (own-props r "recipeInstructions"))]
      (merge {:title (v "name")
              :description (v "description")
              :servings (first-int (v "recipeYield"))
              :lines (vec (or (seq (vs "recipeIngredient")) (vs "ingredients")))
              :steps (str/join "\n" (remove str/blank? steps))
              :image-url (v "image")}
             (times (v "prepTime") (v "cookTime") (v "totalTime"))))))

;;; Importer

(defn parse-html
  "The recipe in HTML (a string or bytes) of the page at BASE-URL, or nil."
  [html base-url]
  (let [^Document doc (if (string? html)
                        (Jsoup/parse ^String html ^String base-url)
                        (Jsoup/parse (ByteArrayInputStream. ^bytes html) nil ^String base-url))
        found (or (from-json-ld doc base-url) (from-microdata doc base-url))]
    (when (and found (or (:title found) (seq (:lines found))))
      found)))

(defn- download-image
  "[image warnings] for the recipe image at URL."
  [url]
  (if (nil? url)
    [nil []]
    (try
      (let [{:keys [status body]} (fetch/fetch url :accept "image/*")]
        (if (= 200 status)
          [{:bytes body} []]
          [nil [(str "The image couldn't be downloaded (HTTP " status ").")]]))
      (catch clojure.lang.ExceptionInfo e
        (log/info "Recipe image download failed:" url (.getMessage e))
        [nil [(str "The image couldn't be downloaded: " (or (:user-message (ex-data e)) "unknown error"))]]))))

(defmethod core/import-source :web
  [{:keys [url]}]
  (let [{:keys [status body content-type] final-url :url} (fetch/fetch url :accept "text/html,application/xhtml+xml")]
    (when-not (= 200 status)
      (throw (ex-info "fetch failed" {:user-message (str "The page couldn't be loaded (HTTP " status ").")})))
    (when-not (re-find #"(?i)html|xml" content-type)
      (throw (ex-info "not html" {:user-message "That address isn't a web page." :no-recipe true})))
    (let [found (parse-html body final-url)]
      (when-not found
        (throw (ex-info "no recipe" {:user-message "That page doesn't have recipe data Larder can read."
                                     :no-recipe true})))
      (let [[image warnings] (download-image (:image-url found))]
        [{:recipe (-> (dissoc found :image-url) (assoc :source_url (str/trim url)))
          :image image
          :warnings warnings}]))))
