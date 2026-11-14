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

(ns com.larder.oauth-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [com.larder :as larder]
            [com.larder.api-keys :as api-keys]
            [com.larder.database.db :as db]
            [com.larder.fetch :as fetch]
            [com.larder.global-settings :as gs]
            [com.larder.mcp-test :refer [post-mcp]]
            [com.larder.oauth :as oauth]
            [com.larder.test-util :as tu]
            [com.larder.users :as users]
            [com.larder.web-test :refer [login]])
  (:import (java.net URLDecoder URLEncoder)
           (java.security MessageDigest)
           (java.util Base64)))

(use-fixtures :each tu/temp-env-fixture)

(def site "http://larder.example.com:8085")
(def redirect "http://localhost:33418/callback")
(def verifier "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk-and-some-more-entropy")

(defn- challenge [v]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes ^String v "US-ASCII"))))

(defn- enc [s] (URLEncoder/encode (str s) "UTF-8"))

(defn- req [method uri & {:as opts}]
  (let [resp (larder/app (apply tu/request method uri (mapcat identity opts)))
        text (tu/body-str resp)]
    (assoc resp :text text :json (try (json/read-str text) (catch Exception _ nil)))))

(defn- register! []
  (get (:json (req :post "/oauth/register" :bytes (.getBytes (json/write-str {:client_name "Claude Code" :redirect_uris [redirect]}))
                   :headers {"content-type" "application/json"}))
       "client_id"))

(defn- auth-query [client-id & {:as over}]
  (str/join "&" (for [[k v] (merge {:response_type "code" :client_id client-id :redirect_uri redirect
                                    :code_challenge (challenge verifier) :code_challenge_method "S256"
                                    :state "xyz" :resource (str site "/mcp") :scope "larder"} over)
                      :when (some? v)]
                  (str (name k) "=" (enc v)))))

(defn- query-params [location]
  (into {} (for [kv (str/split (second (str/split location #"\?" 2)) #"&")
                 :let [[k v] (str/split kv #"=" 2)]]
             [k (URLDecoder/decode (str v) "UTF-8")])))

(defn- approve! [sid client-id]
  (let [form (into {:decision "approve"} (for [[k v] (query-params (str "?" (auth-query client-id)))] [(keyword k) v]))]
    (req :post "/oauth/authorize" :sid sid :form form)))

(defn- code-for [sid client-id]
  (get (query-params (get-in (approve! sid client-id) [:headers "Location"])) "code"))

(defn- token-req [form] (req :post "/oauth/token" :form form))

(defn- tokens! [sid client-id]
  (:json (token-req {:grant_type "authorization_code" :code (code-for sid client-id) :client_id client-id
                     :redirect_uri redirect :code_verifier verifier :resource (str site "/mcp")})))

(defn- mcp-ok? [token] (= 200 (:status (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"} :token token))))

(deftest metadata-test
  (doseq [path ["/.well-known/oauth-protected-resource" "/.well-known/oauth-protected-resource/mcp"]]
    (let [{:keys [json headers]} (req :get path)]
      (is (= (str site "/mcp") (get json "resource")) path)
      (is (= [site] (get json "authorization_servers")))
      (is (= "*" (get headers "Access-Control-Allow-Origin")))))
  (let [{:keys [json]} (req :get "/.well-known/oauth-authorization-server")]
    (is (= site (get json "issuer")))
    (is (= ["S256"] (get json "code_challenge_methods_supported")))
    (is (true? (get json "client_id_metadata_document_supported")))
    (is (= (str site "/oauth/register") (get json "registration_endpoint"))))
  (testing "the 401 from /mcp names the scope"
    (is (str/includes? (get-in (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"}) [:headers "WWW-Authenticate"]) "scope=\"larder\"")))
  (testing "CORS preflight for browser clients"
    (let [resp (req :options "/oauth/token" :headers {"origin" "https://inspector.example" "access-control-request-method" "POST"})]
      (is (= 204 (:status resp)))
      (is (= "*" (get-in resp [:headers "Access-Control-Allow-Origin"]))))))

(deftest registration-test
  (is (str/starts-with? (register!) "lc_"))
  (doseq [uris [["http://evil.example/cb"] [] ["https://ok.example/cb#frag"] ["not a url"]]]
    (let [resp (req :post "/oauth/register" :bytes (.getBytes (json/write-str {:redirect_uris uris})) :headers {"content-type" "application/json"})]
      (is (= 400 (:status resp)) (pr-str uris))
      (is (= "invalid_redirect_uri" (get-in resp [:json "error"]))))))

(deftest authorize-flow-test
  (let [client (register!)
        sid (login "admin")]
    (testing "signed out: off to log in, and back afterwards"
      (let [loc (get-in (req :get "/oauth/authorize" :query (auth-query client)) [:headers "Location"])]
        (is (str/starts-with? loc "/login?next=%2Foauth%2Fauthorize"))
        (let [next (URLDecoder/decode (subs loc (count "/login?next=")) "UTF-8")]
          (is (str/includes? (:text (req :get "/login" :query (str "next=" (enc next)))) "name=\"next\""))
          (is (= next (get-in (req :post "/api/login" :form {:username "admin" :password "admin" :next next}) [:headers "HX-Redirect"]))))))
    (testing "the consent page names the client and where it sends you"
      (let [body (:text (req :get "/oauth/authorize" :sid sid :query (auth-query client)))]
        (is (str/includes? body "Claude Code"))
        (is (str/includes? body "<code>localhost</code>"))
        (is (str/includes? body "on this computer"))))
    (testing "a redirect URI that wasn't registered gets an error page, not a redirect"
      (let [resp (req :get "/oauth/authorize" :sid sid :query (auth-query client :redirect_uri "https://evil.example/cb"))]
        (is (= 400 (:status resp)))
        (is (nil? (get-in resp [:headers "Location"])))))
    (testing "missing PKCE or another method is sent back as an error"
      (doseq [over [{:code_challenge nil} {:code_challenge_method "plain"} {:resource "https://other.example/mcp"}]]
        (let [loc (get-in (req :get "/oauth/authorize" :sid sid :query (apply auth-query client (mapcat identity over))) [:headers "Location"])]
          (is (str/starts-with? loc redirect) (pr-str over))
          (is (#{"invalid_request" "invalid_target"} (get (query-params loc) "error")))
          (is (= "xyz" (get (query-params loc) "state"))))))
    (testing "deny"
      (let [form (into {:decision "deny"} (for [[k v] (query-params (str "?" (auth-query client)))] [(keyword k) v]))
            loc (get-in (req :post "/oauth/authorize" :sid sid :form form) [:headers "Location"])]
        (is (= "access_denied" (get (query-params loc) "error")))))
    (testing "the consent form only works from this site"
      (let [form (into {:decision "approve"} (for [[k v] (query-params (str "?" (auth-query client)))] [(keyword k) v]))]
        (is (= 403 (:status (req :post "/oauth/authorize" :sid sid :form form :headers {"origin" "https://evil.example"}))))))
    (testing "approve returns a code, the state and the issuer"
      (let [params (query-params (get-in (approve! sid client) [:headers "Location"]))]
        (is (= "xyz" (get params "state")))
        (is (= site (get params "iss")))
        (is (re-matches #"[A-Za-z0-9_-]{43}" (get params "code")))))))

(deftest token-test
  (let [client (register!)
        sid (login "admin")]
    (testing "PKCE, client and redirect must match"
      (let [code (code-for sid client)
            base {:grant_type "authorization_code" :code code :client_id client :redirect_uri redirect :code_verifier verifier}]
        (is (= "invalid_grant" (get-in (token-req (assoc base :code_verifier (str verifier "x"))) [:json "error"])))
        (is (= "invalid_grant" (get-in (token-req (assoc base :redirect_uri "http://localhost:1/x")) [:json "error"])))
        (is (= "invalid_grant" (get-in (token-req (assoc base :client_id "lc_other")) [:json "error"])))))
    (testing "a good exchange"
      (let [code (code-for sid client)
            resp (token-req {:grant_type "authorization_code" :code code :client_id client :redirect_uri redirect
                             :code_verifier verifier :resource (str site "/mcp")})
            {:strs [access_token refresh_token expires_in token_type]} (:json resp)]
        (is (= 200 (:status resp)))
        (is (= "no-store" (get-in resp [:headers "Cache-Control"])))
        (is (= ["Bearer" 3600] [token_type expires_in]))
        (is (mcp-ok? access_token))
        (testing "tokens are stored hashed"
          (is (empty? (db/run-db-query ["select 1 from oauth_tokens where access_hash = ? or refresh_hash = ?" access_token refresh_token]))))
        (testing "codes are single use; replaying one revokes what it issued"
          (is (= "invalid_grant" (get-in (token-req {:grant_type "authorization_code" :code code :client_id client
                                                     :redirect_uri redirect :code_verifier verifier}) [:json "error"])))
          (is (not (mcp-ok? access_token))))))
    (testing "codes expire after 60 seconds"
      (let [code (code-for sid client)]
        (db/exe-db-stmt ["update oauth_codes set expires_at = ?" (- (System/currentTimeMillis) 1)])
        (is (= "invalid_grant" (get-in (token-req {:grant_type "authorization_code" :code code :client_id client
                                                   :redirect_uri redirect :code_verifier verifier}) [:json "error"])))))
    (testing "refresh tokens rotate; a reused one revokes the family"
      (let [{:strs [refresh_token]} (tokens! sid client)
            r1 (:json (token-req {:grant_type "refresh_token" :refresh_token refresh_token :client_id client}))
            new-access (get r1 "access_token")]
        (is (mcp-ok? new-access))
        (is (not= refresh_token (get r1 "refresh_token")))
        (is (= "invalid_grant" (get-in (token-req {:grant_type "refresh_token" :refresh_token refresh_token :client_id client}) [:json "error"])))
        (is (not (mcp-ok? new-access)) "the stolen-token case: everything from that sign-in is revoked")))
    (testing "expired access tokens get 401 invalid_token"
      (let [{:strs [access_token]} (tokens! sid client)]
        (db/exe-db-stmt ["update oauth_tokens set access_expires_at = 1 where revoked_at is null"])
        (let [resp (post-mcp {:jsonrpc "2.0" :id 1 :method "ping"} :token access_token)]
          (is (= 401 (:status resp)))
          (is (str/includes? (get-in resp [:headers "WWW-Authenticate"]) "invalid_token")))))
    (testing "audience: a token issued for another resource isn't accepted"
      (let [{:strs [access_token]} (tokens! sid client)]
        (is (mcp-ok? access_token))
        (db/exe-db-stmt ["update oauth_tokens set resource = 'https://other.example/mcp'"])
        (is (not (mcp-ok? access_token)))))
    (testing "unsupported grants"
      (is (= "unsupported_grant_type" (get-in (token-req {:grant_type "password"}) [:json "error"]))))))

(deftest revocation-and-settings-test
  (let [client (register!)
        sid (login "admin")
        {:strs [access_token refresh_token]} (tokens! sid client)]
    (testing "the user sees the connection and can disconnect it"
      (is (str/includes? (tu/body-str (larder/app (tu/request :get "/app/settings/keys" :sid sid))) "Claude Code"))
      (larder/app (tu/request :post "/api/connections/revoke" :sid sid :form {:client_id client}))
      (is (not (mcp-ok? access_token)))
      (is (= "invalid_grant" (get-in (token-req {:grant_type "refresh_token" :refresh_token refresh_token :client_id client}) [:json "error"])))))
  (let [client (register!)
        sid (login "admin")
        {:strs [access_token]} (tokens! sid client)
        k (api-keys/create! 1 "key")]
    (testing "admins see every connection and can turn OAuth off"
      (is (str/includes? (tu/body-str (larder/app (tu/request :get "/app/settings/site" :sid sid))) "Connected OAuth apps"))
      (larder/app (tu/request :post "/api/site/mcp" :sid sid :form {:mcp_api_keys "1"}))
      (is (not (gs/enabled? "mcp_oauth")))
      (is (not (mcp-ok? access_token)))
      (is (mcp-ok? k) "API keys still work")
      (is (= 404 (:status (req :get "/.well-known/oauth-authorization-server"))))
      (is (= 403 (:status (req :get "/oauth/authorize" :sid sid :query (auth-query client)))))
      (gs/put! "mcp_oauth" "1")
      (is (mcp-ok? access_token)))
    (testing "disabled users' tokens stop working"
      (users/create! "spare" "password1" "admin")
      (users/set-enabled! 1 false)
      (is (not (mcp-ok? access_token))))))

(deftest client-metadata-document-test
  (let [client-id "https://claude.example/oauth/client.json"
        doc (atom {"client_id" client-id "client_name" "Claude (metadata)" "redirect_uris" [redirect]})
        fetches (atom 0)
        sid (login "admin")]
    (with-redefs [fetch/fetch (fn [url & _]
                                (swap! fetches inc)
                                (is (= client-id url))
                                {:status 200 :url url :content-type "application/json"
                                 :body (.getBytes (json/write-str @doc) "UTF-8")})]
      (testing "a URL client_id is fetched, validated and shown"
        (let [body (:text (req :get "/oauth/authorize" :sid sid :query (auth-query client-id)))]
          (is (str/includes? body "Claude (metadata)"))
          (is (str/includes? body "claude.example")))
        (let [{:strs [access_token]} (tokens! sid client-id)]
          (is (mcp-ok? access_token))))
      (testing "it is cached for a day"
        (is (= 1 @fetches)))
      (testing "a document naming another client_id is refused"
        (db/exe-db-stmt ["update oauth_clients set fetched_at = 0"])
        (reset! doc {"client_id" "https://evil.example/c.json" "client_name" "X" "redirect_uris" [redirect]})
        (let [resp (req :get "/oauth/authorize" :sid sid :query (auth-query client-id))]
          (is (= 400 (:status resp)))
          (is (str/includes? (:text resp) "different client_id")))))
    (testing "client ids that are neither registered nor https URLs are refused"
      (is (= 400 (:status (req :get "/oauth/authorize" :sid sid :query (auth-query "http://plain.example/c.json"))))))))

(deftest resource-matching-test
  (is (oauth/resource-matches? "HTTPS://Larder.Example.com/mcp/" "https://larder.example.com/mcp"))
  (is (oauth/resource-matches? "https://larder.example.com" "https://larder.example.com/mcp"))
  (is (oauth/resource-matches? "https://larder.example.com:443/mcp" "https://larder.example.com/mcp"))
  (is (not (oauth/resource-matches? "https://larder.example.com.evil/mcp" "https://larder.example.com/mcp")))
  (is (not (oauth/resource-matches? "https://larder.example.com/other" "https://larder.example.com/mcp"))))
