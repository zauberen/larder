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

(ns com.larder.onboarding-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [com.larder :as larder]
            [com.larder.database.migration :as m]
            [com.larder.global-settings :as gs]
            [com.larder.settings :as s]
            [com.larder.test-util :as tu]
            [com.larder.users :as users]
            [com.larder.web-test :refer [login]]))

(defn- post [uri & {:as opts}]
  (let [resp (larder/app (apply tu/request :post uri (mapcat identity opts)))]
    (assoc resp :text (tu/body-str resp))))

(defn- config-form
  [root]
  {:listen-port "8085" :debug "1" :upload-chunk-mb "2"
   :data-root (str root "/data") :sqlite-db-path (str root "/chosen.db")})

(defn- call-with-fresh-install
  "Run F as a first start: no settings file, no database, setup required. Unlike
  `tu/with-temp-env` the real settings code runs, reading a settings file in the temp dir."
  [f]
  (let [root (.getCanonicalPath (.toFile (java.nio.file.Files/createTempDirectory
                                          "larder-setup" (into-array java.nio.file.attribute.FileAttribute []))))
        config (str root "/larder.edn")
        started (atom 0)]
    (reset! m/migration-chk-ran nil)
    (try
      (with-redefs [s/config-path (constantly config)
                    s/get-default-settings (let [defaults (s/get-default-settings)]
                                             (constantly (merge defaults (tu/temp-settings root))))]
        (s/pin-restart-settings!)
        (reset! @#'larder/setup-required true)
        (with-redefs-fn {#'larder/on-startup #(swap! started inc)}
          #(f root config started)))
      (finally
        (reset! @#'larder/setup-required false)
        (s/pin-restart-settings! {})
        (reset! m/migration-chk-ran nil)
        (doseq [file (reverse (file-seq (io/file root)))]
          (io/delete-file file true))))))

(deftest setup-gate-test
  (call-with-fresh-install
   (fn [_root _config _started]
     (is (= "/setup" (get-in (larder/app (tu/request :get "/app")) [:headers "Location"])))
     (is (= "/setup" (get-in (larder/app (tu/request :get "/login")) [:headers "Location"])))
     (is (= "/setup" (get-in (larder/app (tu/request :get "/" :headers {"hx-request" "true"})) [:headers "HX-Redirect"])))
     (let [page (larder/app (tu/request :get "/setup"))]
       (is (= 200 (:status page)))
       (is (str/includes? (tu/body-str page) "Finish setup")))
     (testing "static files are still served"
       (is (= 200 (:status (larder/app (tu/request :get "/css/setup.css")))))))))

(deftest setup-validation-test
  (call-with-fresh-install
   (fn [root config started]
     (let [form (merge (config-form root) {:username "boss" :password "longenough" :confirm "different"
                                           :site_name "Home" :listen-port "99999"
                                           :data-root "relative/path"})
           resp (post "/api/setup" :form form)]
       (is (str/includes? (:text resp) "match"))
       (is (str/includes? (:text resp) "web UI port"))
       (is (str/includes? (:text resp) "absolute path"))
       (testing "what was typed is shown again"
         (is (str/includes? (:text resp) "relative/path")))
       (is (not (.exists (io/file config))))
       (is (zero? @started))))))

(deftest setup-completes-test
  (call-with-fresh-install
   (fn [root config started]
     (let [resp (post "/api/setup" :form (merge (config-form root)
                                                {:username "boss" :password "longenough" :confirm "longenough"
                                                 :site_name "Our Kitchen"}))
           sid (second (re-find #"larder-session-id=([^;]+)" (get-in resp [:headers "Set-Cookie"] "")))]
       (is (= "/app" (get-in resp [:headers "HX-Redirect"])))
       (testing "the configuration is written"
         (let [saved (edn/read-string (slurp config))]
           (is (= 8085 (:listen-port saved)))
           (is (= 2 (:upload-chunk-mb saved)))
           (is (= (str root "/data") (:data-root saved)))
           (is (.isDirectory (io/file root "data")))))
       (testing "the admin lives in the chosen database"
         (is (.exists (io/file root "chosen.db")))
         (is (users/check-password "boss" "longenough"))
         (is (= "admin" (:role (users/by-username "boss"))))
         (is (nil? (users/by-username "admin"))))
       (is (= "Our Kitchen" (gs/site-name)))
       (is (= 1 @started))
       (testing "the new admin is logged in and the app is open"
         (is (= 200 (:status (larder/app (tu/request :get "/app" :sid sid))))))
       (testing "setup can't be run twice"
         (is (= "/app" (get-in (larder/app (tu/request :get "/setup")) [:headers "Location"])))
         (is (= 404 (:status (post "/api/setup" :form {:username "evil"}))))
         (is (nil? (users/by-username "evil"))))))))

(deftest setup-port-change-test
  (call-with-fresh-install
   (fn [root _config _started]
     (let [resp (post "/api/setup" :form (merge (config-form root)
                                                {:listen-port "9090" :username "boss" :password "longenough"
                                                 :confirm "longenough" :site_name "Home"}))]
       (is (str/includes? (:text resp) "port 9090"))
       (is (some? (get-in resp [:headers "Set-Cookie"])))
       (testing "the running port stays until a restart"
         (is (= 8085 (:listen-port (s/get-settings))))
         (is (= [:listen-port] (s/pending-restart))))))))

(deftest server-config-card-test
  (tu/with-temp-env
    (let [sid (login "admin")
          root tu/*root*]
      (is (str/includes? (tu/body-str (larder/app (tu/request :get "/app/settings/site" :sid sid))) "Server configuration"))
      (testing "validation"
        (is (str/includes? (:text (post "/api/site/config" :sid sid :form (assoc (config-form root) :upload-chunk-mb "999")))
                           "upload piece size")))
      (testing "saving writes the settings file"
        (is (str/includes? (:text (post "/api/site/config" :sid sid :form (config-form root))) "Saved"))
        (is (= 2 (:upload-chunk-mb (edn/read-string (slurp (s/config-path))))))))))
