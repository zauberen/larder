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

(ns com.larder.recipes-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.images :as images]
            [com.larder.pages.recipes :as recipes-page]
            [com.larder.recipes :as recipes]
            [com.larder.test-util :as tu]
            [com.larder.users :as users]
            [com.larder.web-test :refer [login]])
  (:import (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream)
           (javax.imageio ImageIO)))

(use-fixtures :each tu/temp-env-fixture)

(defn- post [sid uri & {:as opts}]
  (let [resp (larder/app (apply tu/request :post uri :sid sid (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(defn- get-page [uri & opts]
  (let [resp (larder/app (apply tu/request :get uri opts))]
    (assoc resp :text (tu/body-str resp))))

(def pancakes
  {:title "Pancakes" :description "Fluffy.\n\nGood on Sundays." :servings "4" :prep_minutes "10" :cook_minutes "20"
   :tags "Breakfast, sweet, breakfast" :rating "4" :source_url "https://example.com/pancakes"
   :ingredients "2 cups flour\n\n 2 eggs \n1 cup milk" :steps "Mix.\n\nFry."})

(defn- created-id
  [resp]
  (some-> (get-in resp [:headers "HX-Push-Url"]) (str/replace "/app/recipes/" "") parse-long))

;;; Unit

(deftest parse-form-test
  (let [[r errors] (recipes/parse-form pancakes)]
    (is (empty? errors))
    (is (= ["Breakfast" "sweet"] (:tags r)))
    (is (= ["2 cups flour" "2 eggs" "1 cup milk"] (:lines r)))
    (is (= "Mix.\nFry." (:steps r)))
    (is (= [4 10 20 4] ((juxt :servings :prep_minutes :cook_minutes :rating) r))))
  (testing "blank optional fields are nil"
    (let [[r errors] (recipes/parse-form {:title "Toast"})]
      (is (empty? errors))
      (is (every? nil? ((juxt :servings :rating :description :steps :source_url) r)))))
  (testing "errors"
    (let [[_ errors] (recipes/parse-form {:title " " :rating "6" :servings "0" :source_url "javascript:alert(1)"})]
      (is (= 4 (count errors))))))

(deftest next-status-test
  (is (= "draft" (recipes-page/next-status nil "draft")))
  (is (= "published" (recipes-page/next-status nil "publish")))
  (is (= "published" (recipes-page/next-status "draft" "publish")))
  (is (= "published" (recipes-page/next-status "published" "save")))
  (is (= "draft" (recipes-page/next-status "published" "revert")))
  (testing "buttons not offered in a state do nothing"
    (is (nil? (recipes-page/next-status nil "save")))
    (is (nil? (recipes-page/next-status "draft" "revert")))
    (is (nil? (recipes-page/next-status "published" "draft")))))

(deftest search-test
  (let [uid (:id (users/by-username "admin"))
        a (recipes/create! (first (recipes/parse-form {:title "Apple pie" :tags "dessert"})) "published" uid)
        b (recipes/create! (first (recipes/parse-form {:title "Bean stew" :description "Hearty 100% beans"})) "draft" uid)]
    (is (= [a b] (map :id (recipes/search {}))))
    (is (= [a] (map :id (recipes/search {:q "DESSERT"}))) "tags match, ignoring case")
    (is (= [b] (map :id (recipes/search {:q "100%"}))) "LIKE wildcards are literal")
    (is (= [] (map :id (recipes/search {:q "_"}))))
    (is (= [b] (map :id (recipes/search {:status "draft"}))))
    (is (= ["dessert"] (:tags (first (recipes/search {:status "published"})))))))

;;; Web

(deftest create-draft-publish-revert-test
  (users/create! "cook" "password1" "user")
  (let [sid (login "cook")
        resp (post sid "/api/recipes" :form (assoc pancakes :action "draft"))
        id (created-id resp)
        recipe (recipes/by-id id)]
    (testing "a plain user can create a draft"
      (is (str/includes? (:text resp) "Saved as a draft"))
      (is (= "draft" (:status recipe)))
      (is (= ["2 cups flour" "2 eggs" "1 cup milk"] (map :original_text (:ingredients recipe))))
      (is (= (:id (users/by-username "cook")) (:created_by recipe))))
    (testing "drafts are invisible to visitors"
      (is (not (str/includes? (:text (get-page "/")) "Pancakes")))
      (is (= 404 (:status (get-page (str "/recipes/" id))))))
    (testing "but listed in the app with a Draft badge"
      (let [body (:text (get-page "/app" :sid sid))]
        (is (str/includes? body "Pancakes"))
        (is (str/includes? body "Draft"))))
    (testing "the draft editor offers Save draft and Publish"
      (let [body (:text (get-page (str "/app/recipes/" id "/edit") :sid sid))]
        (is (str/includes? body "value=\"draft\""))
        (is (str/includes? body "value=\"publish\""))
        (is (not (str/includes? body "value=\"revert\"")))))
    (testing "publishing"
      (post sid (str "/api/recipes/" id) :form (assoc pancakes :action "publish" :title "Best pancakes"))
      (is (= "published" (:status (recipes/by-id id))))
      (is (str/includes? (:text (get-page "/")) "Best pancakes"))
      (is (str/includes? (:text (get-page "/")) "Search recipes"))
      (is (str/includes? (:text (get-page "/" :query "q=pancake")) "Best pancakes"))
      (let [body (:text (get-page "/" :query "q=waffles"))]
        (is (not (str/includes? body "Best pancakes")))
        (is (str/includes? body "No recipes match.")))
      (let [page (get-page (str "/recipes/" id))]
        (is (= 200 (:status page)))
        (is (str/includes? (:text page) "1 cup milk"))
        (is (str/includes? (:text page) "Fry."))))
    (testing "the published editor offers Save and Revert to draft"
      (let [body (:text (get-page (str "/app/recipes/" id "/edit") :sid sid))]
        (is (str/includes? body "value=\"save\""))
        (is (str/includes? body "value=\"revert\""))
        (is (not (str/includes? body "value=\"publish\"")))))
    (testing "saving keeps it published; a disallowed action is refused"
      (post sid (str "/api/recipes/" id) :form (assoc pancakes :action "save" :rating ""))
      (is (= "published" (:status (recipes/by-id id))))
      (is (nil? (:rating (recipes/by-id id))))
      (is (str/includes? (:text (post sid (str "/api/recipes/" id) :form (assoc pancakes :action "draft")))
                         "Unknown action")))
    (testing "reverting to draft hides it again"
      (post sid (str "/api/recipes/" id) :form (assoc pancakes :action "revert"))
      (is (= "draft" (:status (recipes/by-id id))))
      (is (= 404 (:status (get-page (str "/recipes/" id))))))))

(deftest editor-validation-test
  (let [sid (login "admin")
        resp (post sid "/api/recipes" :form (assoc pancakes :title "" :servings "lots" :action "publish"))]
    (is (str/includes? (:text resp) "Give the recipe a title"))
    (is (str/includes? (:text resp) "Servings must be"))
    (testing "what was typed is kept"
      (is (str/includes? (:text resp) "2 cups flour")))
    (is (empty? (recipes/search {})))))

(deftest recipe-text-is-escaped-test
  (let [sid (login "admin")
        id (created-id (post sid "/api/recipes" :form {:title "<script>alert(1)</script>" :steps "<b>bold</b>"
                                                       :action "publish"}))
        body (:text (get-page (str "/recipes/" id)))]
    (is (not (str/includes? body "<script>alert(1)")))
    (is (not (str/includes? body "<b>bold</b>")))
    (is (str/includes? body "&lt;b&gt;bold"))))

(deftest search-and-filter-page-test
  (let [sid (login "admin")]
    (post sid "/api/recipes" :form {:title "Apple pie" :action "publish"})
    (post sid "/api/recipes" :form {:title "Bean stew" :action "draft"})
    (let [body (:text (get-page "/app" :sid sid :query "q=apple" :headers {"hx-request" "true"}))]
      (is (str/includes? body "Apple pie"))
      (is (not (str/includes? body "Bean stew"))))
    (let [body (:text (get-page "/app" :sid sid :query "status=draft"))]
      (is (str/includes? body "Bean stew"))
      (is (not (str/includes? body "Apple pie"))))))

(deftest delete-test
  (let [sid (login "admin")
        id (created-id (post sid "/api/recipes" :form {:title "Gone soon" :action "publish"}))
        resp (post sid (str "/api/recipes/" id "/delete"))]
    (is (str/includes? (get-in resp [:headers "HX-Location"]) "/app"))
    (is (nil? (recipes/by-id id)))
    (is (= 404 (:status (get-page (str "/app/recipes/" id) :sid sid))))
    (testing "visitors can't change recipes"
      (is (= 302 (:status (post nil "/api/recipes" :form {:title "Spam" :action "publish"}))))
      (is (empty? (recipes/search {}))))))

;;; Images

(defn- png-bytes
  [w h]
  (let [img (BufferedImage. w h BufferedImage/TYPE_INT_ARGB)
        out (ByteArrayOutputStream.)]
    (ImageIO/write img "png" out)
    (.toByteArray out)))

(defn- upload!
  "Send BYTES through the chunked upload API. Returns the upload id."
  [sid ^bytes data]
  (let [start (post sid "/api/uploads" :form {:filename "photo.png" :size (alength data) :kind "image"})
        {:strs [id]} (json/read-str (:text start))]
    (is (= 200 (:status start)))
    (is (= 200 (:status (larder/app (tu/request :put (str "/api/uploads/" id) :sid sid :bytes data :query "offset=0")))))
    id))

(deftest image-upload-test
  (let [sid (login "admin")
        id (created-id (post sid "/api/recipes" :form {:title "Pictured" :action "draft"
                                                       :image_upload (upload! sid (png-bytes 3000 1500))}))
        image (:image_path (recipes/by-id id))]
    (testing "the image is re-encoded as a scaled-down JPEG"
      (is (re-matches #"[0-9a-f]{32}\.jpg" image))
      (let [stored (ImageIO/read (images/media-file image))]
        (is (= [2000 1000] [(.getWidth stored) (.getHeight stored)]))))
    (testing "draft images are only for signed-in users"
      (is (= 404 (:status (get-page (str "/media/" image)))))
      (let [resp (get-page (str "/media/" image) :sid sid)]
        (is (= 200 (:status resp)))
        (is (= "image/jpeg" (get-in resp [:headers "Content-Type"])))))
    (testing "published images are public"
      (post sid (str "/api/recipes/" id) :form {:title "Pictured" :action "publish"})
      (is (= 200 (:status (get-page (str "/media/" image))))))
    (testing "replacing the image deletes the old file"
      (post sid (str "/api/recipes/" id) :form {:title "Pictured" :action "save"
                                                :image_upload (upload! sid (png-bytes 10 10))})
      (is (nil? (images/media-file image)))
      (is (some? (images/media-file (:image_path (recipes/by-id id))))))
    (testing "removing the image"
      (let [current (:image_path (recipes/by-id id))]
        (post sid (str "/api/recipes/" id) :form {:title "Pictured" :action "save" :remove_image "1"})
        (is (nil? (:image_path (recipes/by-id id))))
        (is (nil? (images/media-file current)))))
    (testing "deleting a recipe deletes its image"
      (post sid (str "/api/recipes/" id) :form {:title "Pictured" :action "save"
                                                :image_upload (upload! sid (png-bytes 10 10))})
      (let [current (:image_path (recipes/by-id id))]
        (post sid (str "/api/recipes/" id "/delete"))
        (is (nil? (images/media-file current)))))
    (testing "unknown media names are not found"
      (is (= 404 (:status (get-page "/media/..%2Flarder.db" :sid sid))))
      (is (= 404 (:status (get-page "/media/00000000000000000000000000000000.jpg" :sid sid)))))))

(deftest image-rejection-test
  (let [sid (login "admin")]
    (testing "files that aren't images are refused and the recipe isn't saved"
      (let [resp (post sid "/api/recipes" :form {:title "Bad" :action "draft"
                                                 :image_upload (upload! sid (.getBytes "not an image at all"))})]
        (is (str/includes? (:text resp) "an image Larder can read"))
        (is (empty? (recipes/search {})))))
    (testing "images over 10 MB are refused before uploading"
      (let [resp (post sid "/api/uploads" :form {:filename "big.png" :size (inc images/max-bytes) :kind "image"})]
        (is (= 400 (:status resp)))
        (is (str/includes? (:text resp) "too large"))))
    (testing "unknown upload kinds are refused"
      (is (= 400 (:status (post sid "/api/uploads" :form {:filename "x" :size 10 :kind "world"})))))))
