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

(ns com.larder.pages.server-config
  "The settings file (~/.larder) as a form. Onboarding uses the fields to write the first
  configuration; Settings -> Site -> Server configuration edits it afterwards."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [com.larder.response :as r]
            [com.larder.settings :as s]
            [com.larder.ui :as ui]
            [com.larder.uploads :as uploads]
            [com.larder.util :as u]
            [com.larder.wrappers.path-utils :as pu]))

(def labels
  {:listen-port "Web UI port"
   :data-root "Data folder"
   :sqlite-db-path "Database file"
   :upload-chunk-mb "Upload piece size (MB)"
   :debug "Debug mode"})

(def ^:private path-keys [:data-root :sqlite-db-path])

;;; Parsing

(defn- valid-port?
  [n]
  (and n (< 0 n 65536)))

(defn- path?
  "Absolute paths, or paths in the home directory (~/...)."
  [p]
  (and (not (str/blank? p))
       (or (str/starts-with? p "~") (.isAbsolute (io/file p)))))

(defn parse
  "Read the configuration fields from form PARAMS. Returns [settings errors]."
  [params]
  (let [text #(some-> (get params %) str str/trim)
        settings (merge (into {} (for [k path-keys] [k (text k)]))
                        {:listen-port (u/parse-int (text :listen-port))
                         :debug (= "1" (get params :debug))
                         ;; Blank or missing: the default.
                         :upload-chunk-mb (u/parse-int (text :upload-chunk-mb)
                                                       (:upload-chunk-mb (s/get-default-settings)))})
        errors (cond-> []
                 (not (valid-port? (:listen-port settings)))
                 (conj "The web UI port must be between 1 and 65535.")
                 :always
                 (into (for [k path-keys :when (not (path? (get settings k)))]
                         (str (labels k) " must be an absolute path, or start with ~.")))
                 (not (<= 1 (or (:upload-chunk-mb settings) 0) uploads/max-chunk-mb))
                 (conj (str "The upload piece size must be between 1 and " uploads/max-chunk-mb " MB.")))]
    [settings errors]))

(defn form-values
  "Values to redisplay after a failed submit: what was typed, not what parsed."
  [params]
  (merge (into {} (for [k s/config-keys :when (contains? params k)] [k (get params k)]))
         {:debug (= "1" (get params :debug))}))

(defn create-folders!
  "Create the data folder in SETTINGS. Returns an error message for each folder that can't be."
  [settings]
  (vec (for [k [:data-root]
             :let [error (try (pu/create-dir (get settings k)) nil
                              (catch Exception e (str "Couldn't create " (get settings k) ": " (.getMessage e))))]
             :when error]
         error)))

;;; Fields

(defn- restart-hint
  "Hint text for K, noting restart-only keys and whether one is waiting on a restart."
  [k hint running values]
  (let [pending? (and running (contains? s/restart-keys k)
                      (not= (str (get running k)) (str (get values k))))]
    [:span.form-hint
     hint
     (when (contains? s/restart-keys k)
       (list (when hint " ") "Applies after a restart."))
     (when pending?
       (list " " [:span.badge.inherited (str "Restart pending — running with " (get running k))]))]))

(defn- input
  [values running k hint & [attrs]]
  [:label.form-field
   [:span.form-label (labels k)]
   [:input.form-input (merge {:name (name k) :value (str (get values k)) :required true} attrs)]
   (restart-hint k hint running values)])

(defn storage-fields
  "VALUES are the settings to show; RUNNING (optional) the settings in effect, to flag
  values that are saved but waiting on a restart."
  [values & [running]]
  [:fieldset.form-group
   [:legend "Server"]
   (input values running :listen-port "Port of this web interface." {:type "number" :min 1 :max 65535})
   (input values running :data-root "Recipe images and logs are kept here.")
   (input values running :sqlite-db-path "Recipes, the pantry, the grocery list and accounts are stored here.")])

(defn advanced-fields
  [values & [running]]
  [:fieldset.form-group
   [:legend "Advanced"]
   (input values running :upload-chunk-mb
          (str "Files are uploaded in pieces of this size. Raise it only if every reverse proxy in front "
               "of Larder accepts request bodies this large (nginx's default client_max_body_size is 1 MB).")
          {:type "number" :min 1 :max uploads/max-chunk-mb})
   [:label.checkbox
    [:input {:type "checkbox" :name "debug" :value "1" :checked (boolean (get values :debug))}]
    [:span "Debug mode — enables " [:code "/api/echo"] " for admins, which prints raw requests. Leave off in production."]]])

;;; Settings tab

(defn config-card
  "The Server configuration card. VALUES default to the saved settings."
  [& [message errors values]]
  (let [running (s/get-settings)
        values (or values (s/file-settings))]
    [:section#config-card.card
     [:div.card-header [:h2 "Server configuration"]]
     [:p.muted "Saved to " [:code (s/config-path)] ". Settings marked as applying after a restart "
      "take effect the next time Larder starts."]
     (ui/message-alert message)
     (ui/errors-alert errors)
     [:form.form {:hx-post "/api/site/config" :hx-target "#config-card" :hx-swap "outerHTML"
                  :style "margin-top: 1rem"}
      (storage-fields values running)
      (advanced-fields values running)
      [:div.form-actions [:button.btn.btn-primary {:type "submit"} "Save"]]]]))

(defn api-save
  [req _me]
  (let [params (:params req)
        [settings errors] (parse params)
        errors (if (seq errors) errors (create-folders! settings))]
    (if (seq errors)
      (r/h-respond (config-card nil errors (form-values params)))
      (do (s/save-settings! settings)
          (let [pending (s/pending-restart)]
            (r/h-respond
             (config-card {:kind :success
                           :text (if (seq pending)
                                   (str "Saved. Restart Larder to apply: "
                                        (str/join ", " (map labels pending)) ".")
                                   "Saved.")})))))))
