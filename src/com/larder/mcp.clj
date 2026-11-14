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

(ns com.larder.mcp
  "The MCP endpoint (POST /mcp): Streamable HTTP with plain JSON responses, tools only,
  no sessions or server-initiated messages. Every request is authenticated by a bearer
  token, looked up first as an API key and then as an OAuth access token; the user's
  enabled flag is checked on every call. Cookies are ignored here."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [com.larder.api-keys :as api-keys]
            [com.larder.global-settings :as gs]
            [com.larder.mcp-tools :as tools]
            [com.larder.pages.layout :as layout]
            [com.larder.users :as users]))

(def supported-versions ["2025-11-25" "2025-06-18" "2025-03-26"])
(def latest-version (first supported-versions))

(def server-info {:name "larder" :title "Larder" :version "0.10.0"})

(def instructions
  (str "Larder is a household's shared recipes, pantry, grocery list and meal plan. "
       "Ingredients are shared records: resolve names with resolve_ingredients before adding pantry or grocery items. "
       "Pantry rows are one per purchase; a null quantity means the amount is unknown. "
       "Recipes added with add_recipe are drafts unless publish is true."))

;; Phase 11 sets this to the OAuth access token lookup: token -> {:user_id ...} or nil.
(defonce oauth-lookup (atom (fn [_token] nil)))

;;; Responses

(defn- json-response
  [status body & [headers]]
  {:status status
   :headers (merge {"Content-Type" "application/json"} headers)
   :body (json/write-str body)})

(defn- rpc-result [id result] {:jsonrpc "2.0" :id id :result result})
(defn- rpc-error [id code message] {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn resource-metadata-url
  [req]
  (str (layout/site-url req) "/.well-known/oauth-protected-resource"))

(defn unauthorized
  "401 telling the client where to find out how to sign in."
  [req & [error]]
  (json-response 401 (rpc-error nil -32001 "Sign in to Larder: send an API key or an OAuth access token as a bearer token.")
                 {"WWW-Authenticate" (str "Bearer resource_metadata=\"" (resource-metadata-url req) "\""
                                          (when error (str ", error=\"" error "\"")))}))

;;; Authentication

(defn bearer-token
  [req]
  (some->> (get-in req [:headers "authorization"]) (re-matches #"(?i)Bearer\s+(\S+)\s*") second))

(defn authenticate
  "The enabled user behind REQ's bearer token, or nil."
  [req]
  (when-let [token (bearer-token req)]
    (let [user-id (or (when (gs/enabled? "mcp_api_keys") (:user_id (api-keys/lookup token)))
                      (when (gs/enabled? "mcp_oauth") (:user_id (@oauth-lookup token))))
          user (when user-id (users/by-id user-id))]
      (when (= 1 (:enabled user)) user))))

;;; JSON-RPC

(defn- tool-result
  [result]
  {:content [{:type "text" :text (json/write-str result)}]
   :structuredContent result
   :isError false})

(defn- call-tool
  [id params user]
  (let [name (get params "name")
        args (or (get params "arguments") {})
        tool (first (filter #(= name (:name %)) tools/tools))]
    (cond
      (nil? tool) (rpc-error id -32602 (str "Unknown tool: " name))
      (not (map? args)) (rpc-error id -32602 "arguments must be an object")
      :else
      (rpc-result id
                  (try
                    (tool-result ((:fn tool) (walk/keywordize-keys args) user))
                    (catch clojure.lang.ExceptionInfo e
                      (if-let [msg (:tool-error (ex-data e))]
                        {:content [{:type "text" :text msg}] :isError true}
                        (do (log/error e "MCP tool failed:" name)
                            {:content [{:type "text" :text "Larder hit an error running that tool."}] :isError true})))
                    (catch Exception e
                      (log/error e "MCP tool failed:" name)
                      {:content [{:type "text" :text "Larder hit an error running that tool."}] :isError true}))))))

(defn- tool-list
  []
  {:tools (mapv #(select-keys % [:name :description :inputSchema]) tools/tools)})

(defn handle-message
  "The JSON-RPC response for MSG (a parsed request), or nil for notifications."
  [msg user]
  (let [id (get msg "id")
        method (get msg "method")
        params (get msg "params" {})]
    (cond
      (not (contains? msg "id")) nil
      (not (string? method)) (rpc-error id -32600 "Invalid request")
      :else
      (case method
        "initialize" (let [wanted (get params "protocolVersion")]
                       (rpc-result id {:protocolVersion (if (some #{wanted} supported-versions) wanted latest-version)
                                       :capabilities {:tools {:listChanged false}}
                                       :serverInfo server-info
                                       :instructions instructions}))
        "ping" (rpc-result id {})
        "tools/list" (rpc-result id (tool-list))
        "tools/call" (call-tool id params user)
        (rpc-error id -32601 (str "Method not found: " method))))))

(defn handler
  "Ring handler for /mcp."
  [req]
  (let [version (get-in req [:headers "mcp-protocol-version"])]
    (cond
      (not= :post (:request-method req))
      {:status 405 :headers {"Allow" "POST" "Content-Type" "text/plain"} :body "This MCP server answers POST only."}

      (and version (not (some #{version} supported-versions)))
      (json-response 400 (rpc-error nil -32600 (str "Unsupported MCP-Protocol-Version " version)))

      :else
      (if-let [user (authenticate req)]
        (let [msg (try (json/read-str (slurp (:body req) :encoding "UTF-8")) (catch Exception _ ::invalid))]
          (cond
            (= ::invalid msg) (json-response 400 (rpc-error nil -32700 "Parse error"))
            (sequential? msg) (json-response 400 (rpc-error nil -32600 "Batches are not supported"))
            (not (map? msg)) (json-response 400 (rpc-error nil -32600 "Invalid request"))
            (and (not (contains? msg "method")) (contains? msg "id")) {:status 202 :body ""} ; a response to us; we never ask
            :else (if-let [resp (handle-message msg user)]
                    (json-response 200 resp)
                    {:status 202 :body ""})))
        (unauthorized req (when (bearer-token req) "invalid_token"))))))
