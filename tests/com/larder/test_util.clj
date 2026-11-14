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

(ns com.larder.test-util
  "Shared fixtures: an isolated settings map, filesystem and sqlite database per test."
  (:require [clojure.java.io :as io]
            [clojure.string]
            [com.larder.database.migration :as m]
            [com.larder.ratelimit :as rl]
            [com.larder.settings :as s]
            [com.larder.users :as users]))

(def ^:dynamic *root* nil)

(defn- delete-recursively
  [f]
  (when (.isDirectory f)
    (doseq [child (.listFiles f)]
      (delete-recursively child)))
  (io/delete-file f true))

(defn temp-settings
  "Default settings with every path pointed into ROOT."
  [root]
  (merge (s/get-default-settings)
         {:data-root root
          :sqlite-db-path (str root "/larder.db")
          :debug false}))

(defn call-with-temp-env
  "Run F with settings, files and database isolated in a fresh temp directory. The
  database starts with an admin account named admin (password admin)."
  [f]
  (let [root (.getCanonicalPath (.toFile (java.nio.file.Files/createTempDirectory
                                          "larder-test"
                                          (into-array java.nio.file.attribute.FileAttribute []))))
        settings (temp-settings root)]
    (reset! m/migration-chk-ran nil)
    (rl/reset-all!)
    (try
      (with-redefs [s/get-settings (constantly settings)
                    s/config-path (constantly (str root "/larder.edn"))]
        (binding [*root* root]
          (users/create! "admin" "admin" "admin")
          (f)))
      (finally
        (reset! m/migration-chk-ran nil)
        (rl/reset-all!)
        (delete-recursively (io/file root))))))

(defmacro with-temp-env
  [& body]
  `(call-with-temp-env (fn [] ~@body)))

(defn temp-env-fixture
  "clojure.test :each fixture wrapping a test in with-temp-env."
  [f]
  (call-with-temp-env f))

(defn request
  "Build a ring request. FORM is a map of form fields sent urlencoded in the body; a
  sequential value sends the field once per element. BYTES is sent as the raw body
  (application/octet-stream) instead."
  [method uri & {:keys [form bytes sid headers query remote-addr]}]
  (let [body (cond
               bytes bytes
               form (.getBytes ^String
                               (clojure.string/join "&" (for [[k vs] form
                                                              v (if (sequential? vs) vs [vs])]
                                                          (str (java.net.URLEncoder/encode (name k) "UTF-8") "="
                                                               (java.net.URLEncoder/encode (str v) "UTF-8"))))
                               "UTF-8"))]
    (cond-> {:request-method method
             :uri uri
             :scheme :http
             :server-name "localhost"
             :server-port 8085
             :remote-addr (or remote-addr "127.0.0.1")
             :headers (merge {"host" "larder.example.com:8085"}
                             (when sid {"cookie" (str "larder-session-id=" sid)})
                             (when body {"content-type" (if bytes
                                                          "application/octet-stream"
                                                          "application/x-www-form-urlencoded")
                                         "content-length" (str (alength ^bytes body))})
                             headers)}
      query (assoc :query-string query)
      body (assoc :body (java.io.ByteArrayInputStream. ^bytes body)))))

(defn body-str
  [resp]
  (let [b (:body resp)]
    (cond (string? b) b
          (nil? b) ""
          :else (slurp b))))
