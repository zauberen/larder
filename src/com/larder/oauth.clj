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

(ns com.larder.oauth
  "Larder as its own OAuth 2.1 authorization server for the MCP endpoint: clients
  (dynamic registration or client ID metadata documents), single-use authorization
  codes with PKCE S256, and access/refresh tokens bound to the MCP resource. Access
  tokens last an hour; refresh tokens 30 days and rotate on every use."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [com.larder.api-keys :as ak]
            [com.larder.database.db :as db]
            [com.larder.fetch :as fetch]
            [com.larder.util :as u])
  (:import (java.net URI)
           (java.security MessageDigest)
           (java.util Base64)))

(def scope "larder")
(def code-ttl-ms (* 60 1000))
(def access-ttl-ms (* 60 60 1000))
(def refresh-ttl-ms (* 30 24 60 60 1000))
(def metadata-refetch-ms (* 24 60 60 1000))

(defn- oauth-error
  "Throw an OAuth error (error code, description, HTTP status)."
  [error description & [status]]
  (throw (ex-info description {:oauth-error error :status (or status 400)})))

;;; URIs

(defn- parse-uri [s] (try (URI. (str s)) (catch Exception _ nil)))

(defn loopback-host?
  [host]
  (contains? #{"localhost" "127.0.0.1" "[::1]" "::1"} (some-> host str/lower-case)))

(defn valid-redirect-uri?
  "Redirect URIs must be absolute https URLs, or http on a loopback address, without a fragment."
  [s]
  (let [uri (parse-uri s)
        scheme (some-> uri .getScheme str/lower-case)]
    (boolean (and uri (.isAbsolute uri) (nil? (.getRawFragment uri)) (not (str/blank? (.getHost uri)))
                  (or (= "https" scheme) (and (= "http" scheme) (loopback-host? (.getHost uri))))))))

(defn canonical-resource
  "A resource URI in comparable form: lower-case scheme and host, no trailing slash, no default port."
  [s]
  (when-let [uri (parse-uri s)]
    (when (and (.getScheme uri) (.getHost uri) (nil? (.getRawFragment uri)))
      (let [scheme (str/lower-case (.getScheme uri))
            port (.getPort uri)
            port (when-not (or (neg? port) (and (= scheme "https") (= port 443)) (and (= scheme "http") (= port 80))) port)]
        (str scheme "://" (str/lower-case (.getHost uri)) (when port (str ":" port))
             (str/replace (or (.getRawPath uri) "") #"/+$" ""))))))

(defn resource-matches?
  "True when the requested RESOURCE names this server's MCP endpoint (MCP-URL) or its site root."
  [resource mcp-url]
  (let [r (canonical-resource resource)
        m (canonical-resource mcp-url)]
    (boolean (and r m (or (= r m) (= r (str/replace m #"/mcp$" "")))))))

;;; Clients

(defn- client-row->map
  [row]
  (when row (update row :redirect_uris #(json/read-str %))))

(defn client-by-id
  [client-id]
  (client-row->map (first (db/run-db-query ["select * from oauth_clients where client_id = ?" client-id]))))

(defn metadata-client-id?
  "Client ID metadata documents: an https URL with a path."
  [client-id]
  (let [uri (parse-uri client-id)]
    (boolean (and uri (= "https" (some-> (.getScheme uri) str/lower-case))
                  (not (str/blank? (.getRawPath uri))) (not= "/" (.getRawPath uri))
                  (nil? (.getRawFragment uri)) (nil? (.getRawUserInfo uri))))))

(defn validate-registration
  "[client-name redirect-uris] from registration metadata M (string keys), or an OAuth error."
  [m]
  (let [uris (get m "redirect_uris")
        name (get m "client_name")]
    (when-not (and (sequential? uris) (seq uris) (every? string? uris))
      (oauth-error "invalid_redirect_uri" "redirect_uris must be a non-empty list."))
    (when (> (count uris) 10) (oauth-error "invalid_redirect_uri" "At most 10 redirect URIs."))
    (when-not (every? valid-redirect-uri? uris)
      (oauth-error "invalid_redirect_uri" "Redirect URIs must use https, or http on localhost."))
    (when (and name (or (not (string? name)) (> (count name) 100)))
      (oauth-error "invalid_client_metadata" "client_name must be a string of up to 100 characters."))
    [(or (some-> name str/trim not-empty) "Unnamed client") (vec (distinct uris))]))

(defn register!
  "Dynamic client registration. Returns the registration response map."
  [m]
  (let [[name uris] (validate-registration m)
        client-id (str "lc_" (ak/random-token 24))
        t (u/now-ms)]
    ;; Registrations that never got a token are cleared after a week.
    (db/exe-db-stmt [(str "delete from oauth_clients where kind = 'registered' and created_at < ?"
                          " and client_id not in (select client_id from oauth_tokens)")
                     (- t (* 7 24 3600 1000))])
    (db/insert! ["insert into oauth_clients (client_id, kind, name, redirect_uris, created_at) values (?, 'registered', ?, ?, ?)"
                 client-id name (json/write-str uris) t])
    {:client_id client-id :client_id_issued_at (quot t 1000) :client_name name :redirect_uris uris
     :grant_types ["authorization_code" "refresh_token"] :response_types ["code"]
     :token_endpoint_auth_method "none"}))

(defn- fetch-metadata-document!
  "Fetch, validate and store the client ID metadata document at CLIENT-ID."
  [client-id]
  (let [{:keys [status body]} (try (fetch/fetch client-id :accept "application/json" :max-bytes (* 64 1024))
                                   (catch clojure.lang.ExceptionInfo e
                                     (oauth-error "invalid_client" (str "The client's metadata couldn't be fetched: "
                                                                        (or (:user-message (ex-data e)) "error")))))
        doc (when (= 200 status) (try (json/read-str (String. ^bytes body "UTF-8")) (catch Exception _ nil)))]
    (when-not (map? doc) (oauth-error "invalid_client" "The client's metadata document isn't valid JSON."))
    (when-not (= client-id (get doc "client_id"))
      (oauth-error "invalid_client" "The client's metadata document names a different client_id."))
    (when-not (string? (get doc "client_name"))
      (oauth-error "invalid_client" "The client's metadata document has no client_name."))
    (let [[name uris] (validate-registration doc)
          t (u/now-ms)]
      (db/exe-db-stmt [(str "insert into oauth_clients (client_id, kind, name, redirect_uris, created_at, fetched_at) values (?, 'metadata', ?, ?, ?, ?)"
                            " on conflict(client_id) do update set name = excluded.name, redirect_uris = excluded.redirect_uris, fetched_at = excluded.fetched_at")
                       client-id name (json/write-str uris) t t])
      (client-by-id client-id))))

(defn resolve-client
  "The client for CLIENT-ID: registered, or a metadata document (fetched again after a day)."
  [client-id]
  (let [existing (client-by-id client-id)]
    (cond
      (and existing (= "registered" (:kind existing))) existing
      (metadata-client-id? client-id)
      (if (and existing (< (- (u/now-ms) (:fetched_at existing 0)) metadata-refetch-ms))
        existing
        (fetch-metadata-document! client-id))
      :else (oauth-error "invalid_client" "Unknown client."))))

;;; Codes

(defn- s256
  [verifier]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder))
                   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes (str verifier) "US-ASCII"))))

(defn issue-code!
  "Create an authorization code. Returns it."
  [{:keys [client-id user-id code-challenge redirect-uri resource]}]
  (let [code (ak/random-token 32)]
    (db/insert! [(str "insert into oauth_codes (code_hash, client_id, user_id, code_challenge, redirect_uri, resource, scope, family, expires_at)"
                      " values (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                 (ak/sha256-hex code) client-id user-id code-challenge redirect-uri resource scope
                 (ak/random-token 16) (+ (u/now-ms) code-ttl-ms)])
    code))

;;; Tokens

(defn revoke-family!
  [family]
  (db/exe-db-stmt ["update oauth_tokens set revoked_at = ? where family = ? and revoked_at is null" (u/now-ms) family]))

(defn- issue-tokens!
  [{:keys [client-id user-id resource family]}]
  (let [access (ak/random-token 32)
        refresh (ak/random-token 32)
        t (u/now-ms)]
    (db/insert! [(str "insert into oauth_tokens (access_hash, refresh_hash, client_id, user_id, resource, scope, family,"
                      " access_expires_at, refresh_expires_at, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                 (ak/sha256-hex access) (ak/sha256-hex refresh) client-id user-id resource scope family
                 (+ t access-ttl-ms) (+ t refresh-ttl-ms) t])
    {:access_token access :token_type "Bearer" :expires_in (quot access-ttl-ms 1000)
     :refresh_token refresh :scope scope}))

(defn- enabled-user? [user-id]
  (= 1 (:enabled (first (db/run-db-query ["select enabled from users where id = ?" user-id])))))

(defn exchange-code!
  "The authorization_code grant. PARAMS are the form fields (keyword keys); MCP-URL is
  this server's resource."
  [{:keys [code client_id redirect_uri code_verifier resource]} mcp-url]
  (when (some str/blank? [code client_id redirect_uri code_verifier])
    (oauth-error "invalid_request" "code, client_id, redirect_uri and code_verifier are required."))
  (let [row (first (db/run-db-query ["select * from oauth_codes where code_hash = ?" (ak/sha256-hex code)]))]
    (cond
      (nil? row) (oauth-error "invalid_grant" "Unknown authorization code.")
      (:used_at row) (do (revoke-family! (:family row))
                         (oauth-error "invalid_grant" "The authorization code was already used."))
      (< (:expires_at row) (u/now-ms)) (oauth-error "invalid_grant" "The authorization code has expired.")
      (not= client_id (:client_id row)) (oauth-error "invalid_grant" "The code was issued to another client.")
      (not= redirect_uri (:redirect_uri row)) (oauth-error "invalid_grant" "redirect_uri doesn't match the authorization request.")
      (not (ak/constant-time= (s256 code_verifier) (:code_challenge row))) (oauth-error "invalid_grant" "The PKCE code_verifier doesn't match.")
      (and (seq resource) (not (resource-matches? resource mcp-url))) (oauth-error "invalid_target" "Unknown resource.")
      (not (enabled-user? (:user_id row))) (oauth-error "invalid_grant" "That account is disabled.")
      :else
      (let [[claimed] (db/exe-db-stmt ["update oauth_codes set used_at = ? where id = ? and used_at is null" (u/now-ms) (:id row)])]
        (when (zero? claimed) (oauth-error "invalid_grant" "The authorization code was already used."))
        (issue-tokens! {:client-id client_id :user-id (:user_id row) :resource (:resource row) :family (:family row)})))))

(defn refresh!
  "The refresh_token grant: the presented token is revoked and a new pair issued. A
  refresh token presented after it was rotated revokes its whole family."
  [{:keys [refresh_token client_id resource]} mcp-url]
  (when (some str/blank? [refresh_token client_id])
    (oauth-error "invalid_request" "refresh_token and client_id are required."))
  (let [row (first (db/run-db-query ["select * from oauth_tokens where refresh_hash = ?" (ak/sha256-hex refresh_token)]))]
    (cond
      (nil? row) (oauth-error "invalid_grant" "Unknown refresh token.")
      (:revoked_at row) (do (revoke-family! (:family row))
                            (oauth-error "invalid_grant" "The refresh token was already used or revoked."))
      (< (:refresh_expires_at row) (u/now-ms)) (oauth-error "invalid_grant" "The refresh token has expired.")
      (not= client_id (:client_id row)) (oauth-error "invalid_grant" "The refresh token was issued to another client.")
      (and (seq resource) (not (resource-matches? resource mcp-url))) (oauth-error "invalid_target" "Unknown resource.")
      (not (enabled-user? (:user_id row))) (oauth-error "invalid_grant" "That account is disabled.")
      :else
      (let [[claimed] (db/exe-db-stmt ["update oauth_tokens set revoked_at = ? where id = ? and revoked_at is null" (u/now-ms) (:id row)])]
        (when (zero? claimed)
          (revoke-family! (:family row))
          (oauth-error "invalid_grant" "The refresh token was already used."))
        (issue-tokens! {:client-id client_id :user-id (:user_id row) :resource (:resource row) :family (:family row)})))))

(defn lookup-access-token
  "The active token row for access token TOKEN issued for MCP-URL, or nil."
  [token mcp-url]
  (when (and (string? token) (re-matches #"[A-Za-z0-9_-]{43}" token))
    (when-let [row (first (db/run-db-query ["select * from oauth_tokens where access_hash = ? and revoked_at is null" (ak/sha256-hex token)]))]
      (when (and (> (:access_expires_at row) (u/now-ms)) (resource-matches? (:resource row) mcp-url))
        (db/exe-db-stmt ["update oauth_tokens set last_used_at = ? where id = ?" (u/now-ms) (:id row)])
        row))))

;;; Connections

(defn connections
  "Clients with live (unexpired refresh, unrevoked) tokens, for USER-ID or everyone."
  [& [user-id]]
  (db/run-db-query (into [(str "select t.client_id, t.user_id, u.username, coalesce(c.name, t.client_id) as client_name,"
                               " min(t.created_at) as created_at, max(coalesce(t.last_used_at, t.created_at)) as last_used_at"
                               " from oauth_tokens t join users u on u.id = t.user_id left join oauth_clients c on c.client_id = t.client_id"
                               " where t.revoked_at is null and t.refresh_expires_at > ?"
                               (when user-id " and t.user_id = ?")
                               " group by t.client_id, t.user_id order by last_used_at desc")
                          (u/now-ms)]
                         (when user-id [user-id]))))

(defn revoke-connection!
  "Revoke every token CLIENT-ID holds for USER-ID."
  [client-id user-id]
  (db/exe-db-stmt ["update oauth_tokens set revoked_at = ? where client_id = ? and user_id = ? and revoked_at is null"
                   (u/now-ms) client-id user-id]))
