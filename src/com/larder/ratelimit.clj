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

(ns com.larder.ratelimit
  "In-memory fixed-window rate limits per client address. Limits reset when Larder
  restarts, which is fine for slowing down guessing.")

;; [bucket address] -> {:start ms :count n}
(defonce ^:private windows (atom {}))

(def limits
  "bucket -> [max-events window-ms]."
  {:login [10 (* 5 60 1000)]
   ;; Checking off and undoing grocery items without a login.
   :grocery-public [120 (* 10 60 1000)]})

(defn client-address
  "The address a request came from. X-Forwarded-For is not trusted, since any client
  can send it; behind a reverse proxy every request counts against the proxy's address."
  [req]
  (or (:remote-addr req) "unknown"))

(defn- now [] (System/currentTimeMillis))

(defn- sweep!
  [t]
  (swap! windows (fn [m] (into {} (remove (fn [[[bucket] w]]
                                            (> (- t (:start w)) (second (limits bucket)))))
                               m))))

(defn limited?
  "True when ADDRESS has used up BUCKET's events in the current window."
  [bucket address]
  (let [[max-events window-ms] (limits bucket)
        w (get @windows [bucket address])]
    (boolean (and w (< (- (now) (:start w)) window-ms) (>= (:count w) max-events)))))

(defn record!
  "Count one event for ADDRESS in BUCKET."
  [bucket address]
  (let [t (now)
        [_ window-ms] (limits bucket)]
    (when (> (count @windows) 10000) (sweep! t))
    (swap! windows update [bucket address]
           (fn [w] (if (or (nil? w) (>= (- t (:start w)) window-ms))
                     {:start t :count 1}
                     (update w :count inc))))))

(defn reset-all!
  "Forget every window (tests)."
  []
  (reset! windows {}))

(defn too-many-requests
  []
  {:status 429
   :headers {"Content-Type" "text/plain" "Retry-After" "300"}
   :body "Too many attempts. Wait a few minutes and try again."})
