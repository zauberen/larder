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

(ns com.larder.pages.app
  "The logged-in views behind the sidebar entries. Each phase fills one in."
  (:require [com.larder.pages.layout :as layout]
            [com.larder.ui :as ui]))

(defn- placeholder
  [req user section title text]
  (layout/app-page req user
                   (layout/main section
                                [:h1.dashboard-title title]
                                (ui/empty-state [:p.muted text]))))

(defn meal-plan-page
  [req user]
  (placeholder req user "meal-plan" "Meal plan" "Nothing planned yet."))
