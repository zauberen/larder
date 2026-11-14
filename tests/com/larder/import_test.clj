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

(ns com.larder.import-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.fetch :as fetch]
            [com.larder.images :as images]
            [com.larder.import.web :as web]
            [com.larder.recipes :as recipes]
            [com.larder.test-util :as tu]
            [com.larder.web-test :refer [login]])
  (:import (com.sun.net.httpserver HttpHandler HttpServer)
           (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream)
           (java.net InetSocketAddress)
           (javax.imageio ImageIO)))

(use-fixtures :each tu/temp-env-fixture)

(defn- fixture [name] (slurp (io/file "tests/fixtures/import" name)))

;;; Parsing saved pages

(deftest json-ld-graph-test
  (let [r (web/parse-html (fixture "json-ld-graph.html") "https://example.com/recipes/lemon")]
    (is (= "Lemon Drizzle Cake" (:title r)))
    (is (= "A zesty loaf & a crunchy glaze." (:description r)))
    (is (= 8 (:servings r)))
    (is (= [15 50] [(:prep_minutes r) (:cook_minutes r)]) "cook time is the total minus prep")
    (is (= ["225 g butter, softened" "225 g caster sugar" "4 eggs" "1 ½ lemons, zested"] (:lines r)))
    (is (= "Heat the oven to 180C.\nBeat the butter and sugar, then add the eggs.\nMix lemon juice with sugar & pour over."
           (:steps r)))
    (is (= "https://example.com/img/lemon.png" (:image-url r)) "relative image URLs resolve against the page")))

(deftest json-ld-simple-test
  (let [r (web/parse-html (fixture "json-ld-simple.html") "https://example.com/a/toast")]
    (is (= "Toast" (:title r)) "invalid JSON-LD blocks are skipped")
    (is (= 2 (:servings r)))
    (is (= [nil 3] [(:prep_minutes r) (:cook_minutes r)]))
    (is (= "Toast the bread.\nButter it.\nEat." (:steps r)))
    (is (= "https://example.com/a/toast.jpg" (:image-url r)))))

(deftest microdata-test
  (let [r (web/parse-html (fixture "microdata.html") "https://example.com/granola")]
    (is (= "Maple Granola" (:title r)) "the nested author's name isn't the title")
    (is (= "Crunchy clusters." (:description r)))
    (is (= [10 40 6] [(:prep_minutes r) (:cook_minutes r) (:servings r)]))
    (is (= ["3 cups rolled oats" "1/2 cup maple syrup"] (:lines r)))
    (is (= "Heat the oven to 150C.\nMix everything and bake for 40 minutes." (:steps r)))
    (is (= "https://example.com/media/granola.jpg" (:image-url r)))))

(deftest no-recipe-test
  (is (nil? (web/parse-html (fixture "no-recipe.html") "https://example.com/about"))))

(deftest duration-test
  (is (= 90 (web/duration-minutes "PT1H30M")))
  (is (= 45 (web/duration-minutes "P0DT45M")))
  (is (= 1500 (web/duration-minutes "P1DT1H")))
  (is (nil? (web/duration-minutes "PT")))
  (is (nil? (web/duration-minutes "20 minutes"))))

;;; Through a local web server

(defn- png-bytes []
  (let [out (ByteArrayOutputStream.)]
    (ImageIO/write (BufferedImage. 40 30 BufferedImage/TYPE_INT_RGB) "png" out)
    (.toByteArray out)))

(defn- call-with-server
  "Run (F base-url) with a local server answering ROUTES: path -> [status content-type
  body-bytes-or-string headers-map]. fetch's private address check is off for it."
  [routes f]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [[status ctype body headers] (get routes (.getPath (.getRequestURI ex)) [404 "text/plain" "nope"])
                              bytes (if (string? body) (.getBytes ^String body "UTF-8") body)]
                          (when (= :slow body) (Thread/sleep 1500))
                          (doseq [[k v] (merge {"Content-Type" ctype} headers)]
                            (.add (.getResponseHeaders ex) k v))
                          (if (= :slow body)
                            (.sendResponseHeaders ex 200 -1)
                            (do (.sendResponseHeaders ex status (if (zero? (alength ^bytes bytes)) -1 (alength ^bytes bytes)))
                                (with-open [out (.getResponseBody ex)] (.write out ^bytes bytes))))
                          (.close ex)))))
    (.start server)
    (try
      (binding [fetch/*allow-private-addresses* true]
        (f (str "http://127.0.0.1:" (.getPort (.getAddress server)))))
      (finally (.stop server 0)))))

(defn- post [sid uri & {:as opts}]
  (let [resp (larder/app (apply tu/request :post uri :sid sid (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(deftest import-flow-test
  (call-with-server
   {"/lemon" [200 "text/html; charset=utf-8" (fixture "json-ld-graph.html")]
    "/img/lemon.png" [200 "image/png" (png-bytes)]
    "/about" [200 "text/html" (fixture "no-recipe.html")]
    "/toast" [200 "text/html" (fixture "json-ld-simple.html")]
    "/moved" [301 "text/html" "" {"Location" "/lemon"}]}
   (fn [base]
     (let [sid (login "admin")]
       (testing "the Add page offers both choices"
         (let [body (tu/body-str (larder/app (tu/request :get "/app/recipes/new" :sid sid)))]
           (is (str/includes? body "Import from URL"))
           (is (str/includes? body "Start a blank recipe"))))
       (testing "a recipe page is imported, published, with its image and source"
         (let [resp (post sid "/api/recipes/import" :form {:url (str base "/lemon")})
               id (some-> (get-in resp [:headers "HX-Push-Url"]) (str/replace "/app/recipes/" "") parse-long)
               r (recipes/by-id id)]
           (is (str/includes? (:text resp) "Imported and published"))
           (is (= "published" (:status r)))
           (is (= (str base "/lemon") (:source_url r)))
           (is (some? (images/media-file (:image_path r))))
           (is (= ["butter" "sugar" "egg" "lemon"] (map :ingredient_name (:ingredients r)))
               "lines go through the parser and matcher")
           (is (nil? (:rating r)) "other sites' ratings aren't ours")))
       (testing "redirects are followed"
         (is (str/includes? (:text (post sid "/api/recipes/import" :form {:url (str base "/moved")})) "Lemon Drizzle Cake")))
       (testing "a missing image is a warning, not a failure"
         (let [resp (post sid "/api/recipes/import" :form {:url (str base "/toast")})]
           (is (str/includes? (:text resp) "image couldn"))
           (is (str/includes? (:text resp) "Toast"))))
       (testing "a page without recipe data offers the blank editor with the address"
         (let [resp (post sid "/api/recipes/import" :form {:url (str base "/about")})]
           (is (str/includes? (:text resp) "doesn&apos;t have recipe data"))
           (is (str/includes? (:text resp) (str "source_url=" (java.net.URLEncoder/encode (str base "/about") "UTF-8"))))))
       (testing "the blank editor takes the address"
         (is (str/includes? (tu/body-str (larder/app (tu/request :get "/app/recipes/new" :sid sid
                                                                 :query "blank=1&source_url=https%3A%2F%2Fx.example%2Fr")))
                            "value=\"https://x.example/r\"")))
       (testing "fetch errors are reported"
         (is (str/includes? (:text (post sid "/api/recipes/import" :form {:url (str base "/missing")})) "HTTP 404")))))))

;;; Fetch safety

(deftest fetch-safety-test
  (testing "only http and https"
    (doseq [u ["file:///etc/passwd" "ftp://example.com/x" "javascript:alert(1)" "example.com/recipe" ""]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"http" (fetch/check-url! u)) u)))
  (testing "private, loopback and link-local addresses are refused"
    (doseq [u ["http://127.0.0.1/" "http://localhost:8085/" "http://10.0.0.1/" "http://169.254.169.254/latest/meta-data"
               "http://[::1]/" "http://192.168.1.10/" "http://0.0.0.0/"]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"private network" (fetch/check-url! u)) u)))
  (testing "credentials in the address are refused"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"user name" (fetch/check-url! "http://user:pw@8.8.8.8/"))))
  (testing "the import page reports a refused address"
    (let [sid (login "admin")]
      (is (str/includes? (:text (post sid "/api/recipes/import" :form {:url "http://127.0.0.1:1/x"})) "private network"))))
  (testing "every redirect hop is checked again"
    (let [checked (atom [])]
      (call-with-server
       {"/start" [302 "text/html" "" {"Location" "/inside"}]
        "/inside" [200 "text/html" "secret"]}
       (fn [base]
         (with-redefs [fetch/check-url! (let [real fetch/check-url!]
                                          (fn [u] (swap! checked conj u)
                                            (if (str/ends-with? u "/inside")
                                              (throw (ex-info "private network" {:user-message "That address is on a private network, so Larder won't fetch it."}))
                                              (real u))))]
           (is (thrown-with-msg? clojure.lang.ExceptionInfo #"private" (fetch/fetch (str base "/start"))))
           (is (= [(str base "/start") (str base "/inside")] @checked)))))))
  (testing "responses over the size limit are refused"
    (call-with-server
     {"/big" [200 "text/html" (byte-array (inc fetch/default-max-bytes))]}
     (fn [base]
       (is (thrown-with-msg? clojure.lang.ExceptionInfo #"larger than 5 MB" (fetch/fetch (str base "/big")))))))
  (testing "slow sites time out"
    (with-redefs [fetch/timeout-ms 300]
      (call-with-server
       {"/slow" [200 "text/html" :slow]}
       (fn [base]
         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"too long" (fetch/fetch (str base "/slow")))))))))
