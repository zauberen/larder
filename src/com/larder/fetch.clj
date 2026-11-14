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

(ns com.larder.fetch
  "Server-side fetches of addresses users or clients supply (recipe pages, their
  images, later OAuth client metadata). Rules: http and https only; every address the
  host resolves to must be public (no private, loopback, link-local, multicast or
  similar ranges); redirects are followed by hand and each hop is checked again; bodies
  are capped (5 MB by default); the whole fetch, body included, must finish within 10
  seconds.

  The JVM caches successful DNS lookups (30 s by default), so the connection made right
  after the check uses the addresses that were checked."
  (:require [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream InputStream)
           (java.net Inet4Address Inet6Address InetAddress URI)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
           (java.time Duration)
           (java.util.concurrent TimeUnit TimeoutException)))

(def default-max-bytes (* 5 1024 1024))
(def timeout-ms 10000)
(def max-redirects 5)

(def ^:dynamic *allow-private-addresses*
  "Tests bind this to true to fetch from a local server."
  false)

(defn- user-error
  [msg]
  (ex-info msg {:user-message msg}))

(defn- v4-blocked?
  [^bytes b]
  (let [[a c] (map #(bit-and % 0xff) b)]
    (or (= a 0)                              ; "this" network
        (= a 10)                             ; private
        (= a 127)                            ; loopback
        (and (= a 100) (<= 64 c 127))        ; carrier-grade NAT
        (and (= a 169) (= c 254))            ; link-local
        (and (= a 172) (<= 16 c 31))         ; private
        (and (= a 192) (= c 168))            ; private
        (and (= a 192) (= c 0) (= 0 (bit-and (aget b 2) 0xff))) ; IETF protocol assignments
        (and (= a 198) (<= 18 c 19))         ; benchmarking
        (>= a 224))))                        ; multicast and reserved

(defn blocked-address?
  "True for addresses a server-side fetch must not reach."
  [^InetAddress addr]
  (or (.isAnyLocalAddress addr) (.isLoopbackAddress addr) (.isLinkLocalAddress addr)
      (.isSiteLocalAddress addr) (.isMulticastAddress addr)
      (if (instance? Inet4Address addr)
        (v4-blocked? (.getAddress addr))
        (let [b (.getAddress ^Inet6Address addr)
              b0 (bit-and (aget b 0) 0xff)
              embedded-v4 (fn [] (v4-blocked? (java.util.Arrays/copyOfRange b 12 16)))]
          (or (= 0xfc (bit-and b0 0xfe))                                   ; unique local fc00::/7
              ;; IPv4-compatible/mapped (::a.b.c.d, ::ffff:a.b.c.d) and NAT64 (64:ff9b::/96)
              (and (every? zero? (take 10 b)) (embedded-v4))
              (and (= [0 0x64 0xff 0x9b] (map #(bit-and % 0xff) (take 4 b))) (embedded-v4))
              ;; documentation 2001:db8::/32
              (= [0x20 0x01 0x0d 0xb8] (map #(bit-and % 0xff) (take 4 b))))))))

(defn check-url!
  "Parse and check URL-STRING. Returns the URI; throws ex-info with :user-message when it
  may not be fetched."
  ^URI [url-string]
  (let [uri (try (URI. (str/trim (str url-string))) (catch Exception _ nil))
        scheme (some-> uri .getScheme str/lower-case)
        host (some-> uri .getHost)]
    (when-not (and uri (#{"http" "https"} scheme) (not (str/blank? host)))
      (throw (user-error "Enter a web address starting with http:// or https://.")))
    (when (.getRawUserInfo uri)
      (throw (user-error "Web addresses with a user name or password can't be fetched.")))
    (let [addrs (try (InetAddress/getAllByName host)
                     (catch java.net.UnknownHostException _
                       (throw (user-error (str "Couldn't find " host ".")))))]
      (when (and (not *allow-private-addresses*) (some blocked-address? addrs))
        (throw (user-error "That address is on a private network, so Larder won't fetch it."))))
    uri))

(defonce ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.followRedirects HttpClient$Redirect/NEVER)
             (.connectTimeout (Duration/ofMillis timeout-ms))
             (.build))))

(defn- read-capped
  "The bytes of IN, throwing once there are more than MAX."
  ^bytes [^InputStream in max]
  (with-open [in in]
    (let [out (ByteArrayOutputStream.)
          buf (byte-array 65536)]
      (loop [n 0]
        (let [r (.read in buf)]
          (cond
            (neg? r) (.toByteArray out)
            (> (+ n r) max) (throw (user-error (str "The response is larger than " (quot max (* 1024 1024)) " MB.")))
            :else (do (.write out buf 0 r) (recur (+ n r)))))))))

(defn- fetch-hops
  [url-string max-bytes accept]
  (loop [url url-string hops 0]
    (let [uri (check-url! url)
          req (-> (HttpRequest/newBuilder uri)
                  (.timeout (Duration/ofMillis timeout-ms))
                  (.header "User-Agent" "Mozilla/5.0 (compatible; Larder recipe importer)")
                  (.header "Accept" accept)
                  (.GET)
                  (.build))
          resp (.send ^HttpClient @client req (HttpResponse$BodyHandlers/ofInputStream))
          status (.statusCode resp)]
      (if (<= 300 status 399)
        (let [location (.orElse (.firstValue (.headers resp) "location") nil)]
          (.close ^InputStream (.body resp))
          (cond
            (nil? location) (throw (user-error "The site redirected without saying where."))
            (>= hops max-redirects) (throw (user-error "The site redirected too many times."))
            :else (recur (str (.resolve uri (str/replace location " " "%20"))) (inc hops))))
        (let [body (read-capped (.body resp) max-bytes)]
          {:status status
           :url (str uri)
           :content-type (.orElse (.firstValue (.headers resp) "content-type") "")
           :body body})))))

(defn fetch
  "GET URL-STRING under the rules above. Returns {:status :url (after redirects)
  :content-type :body (bytes)}. Throws ex-info with :user-message on any refusal or
  failure."
  [url-string & {:keys [max-bytes accept] :or {max-bytes default-max-bytes accept "*/*"}}]
  (let [allow *allow-private-addresses*
        work (future (binding [*allow-private-addresses* allow] (fetch-hops url-string max-bytes accept)))]
    (try
      (.get ^java.util.concurrent.Future work timeout-ms TimeUnit/MILLISECONDS)
      (catch TimeoutException _
        (future-cancel work)
        (throw (user-error "The site took too long to answer.")))
      (catch java.util.concurrent.ExecutionException e
        (let [cause (.getCause e)]
          (if (:user-message (ex-data cause))
            (throw cause)
            (throw (ex-info (str "Couldn't fetch the page: " (or (.getMessage cause) (.getSimpleName (class cause))))
                            {:user-message (str "Couldn't fetch the page"
                                                (when-let [m (.getMessage cause)] (str ": " m)) ".")}
                            cause))))))))
