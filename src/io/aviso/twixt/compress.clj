(ns io.aviso.twixt.compress
  "Asset pipeline middleware for handling compressable assets."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [io.aviso.twixt
             [memory-cache :as mem]
             [utils :as utils]
             [schemas :refer [AssetHandler]]]
            [schema.core :as s])
  (:import [java.io ByteArrayOutputStream]
           [java.util.zip GZIPOutputStream]))

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
    (some #(.equalsIgnoreCase ^String % "gzip")
          (str/split encodings #","))))

(s/defn wrap-pipeline-with-compression :- AssetHandler
  "Asset pipeline middleware for detecting if asset content is compressable, and compressing
  the asset when necessary

  When an asset is accessed for aggregation, it is never compressed."
  [asset-handler :- AssetHandler
   {compressable-types :compressable}]
  (fn [asset-path {:keys [gzip-enabled for-aggregation] :as context}]
    (let [asset (asset-handler asset-path context)]
      (if (and asset
               gzip-enabled
               (not for-aggregation)
               (is-compressable-mime-type? compressable-types (:content-type asset)))
        (compress-asset asset)
        asset))))

(defn- with-cache-delegation
  [asset-handler cache]
  (fn [asset-path options]
    (let [delegate (if (:gzip-enabled options) cache asset-handler)]
      (delegate asset-path options))))

(s/defn wrap-with-sticky-compressed-caching :- AssetHandler
  "Adds sticky in-memory caching of just compressed assets.
  The cache is only used when the `:gzip-enabled` options key is true."
  [asset-handler :- AssetHandler]
  (with-cache-delegation asset-handler
                         (mem/wrap-with-sticky-cache asset-handler :compressed)))

(s/defn wrap-with-invalidating-compressed-caching :- AssetHandler
  "Adds an in-memory development cache that includes invalidation checks.
  The cache is only consulted if the `:gzip-enabled` options key is true."
  [asset-handler :- AssetHandler]
  (with-cache-delegation asset-handler
                         (mem/wrap-with-invalidating-cache asset-handler :compressed)))

(defn wrap-with-compression-analyzer
  "Ring middleware that analyzes the incoming request to determine if the
  client can accept compressed streams."
  [handler]
  (fn [request]
    (handler (assoc-in request [:twixt :gzip-enabled] (is-gzip-supported? request)))))