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

(ns com.larder.tandoor-import-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.images :as images]
            [com.larder.import.tandoor :as tandoor]
            [com.larder.recipes :as recipes]
            [com.larder.test-util :as tu]
            [com.larder.web-test :refer [login]])
  (:import (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream)
           (java.util.zip ZipEntry ZipOutputStream)
           (javax.imageio ImageIO)))

(use-fixtures :each tu/temp-env-fixture)

;;; Building exports

(defn- zip-bytes
  "A zip of ENTRIES (name -> bytes or string)."
  ^bytes [entries]
  (let [out (ByteArrayOutputStream.)]
    (with-open [z (ZipOutputStream. out)]
      (doseq [[n content] entries]
        (.putNextEntry z (ZipEntry. ^String n))
        (.write z ^bytes (if (string? content) (.getBytes ^String content "UTF-8") content))
        (.closeEntry z)))
    (.toByteArray out)))

(defn- png-bytes
  []
  (let [out (ByteArrayOutputStream.)]
    (ImageIO/write (BufferedImage. 20 10 BufferedImage/TYPE_INT_RGB) "png" out)
    (.toByteArray out)))

(defn- ingredient
  [food unit amount & {:as more}]
  (merge {:food {:name food} :unit (when unit {:name unit}) :amount amount :note "" :order 0
          :is_header false :no_amount false}
         more))

(def soup
  {:name "Tomato Soup" :description "Warm." :servings 4 :servings_text "" :working_time 10 :waiting_time 25
   :source_url "https://example.com/soup" :internal true :nutrition nil
   :keywords [{:name "Dinner"} {:name "soup"}]
   :steps [{:name "" :order 0 :instruction "Chop the onion.  \nCook it in butter.\n\nAdd tomatoes."
            :ingredients [(ingredient "onion" nil 1.0 :note "chopped")
                          (ingredient "butter" "tbsp" 2.0)
                          (ingredient "Tomatoes" "can" 1.0 :note "(14 oz)")
                          (ingredient "salt" "pinch" 0.0)
                          (ingredient "Topping" nil 0.0 :is_header true)]}]})

(def stew
  {:name "Stew" :description "" :servings 0 :working_time 0 :waiting_time 0 :source_url nil :keywords []
   :steps [{:name "Broth" :order 0 :instruction "Simmer."
            :ingredients [(ingredient "water" "cups" 4.0)]}
           {:name "Finish" :order 1 :instruction "Serve."
            :ingredients [(ingredient "¼ cup () sugar" "g" 50.0)]}]})

(defn- recipe-zip
  [recipe & [image]]
  (zip-bytes (cond-> {"recipe.json" (json/write-str recipe)} image (assoc "image.png" image))))

(defn- export-bytes
  []
  (zip-bytes (array-map "1.zip" (recipe-zip soup (png-bytes))
                        "2.zip" (recipe-zip stew)
                        "10.zip" (zip-bytes {"notes.txt" "no recipe here"}))))

(defn- temp-file
  [^bytes data]
  (let [f (java.io.File/createTempFile "tandoor-test" ".zip")]
    (.deleteOnExit f)
    (with-open [o (java.io.FileOutputStream. f)] (.write o data))
    f))

;;; Unit

(deftest ingredient-line-test
  (is (= "1 onion, chopped" (tandoor/ingredient-line (ingredient "onion" nil 1.0 :note "chopped"))))
  (is (= "1½ cups flour" (tandoor/ingredient-line (ingredient "flour" "cups" 1.5))))
  (is (= "salt (pinch)" (tandoor/ingredient-line (ingredient "salt" "pinch" 0.0))) "no amount keeps the name first")
  (is (= "pepper" (tandoor/ingredient-line (ingredient "pepper" "tsp" 2.0 :no_amount true))))
  (is (= "50 g ¼ cup sugar" (tandoor/ingredient-line (ingredient "¼ cup () sugar" "g" 50.0))) "empty parentheses go")
  (is (nil? (tandoor/ingredient-line (ingredient "Topping" nil 0.0 :is_header true))))
  (is (nil? (tandoor/ingredient-line (ingredient "" nil 1.0)))))

(deftest read-export-test
  (let [[a b c] (tandoor/read-export (temp-file (export-bytes)))]
    (testing "a recipe with an image"
      (is (= {:title "Tomato Soup" :description "Warm." :servings 4 :prep_minutes 10 :cook_minutes 25
              :source_url "https://example.com/soup" :tags ["Dinner" "soup"]
              :lines ["1 onion, chopped" "2 tbsp butter" "1 can Tomatoes, (14 oz)" "salt (pinch)"]
              :steps "Chop the onion.\nCook it in butter.\nAdd tomatoes."}
             (update (:recipe a) :tags vec)))
      (is (some? (get-in a [:image :bytes]))))
    (testing "zeros are unknown; steps are joined in order with their names"
      (is (= [nil nil nil] ((juxt :servings :prep_minutes :cook_minutes) (:recipe b))))
      (is (= "Broth\nSimmer.\nFinish\nServe." (get-in b [:recipe :steps])))
      (is (nil? (:image b))))
    (testing "an entry without recipe.json is reported, sorted numerically"
      (is (= {:failed "10.zip: It has no recipe.json."} c))))
  (testing "a single recipe's zip"
    (is (= ["Stew"] (map (comp :title :recipe) (tandoor/read-export (temp-file (recipe-zip stew)))))))
  (testing "not a zip"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"isn't a zip"
                          (tandoor/read-export (temp-file (.getBytes "hello" "UTF-8"))))))
  (testing "a zip without recipes"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"doesn't look like a Tandoor export"
                          (tandoor/read-export (temp-file (zip-bytes {"a.txt" "x"})))))))

;;; Through the app

(defn- post
  [sid uri & {:as opts}]
  (let [resp (larder/app (apply tu/request :post uri :sid sid (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(defn- upload!
  [sid ^bytes data]
  (let [start (post sid "/api/uploads" :form {:filename "export.zip" :size (alength data) :kind "tandoor"})
        {:strs [id]} (json/read-str (:text start))]
    (is (= 200 (:status start)))
    (is (= 200 (:status (larder/app (tu/request :put (str "/api/uploads/" id) :sid sid :bytes data :query "offset=0")))))
    id))

(defn- import!
  [sid data & {:keys [skip] :or {skip true}}]
  (post sid "/api/recipes/import/tandoor"
        :form (cond-> {:tandoor_upload (upload! sid data)} skip (assoc :skip_existing "1"))))

(deftest import-flow-test
  (let [sid (login "admin")
        resp (import! sid (export-bytes))
        by-title (into {} (map (juxt :title identity)) (recipes/search {}))
        soup-row (recipes/by-id (:id (by-title "Tomato Soup")))]
    (testing "the result lists what was imported and what failed"
      (is (= 200 (:status resp)))
      (is (str/includes? (:text resp) "Imported 2 recipes, published."))
      (is (str/includes? (:text resp) "1 couldn&apos;t be read") (:text resp))
      (is (str/includes? (:text resp) "10.zip: It has no recipe.json.")))
    (testing "recipes are published with tags, times, lines and image"
      (is (= #{"Tomato Soup" "Stew"} (set (keys by-title))))
      (is (= "published" (:status soup-row)))
      (is (= [10 25 4] ((juxt :prep_minutes :cook_minutes :servings) soup-row)))
      (is (= ["Dinner" "soup"] (:tags soup-row)))
      (is (= ["1 onion, chopped" "2 tbsp butter" "1 can Tomatoes, (14 oz)" "salt (pinch)"]
             (map :original_text (:ingredients soup-row))))
      (is (= [2.0 "tbsp"] ((juxt :quantity :unit) (second (:ingredients soup-row)))))
      (is (some? (:ingredient_id (second (:ingredients soup-row)))) "seeded butter matches")
      (is (some? (images/media-file (:image_path soup-row)))))
    (testing "importing again skips recipes already there"
      (let [again (import! sid (export-bytes))]
        (is (str/includes? (:text again) "No recipes were imported. 2 already in Larder were skipped."))
        (is (= 2 (count (recipes/search {}))))))
    (testing "without skipping they are imported again"
      (import! sid (export-bytes) :skip false)
      (is (= 4 (count (recipes/search {})))))))

(deftest import-errors-test
  (let [sid (login "admin")]
    (is (str/includes? (:text (post sid "/api/recipes/import/tandoor" :form {})) "Choose the Tandoor export"))
    (is (str/includes? (:text (import! sid (.getBytes "not a zip" "UTF-8"))) "That file isn&apos;t a zip file."))
    (is (str/includes? (:text (post sid "/api/recipes/import/tandoor" :form {:tandoor_upload "nope"}))
                       "The upload has expired"))
    (testing "signed out"
      (is (not= 200 (:status (larder/app (tu/request :post "/api/recipes/import/tandoor" :form {}))))))))
