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

(ns com.larder.auth
  "Role checks. The role and enabled flag are read from the database on every request,
  so changes apply immediately to existing sessions."
  (:require [com.larder.api.session :as session]
            [com.larder.users :as users]))

(defn admin?
  [user]
  (= "admin" (:role user)))

(defn current-user
  "The enabled user for the request's session, or nil."
  [req]
  (when-let [sess (session/get-session req)]
    (users/by-username (:user sess))))

(defn forbidden
  []
  {:status 403
   :headers {"Content-Type" "text/plain"}
   :body "You do not have permission to do that."})

(defn not-found
  []
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "Not found."})

(defn requires-user
  "Call (HANDLER req user) when the request has a valid session for an enabled user."
  [handler req]
  (if-let [user (current-user req)]
    (handler req user)
    (session/login-redirect req)))

(defn requires-admin
  "Call (HANDLER req user) when the session belongs to an enabled admin."
  [handler req]
  (requires-user (fn [req user]
                   (if (admin? user)
                     (handler req user)
                     (forbidden)))
                 req))
