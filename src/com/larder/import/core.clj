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

(ns com.larder.import.core
  "The importer interface. An importer takes a source and returns a list of imported
  recipes in the app's own shape:

    {:recipe   {:title :description :servings :prep_minutes :cook_minutes :steps
                :source_url :tags :lines}     ; like recipes/parse-form's result
     :image    {:bytes byte-array} or nil
     :warnings [\"...\"]}                     ; shown after import

  `import-source` dispatches on the source's :kind (:web now, :tandoor later). `save!`
  stores what an importer returned, published, through the same parser and matcher as
  typed recipes."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [com.larder.images :as images]
            [com.larder.recipes :as recipes]))

(defmulti import-source
  "Import SOURCE ({:kind ...}). Returns a seq of imported recipes (see the ns doc).
  Throws ex-info with :user-message when nothing can be imported."
  :kind)

(defn- clip
  [s n]
  (when-let [s (some-> s str str/trim not-empty)]
    (if (> (count s) n) (subs s 0 n) s)))

(defn- in-range
  [n lo hi]
  (when (and (number? n) (<= lo n hi)) (long n)))

(defn fit
  "RECIPE trimmed to the editor's limits, so an import never fails validation."
  [recipe]
  (let [{:keys [title description steps source_url servings prep_minutes cook_minutes tags lines]} recipe
        l recipes/limits]
    {:title (or (clip title (:title l)) "Imported recipe")
     :description (clip description (:description l))
     :steps (clip (str/join "\n" (recipes/split-lines steps)) (:steps l))
     :source_url (let [u (clip source_url (:source-url l))] (when (and u (recipes/valid-url? u)) u))
     :servings (in-range servings 1 (:servings l))
     :prep_minutes (in-range prep_minutes 0 (:minutes l))
     :cook_minutes (in-range cook_minutes 0 (:minutes l))
     :rating nil
     :tags (->> tags (keep #(clip % (:tag l))) distinct (take (:tags l)) vec)
     :lines (->> lines (keep #(clip % (:line l))) (take (:lines l)) vec)}))

(defn- store-image
  "Stored image name for BYTES, or [nil warning]."
  [^bytes bytes]
  (let [tmp (java.io.File/createTempFile "larder-import" ".img")]
    (try
      (with-open [out (java.io.FileOutputStream. tmp)] (.write out bytes))
      [(images/store! tmp) nil]
      (catch clojure.lang.ExceptionInfo e
        [nil (str "The image wasn't saved: " (:user-message (ex-data e)))])
      (catch Exception e
        (log/warn e "Storing an imported image failed")
        [nil "The image wasn't saved."])
      (finally (.delete tmp)))))

(defn save!
  "Store IMPORTED (one item of an importer's result) as a published recipe of USER-ID.
  Returns {:id recipe-id :warnings [...]}."
  [imported user-id]
  (let [[image-name image-warning] (when-let [b (get-in imported [:image :bytes])] (store-image b))
        id (recipes/create! (assoc (fit (:recipe imported)) :image_path image-name) "published" user-id)]
    {:id id
     :warnings (cond-> (vec (:warnings imported)) image-warning (conj image-warning))}))
