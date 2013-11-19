(ns io.aviso.twixt.compress
  "Asset pipeline middleware for handling compressable assets."
  (:import [java.io ByteArrayOutputStream]
           [java.util.zip GZIPOutputStream])
  (require [clojure.string :as str]
           [clojure.java.io :as io]
           [io.aviso.twixt
            [memory-cache :as mem]
            [utils :as utils]]))

(defn- make-wildcard [^String mime-type]
  (->
    mime-type
    (.split "/")
    first
    (str "/*")))

(defn- is-compressable-mime-type?
  [compressable-types mime-type]
  (or
    (compressable-types mime-type)
    (compressable-types (make-wildcard mime-type))))

(defn- compress-asset
  [asset]
  (let [bos (ByteArrayOutputStream. (:size asset))]
    (with-open [gz (GZIPOutputStream. bos)
                is (io/input-stream (:content asset))]
      (io/copy is gz))
    (-> asset
        (utils/replace-asset-content (:content-type asset) (.toByteArray bos))
        (assoc :compressed true))))

(defn- is-gzip-supported?
  [request]
  (if-let [encodings (-> request :headers (get "accept-encoding"))]
    (some #(.equalsIgnoreCase % "gzip")
          (str/split encodings #","))))

(defn wrap-with-compression
  "Asset pipeline middleware for detecting if asset content is compressable, and compressing
  the asset when necessary."
  [handler {compressable-types :compressable}]
  (fn asset-compressor [asset-path options]
    (let [asset (handler asset-path options)]
      (if (and asset
               (:gzip-enabled options)
               (is-compressable-mime-type? compressable-types (:content-type asset)))
        (compress-asset asset)
        asset))))

(defn- with-cache-delegation
  [handler cache]
  (fn delegator [asset-path options]
    (let [delegate (if (:gzip-enabled options) cache handler)]
      (delegate asset-path options))))

(defn wrap-with-sticky-compressed-caching
  "Adds sticky in-memory caching of just compressed assets.
  The cache is only used when the :gzip-enabled options key is true."
  [handler]
  (with-cache-delegation handler
                         (mem/wrap-with-sticky-cache handler :compressed)))

(defn wrap-with-invalidating-compressed-caching
  "Adds an in-memory development cache that includes invalidation checks.
  The cache is only consulted if the :gzip-enabled options key is true."
  [handler]
  (with-cache-delegation handler
                         (mem/wrap-with-invalidating-cache handler :compressed)))

(defn wrap-with-compression-analyzer
  "Ring middleware that analyzes the incoming request to determine if the
  client can accept compressed streams."
  [handler]
  (fn compression-analyzer [request]
    (handler (assoc-in request [:twixt :gzip-enabled] (is-gzip-supported? request)))))