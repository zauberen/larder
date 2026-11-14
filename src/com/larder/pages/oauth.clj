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

(ns com.larder.pages.oauth
  "OAuth endpoints: protected resource and authorization server metadata, the authorize
  endpoint with its consent page, the token endpoint and dynamic client registration."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [com.larder.auth :as auth]
            [com.larder.global-settings :as gs]
            [com.larder.oauth :as oauth]
            [com.larder.pages.layout :as layout]
            [com.larder.ratelimit :as rl]
            [com.larder.response :as r]
            [com.larder.ui :as ui])
  (:import (java.net URI URLEncoder)))

(defn- site [req] (layout/site-url req))
(defn mcp-url [req] (str (site req) "/mcp"))

(defn- json-resp
  [status body & [headers]]
  {:status status
   :headers (merge {"Content-Type" "application/json" "Cache-Control" "no-store"} headers)
   :body (json/write-str body)})

(defn- oauth-disabled? [] (not (gs/enabled? "mcp_oauth")))

(defn- error-json
  [e]
  (let [{:keys [oauth-error status]} (ex-data e)]
    (json-resp (or status 400) {:error oauth-error :error_description (ex-message e)})))

;;; Metadata

(defn protected-resource
  [req]
  (json-resp 200 {:resource (mcp-url req)
                  :authorization_servers [(site req)]
                  :scopes_supported [oauth/scope]
                  :bearer_methods_supported ["header"]
                  :resource_name (gs/site-name)}))

(defn authorization-server
  [req]
  (if (oauth-disabled?)
    {:status 404 :headers {"Content-Type" "text/plain"} :body "OAuth sign-in is turned off."}
    (json-resp 200 {:issuer (site req)
                    :authorization_endpoint (str (site req) "/oauth/authorize")
                    :token_endpoint (str (site req) "/oauth/token")
                    :registration_endpoint (str (site req) "/oauth/register")
                    :scopes_supported [oauth/scope]
                    :response_types_supported ["code"]
                    :response_modes_supported ["query"]
                    :grant_types_supported ["authorization_code" "refresh_token"]
                    :token_endpoint_auth_methods_supported ["none"]
                    :code_challenge_methods_supported ["S256"]
                    :client_id_metadata_document_supported true
                    :authorization_response_iss_parameter_supported true})))

;;; Authorize

(defn- with-query
  [uri params]
  (str uri (if (str/includes? uri "?") "&" "?")
       (str/join "&" (for [[k v] params :when (some? v)]
                       (str (name k) "=" (URLEncoder/encode (str v) "UTF-8"))))))

(defn- redirect [url] {:status 302 :headers {"Location" url "Cache-Control" "no-store"} :body ""})

(defn- page
  [title & body]
  (r/h-respond
   (ui/std-header (str title " · " (gs/site-name))
                  (list (ui/link-stylesheet "/css/main.css") (ui/link-stylesheet "/css/setup.css"))
                  [:body.setup-page
                   [:div.setup-container
                    [:div.setup-brand (ui/app-logo-icon) [:span (gs/site-name)]]
                    (into [:section.card.consent] body)]])))

(defn- error-page
  [status message]
  (assoc (page "Can't connect" [:h1.dashboard-title "Can't connect"] [:p message]) :status status))

(def ^:private auth-param-keys [:response_type :client_id :redirect_uri :code_challenge :code_challenge_method :state :scope :resource])

(defn- check-request
  "Validate an authorization request. Returns {:client :params} or, for errors that
  can't be sent back to the client, {:page response}; errors that can are
  {:redirect url}."
  [req params]
  (if (oauth-disabled?)
    {:page (error-page 403 "OAuth sign-in is turned off on this server.")}
    (let [{:keys [client_id redirect_uri response_type code_challenge code_challenge_method state resource]} params
          client (try (when (seq client_id) (oauth/resolve-client client_id))
                      (catch clojure.lang.ExceptionInfo e {:error (ex-message e)}))]
      (cond
        (or (nil? client) (:error client)) {:page (error-page 400 (or (:error client) "The request names no client."))}
        (not (some #{redirect_uri} (:redirect_uris client)))
        {:page (error-page 400 "The redirect address doesn't match one the client registered.")}
        :else
        (let [fail (fn [error desc] {:redirect (with-query redirect_uri {:error error :error_description desc :state state :iss (site req)})})]
          (cond
            (not= "code" response_type) (fail "unsupported_response_type" "Only the code flow is supported.")
            (not (re-matches #"[A-Za-z0-9_-]{43}" (str code_challenge))) (fail "invalid_request" "A PKCE code_challenge is required.")
            (not= "S256" code_challenge_method) (fail "invalid_request" "code_challenge_method must be S256.")
            (and (seq resource) (not (oauth/resource-matches? resource (mcp-url req)))) (fail "invalid_target" "Unknown resource.")
            :else {:client client :params params}))))))

(defn- consent-page
  [req user {:keys [client params]}]
  (let [redirect-host (.getHost (URI. (:redirect_uri params)))
        local? (oauth/loopback-host? redirect-host)]
    (page "Connect"
          [:h1.dashboard-title "Connect " [:span.consent-client (:name client)] "?"]
          [:p (:name client) " wants to use " (gs/site-name) " as " [:strong (:username user)]
           ": read and change recipes, the pantry, the meal plan and the grocery list."]
          [:dl.consent-facts
           [:div [:dt "Sends you back to"] [:dd [:code redirect-host]]]
           (when (oauth/metadata-client-id? (:client_id params))
             [:div [:dt "Client"] [:dd [:code (.getHost (URI. (:client_id params)))]]])]
          (when local?
            (ui/alert :info "This app receives the sign-in on this computer (" redirect-host "). Only continue if you just started connecting from an app here, such as Claude Code."))
          [:form.form {:method "post" :action "/oauth/authorize"}
           (for [k auth-param-keys :let [v (get params k)] :when v]
             [:input {:type "hidden" :name (name k) :value v}])
           [:div.form-actions
            [:button.btn.btn-secondary {:type "submit" :name "decision" :value "deny"} "Deny"]
            [:button.btn.btn-primary {:type "submit" :name "decision" :value "approve"} "Allow"]]])))

(defn- params-of [req] (into {} (for [k auth-param-keys :let [v (get-in req [:params k])] :when (string? v)] [k v])))

(defn authorize-get
  [req]
  (let [checked (check-request req (params-of req))]
    (cond
      (:page checked) (:page checked)
      (:redirect checked) (redirect (:redirect checked))
      :else (if-let [user (auth/current-user req)]
              (consent-page req user checked)
              (redirect (str "/login?next=" (URLEncoder/encode (str (:uri req) "?" (:query-string req)) "UTF-8")))))))

(defn authorize-post
  "The consent form. Same-origin only (the middleware's origin check applies here)."
  [req]
  (if-let [user (auth/current-user req)]
    (let [params (params-of req)
          checked (check-request req params)]
      (cond
        (:page checked) (:page checked)
        (:redirect checked) (redirect (:redirect checked))
        (not= "approve" (get-in req [:params :decision]))
        (redirect (with-query (:redirect_uri params) {:error "access_denied" :state (:state params) :iss (site req)}))
        :else
        (let [code (oauth/issue-code! {:client-id (:client_id params) :user-id (:id user)
                                       :code-challenge (:code_challenge params) :redirect-uri (:redirect_uri params)
                                       :resource (or (:resource params) (mcp-url req))})]
          (redirect (with-query (:redirect_uri params) {:code code :state (:state params) :iss (site req)})))))
    (redirect "/login")))

;;; Token and registration

(defn token
  [req]
  (let [address (rl/client-address req)]
    (cond
      (oauth-disabled?) (json-resp 400 {:error "unauthorized_client" :error_description "OAuth sign-in is turned off."})
      (rl/limited? :oauth-token address) (json-resp 429 {:error "slow_down" :error_description "Too many requests."})
      :else
      (do (rl/record! :oauth-token address)
          (try
            (let [params (into {} (for [[k v] (:params req)] [k (if (sequential? v) (first v) v)]))]
              (case (:grant_type params)
                "authorization_code" (json-resp 200 (oauth/exchange-code! params (mcp-url req)))
                "refresh_token" (json-resp 200 (oauth/refresh! params (mcp-url req)))
                (json-resp 400 {:error "unsupported_grant_type" :error_description "Use authorization_code or refresh_token."})))
            (catch clojure.lang.ExceptionInfo e
              (if (:oauth-error (ex-data e)) (error-json e) (throw e))))))))

(defn register
  [req]
  (let [address (rl/client-address req)]
    (cond
      (oauth-disabled?) (json-resp 400 {:error "invalid_client_metadata" :error_description "OAuth sign-in is turned off."})
      (rl/limited? :oauth-register address) (json-resp 429 {:error "slow_down" :error_description "Too many registrations."})
      :else
      (do (rl/record! :oauth-register address)
          (try
            (let [body (try (json/read-str (slurp (:body req) :encoding "UTF-8")) (catch Exception _ nil))]
              (if (map? body)
                (json-resp 201 (oauth/register! body))
                (json-resp 400 {:error "invalid_client_metadata" :error_description "Send the client metadata as a JSON object."})))
            (catch clojure.lang.ExceptionInfo e
              (if (:oauth-error (ex-data e)) (error-json e) (throw e))))))))
