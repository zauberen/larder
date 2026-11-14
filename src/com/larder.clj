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

(ns com.larder
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.tools.logging :as log]
            [compojure.core :as cjr]
            [compojure.route :as route]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [com.larder.api.session :as session]
            [com.larder.auth :as auth]
            [com.larder.database.db :as db]
            [com.larder.database.sqlite :as sqlite]
            [com.larder.mcp :as mcp]
            [com.larder.middleware :as mw]
            [com.larder.pages.login :as lp]
            [com.larder.pages.made :as made]
            [com.larder.pages.mealplan :as mealplan-page]
            [com.larder.pages.not-found :as nf]
            [com.larder.pages.connect :as connect]
            [com.larder.pages.grocery :as grocery-page]
            [com.larder.pages.ingredients :as ingredients-page]
            [com.larder.pages.onboarding :as onboarding]
            [com.larder.pages.pantry :as pantry-page]
            [com.larder.pages.public :as public]
            [com.larder.pages.recipes :as recipes-page]
            [com.larder.pages.server-config :as server-config]
            [com.larder.pages.settings :as app-settings]
            [com.larder.response :as r]
            [com.larder.settings :as s]
            [com.larder.users :as users]
            [com.larder.wrappers.path-utils :as pu]))

(defonce ^:private server (atom nil))

;; True while Larder waits for first-run setup (no settings file or no account).
(defonce ^:private setup-required (atom false))

(cjr/defroutes
  routes
  ;; API bindings
  ; In debug mode, admins can see the raw request
  (cjr/ANY "/api/echo" req
    (when (:debug (s/get-settings))
      (auth/requires-admin (fn [req _user]
                             (r/respond-generic (with-out-str (pprint/pprint (dissoc req :body)))
                                                "text/plain"))
                           req)))
  ; Do-nothing endpoint for htmx hackery
  (cjr/GET "/api/null" []
    (r/respond-generic "" "text/plain"))
  ; Try logging into the application, requires username and password parameters
  (cjr/POST "/api/login" req
    (session/try-login req))
  ;; Recipes
  (cjr/POST "/api/recipes" req
    (auth/requires-user recipes-page/api-create req))
  (cjr/POST "/api/recipes/import" req
    (auth/requires-user recipes-page/api-import req))
  (cjr/POST "/api/recipes/:id" [id :as req]
    (auth/requires-user (fn [req user] (recipes-page/api-update req user id)) req))
  (cjr/POST "/api/recipes/:id/lines/:line/match" [id line :as req]
    (auth/requires-user (fn [req user] (recipes-page/api-match-line req user id line)) req))
  (cjr/POST "/api/recipes/:id/lines/:line/create" [id line :as req]
    (auth/requires-user (fn [req user] (recipes-page/api-create-ingredient req user id line)) req))
  (cjr/GET "/api/recipes/:id/made" [id :as req]
    (auth/requires-user (fn [req user] (made/api-dialog req user id)) req))
  (cjr/POST "/api/recipes/:id/made" [id :as req]
    (auth/requires-user (fn [req user] (made/api-made req user id)) req))
  (cjr/POST "/api/recipes/:id/grocery" [id :as req]
    (auth/requires-user (fn [req user] (recipes-page/api-missing-to-grocery req user id)) req))
  (cjr/POST "/api/recipes/:id/lines/:line/grocery" [id line :as req]
    (auth/requires-user (fn [req user] (recipes-page/api-line-to-grocery req user id line)) req))
  (cjr/POST "/api/recipes/:id/delete" [id :as req]
    (auth/requires-user (fn [req user] (recipes-page/api-delete req user id)) req))
  ;; Meal plan
  (cjr/POST "/api/meal-plan/entries" req
    (auth/requires-user mealplan-page/api-add req))
  (cjr/POST "/api/meal-plan/entries/:id/move" [id :as req]
    (auth/requires-user (fn [req user] (mealplan-page/api-move req user id)) req))
  (cjr/POST "/api/meal-plan/entries/:id/servings" [id :as req]
    (auth/requires-user (fn [req user] (mealplan-page/api-servings req user id)) req))
  (cjr/POST "/api/meal-plan/entries/:id/delete" [id :as req]
    (auth/requires-user (fn [req user] (mealplan-page/api-delete req user id)) req))
  (cjr/POST "/api/meal-plan/grocery" req
    (auth/requires-user mealplan-page/api-grocery req))
  ;; Pantry and ingredients
  (cjr/POST "/api/pantry" req
    (auth/requires-user pantry-page/api-add req))
  (cjr/POST "/api/pantry/delete" req
    (auth/requires-user pantry-page/api-delete req))
  (cjr/POST "/api/pantry/:id" [id :as req]
    (auth/requires-user (fn [req user] (pantry-page/api-update req user id)) req))
  (cjr/POST "/api/ingredients/:id" [id :as req]
    (auth/requires-user (fn [req user] (ingredients-page/api-save req user id)) req))
  ;; Grocery list. Completion and undo also serve visitors, per the access setting.
  (cjr/GET "/api/grocery/items/:id/complete" [id :as req]
    (grocery-page/api-dialog req id))
  (cjr/POST "/api/grocery/items/:id/complete" [id :as req]
    (grocery-page/api-complete req id))
  (cjr/POST "/api/grocery/items/:id/undo" [id :as req]
    (grocery-page/api-undo req id))
  (cjr/POST "/api/grocery" req
    (auth/requires-user grocery-page/api-add req))
  (cjr/POST "/api/grocery/clear-completed" req
    (auth/requires-user grocery-page/api-clear-completed req))
  (cjr/POST "/api/grocery/items/:id/store" [id :as req]
    (auth/requires-user (fn [req user] (grocery-page/api-set-store req user id)) req))
  (cjr/POST "/api/grocery/items/:id/delete" [id :as req]
    (auth/requires-user (fn [req user] (grocery-page/api-delete req user id)) req))
  (cjr/POST "/api/stores" req
    (auth/requires-user grocery-page/api-add-store req))
  (cjr/POST "/api/stores/:id/delete" [id :as req]
    (auth/requires-user (fn [req user] (grocery-page/api-delete-store req user id)) req))
  (cjr/POST "/api/keys" req
    (auth/requires-user connect/api-create-key req))
  (cjr/POST "/api/keys/:id/revoke" [id :as req]
    (auth/requires-user (fn [req me] (connect/api-revoke-own req me id)) req))
  (cjr/POST "/api/site/mcp" req
    (auth/requires-admin connect/api-save-mcp req))
  (cjr/POST "/api/site/keys/:id/revoke" [id :as req]
    (auth/requires-admin (fn [req me] (connect/api-revoke-any req me id)) req))
  (cjr/POST "/api/site/grocery-access" req
    (auth/requires-admin grocery-page/api-save-access req))
  (cjr/POST "/api/site/grocery-link" req
    (auth/requires-admin grocery-page/api-regenerate-link req))
  ;; Files (recipe images) sent in pieces under the request body limit.
  (cjr/POST "/api/uploads" req
    (auth/requires-user recipes-page/api-upload-start req))
  (cjr/PUT "/api/uploads/:id" [id :as req]
    (auth/requires-user (fn [req user] (recipes-page/api-upload-chunk req user id)) req))
  (cjr/DELETE "/api/uploads/:id" [id :as req]
    (auth/requires-user (fn [req user] (recipes-page/api-upload-delete req user id)) req))
  ;; Settings
  (cjr/POST "/api/users" req
    (auth/requires-admin app-settings/api-create-user req))
  (cjr/POST "/api/users/:id/:action{role|password|disable|enable|delete}" [id action :as req]
    (auth/requires-admin (fn [req me] (app-settings/api-user-action req me id action)) req))
  (cjr/POST "/api/account/password" req
    (auth/requires-user app-settings/api-change-password req))
  (cjr/POST "/api/site/settings" req
    (auth/requires-admin app-settings/api-save-site req))
  (cjr/POST "/api/site/config" req
    (auth/requires-admin server-config/api-save req))

  ;; MCP: bearer tokens only, no cookies (see com.larder.mcp).
  (cjr/ANY "/mcp" req
    (mcp/handler req))

  ;; Public pages
  (cjr/GET "/" req
    (public/public-page req))
  (cjr/GET "/grocery" req
    (public/grocery-page req nil))
  (cjr/GET "/g/:token" [token :as req]
    (public/grocery-page req token))
  (cjr/GET "/recipes/:id" [id :as req]
    (public/recipe-page req id))
  (cjr/GET "/media/:file" [file :as req]
    (recipes-page/media req file))

  ;; Logged-in pages
  (cjr/GET "/app" req
    (auth/requires-user recipes-page/recipes-page req))
  (cjr/GET "/app/recipes/new" req
    (auth/requires-user recipes-page/new-page req))
  (cjr/GET "/app/recipes/:id" [id :as req]
    (auth/requires-user (fn [req user] (recipes-page/recipe-page req user id)) req))
  (cjr/GET "/app/recipes/:id/edit" [id :as req]
    (auth/requires-user (fn [req user] (recipes-page/edit-page req user id)) req))
  (cjr/GET "/app/meal-plan" req
    (auth/requires-user mealplan-page/meal-plan-page req))
  (cjr/GET "/app/pantry" req
    (auth/requires-user pantry-page/pantry-page req))
  (cjr/GET "/app/ingredients/:id/edit" [id :as req]
    (auth/requires-user (fn [req user] (ingredients-page/dialog-page req user id)) req))
  (cjr/GET "/app/grocery" req
    (auth/requires-user grocery-page/grocery-page req))
  (cjr/GET "/app/settings" req
    (auth/requires-user (fn [req me] (app-settings/settings-page req me nil)) req))
  (cjr/GET "/app/settings/:tab" [tab :as req]
    (auth/requires-user (fn [req me] (app-settings/settings-page req me tab)) req))

  ; Setup is finished once this handler is reached
  (cjr/GET "/setup" []
    {:status 302 :headers {"Location" "/app"} :body ""})
  ; Login page
  (cjr/GET "/login" req
    (if (auth/current-user req)
      {:status 302 :headers {"Location" "/app"} :body ""}
      (lp/login-page)))
  ; Logout - clears session and redirects to login
  (cjr/GET "/logout" req
    (session/logout req))
  ;; A function, so the page (which reads the site name from the database) is built per
  ;; request rather than when this namespace loads.
  (route/not-found (fn [_req] (nf/not-found-html))))

(defn- on-startup
  "Bring the database into a consistent state and start the background services."
  []
  (pu/create-dir (:data-root (s/get-settings)))
  (db/get-db-spec))

(defn- setup-routes
  "The only routes served until setup is finished."
  [req]
  (case [(:request-method req) (:uri req)]
    [:get "/setup"] (onboarding/setup-page)
    [:post "/api/setup"] (locking setup-required
                           (if @setup-required
                             (onboarding/api-setup req (fn []
                                                         (on-startup)
                                                         (reset! setup-required false)))
                             {:status 200 :headers {"HX-Redirect" "/login"} :body ""}))
    (onboarding/setup-redirect req)))

(def app
  (let [wrap-res-fun (fn [handler] (wrap-resource handler "public"))]
    (-> (fn [req] (if @setup-required (setup-routes req) (routes req)))
        wrap-keyword-params
        wrap-params
        wrap-res-fun
        wrap-content-type
        wrap-cookies
        mw/wrap-same-origin
        mw/wrap-body-limit
        mw/wrap-security-headers)))

(defn start-server
  "Start the web service."
  []
  (log/info "Larder starting up")
  (s/pin-restart-settings!)
  (reset! setup-required (or (not (s/configured?))
                             (not (.exists (io/file (sqlite/db-path))))
                             (not (users/any-users?))))
  (if @setup-required
    (log/info "Waiting for first-run setup")
    (on-startup))
  (let [settings (s/get-settings)]
    (log/info (str "Starting on port " (:listen-port settings)))
    (log/info (str "Settings: " settings))
    (reset! server (jetty/run-jetty (fn [req] (app req)) {:port (:listen-port settings) :join? false}))
    (when @setup-required
      (let [msg (str "Open http://localhost:" (:listen-port settings) "/setup to finish setting up Larder.")]
        (log/info msg)
        (println msg)))))

(defn stop-server
  "Stop the web service."
  []
  (when-some [s @server]
    (.stop s)
    (reset! server nil)))
