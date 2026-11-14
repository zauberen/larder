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

(ns com.larder.images
  "Recipe images. Every image, uploaded or downloaded, is decoded and re-encoded as a
  JPEG before it is stored, so only plain pixels reach the disk and every file can be
  served as image/jpeg. Files live in <data-root>/media under random names."
  (:require [clojure.java.io :as io]
            [com.larder.settings :as s])
  (:import (java.awt Color RenderingHints)
           (java.awt.image BufferedImage)
           (java.io File)
           (java.util UUID)
           (javax.imageio IIOImage ImageIO ImageReader ImageWriteParam)))

(def max-bytes
  "Largest image file accepted."
  (* 10 1024 1024))

(def max-pixels
  "Largest image accepted, checked from the header before decoding (a small file can
  claim huge dimensions)."
  (* 50 1000 1000))

(def max-side
  "Stored images are scaled down to at most this many pixels on the longest side."
  2000)

(def ^:private name-pattern #"[0-9a-f]{32}\.jpg")

(defn- user-error
  [msg]
  (ex-info msg {:user-message msg}))

(defn media-dir
  ^File []
  (io/file (s/data-dir "media")))

(defn media-file
  "The stored image named NAME, or nil when NAME isn't one of ours or doesn't exist."
  ^File [name]
  (when (and (string? name) (re-matches name-pattern name))
    (let [f (io/file (media-dir) name)]
      (when (.isFile f) f))))

(defn- decode
  "Read the image in FILE, refusing unknown formats and oversized dimensions."
  ^BufferedImage [^File file]
  (with-open [in (ImageIO/createImageInputStream file)]
    (let [readers (when in (ImageIO/getImageReaders in))
          ^ImageReader reader (when (and readers (.hasNext readers)) (.next readers))]
      (when-not reader
        (throw (user-error "That file isn't an image Larder can read. Use a JPEG, PNG, GIF or BMP.")))
      (try
        (.setInput reader in true true)
        (let [w (.getWidth reader 0)
              h (.getHeight reader 0)]
          (when (> (* (long w) (long h)) max-pixels)
            (throw (user-error "The image is too large. Use one under 50 megapixels.")))
          (.read reader 0))
        (catch javax.imageio.IIOException _
          (throw (user-error "The image couldn't be read; it may be damaged.")))
        (finally (.dispose reader))))))

(defn- flatten-and-scale
  "IMG as an RGB image (transparent areas white) no larger than `max-side`."
  ^BufferedImage [^BufferedImage img]
  (let [w (.getWidth img) h (.getHeight img)
        scale (min 1.0 (/ (double max-side) (max w h)))
        nw (max 1 (Math/round (* w scale)))
        nh (max 1 (Math/round (* h scale)))
        out (BufferedImage. nw nh BufferedImage/TYPE_INT_RGB)
        g (.createGraphics out)]
    (try
      (.setRenderingHint g RenderingHints/KEY_INTERPOLATION RenderingHints/VALUE_INTERPOLATION_BILINEAR)
      (.setRenderingHint g RenderingHints/KEY_RENDERING RenderingHints/VALUE_RENDER_QUALITY)
      (.setColor g Color/WHITE)
      (.fillRect g 0 0 nw nh)
      (.drawImage g img 0 0 nw nh nil)
      out
      (finally (.dispose g)))))

(defn- write-jpeg!
  [^BufferedImage img ^File dest]
  (let [writer (.next (ImageIO/getImageWritersByFormatName "jpeg"))
        params (doto (.getDefaultWriteParam writer)
                 (.setCompressionMode ImageWriteParam/MODE_EXPLICIT)
                 (.setCompressionQuality (float 0.85)))]
    (try
      (with-open [out (ImageIO/createImageOutputStream dest)]
        (.setOutput writer out)
        (.write writer nil (IIOImage. img nil nil) params))
      (finally (.dispose writer)))))

(defn store!
  "Re-encode the image in FILE and store it. Returns the stored file's name. Throws
  ex-info with :user-message when FILE is too big or not a readable image."
  [^File file]
  (when (> (.length file) max-bytes)
    (throw (user-error "The image is too large (the limit is 10 MB).")))
  (let [img (flatten-and-scale (decode file))
        name (str (.replace (str (UUID/randomUUID)) "-" "") ".jpg")
        dir (media-dir)
        tmp (io/file dir (str name ".tmp"))
        dest (io/file dir name)]
    (.mkdirs dir)
    (try
      (write-jpeg! img tmp)
      (java.nio.file.Files/move (.toPath tmp) (.toPath dest)
                                (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      name
      (finally (.delete tmp)))))

(defn delete!
  "Delete the stored image NAME, if it is one."
  [name]
  (when-let [f (media-file name)]
    (.delete f)))
