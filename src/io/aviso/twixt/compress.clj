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

(defn- is-compressable?
  [compressable-types asset]
  (is-compressable-mime-type? compressable-types (:content-type asset)))

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

(s/defn wrap-with-gzip-compression :- AssetHandler
  "Adds an in-memory cache that includes invalidation checks.

  Although compression is expensive enough that it is not desirable to
  perform compression on every request, it is also overkill to
  commit the compressed asset to a file system cache.

  The cache is only consulted if the :gzip-enabled options key is true."
  {:added "0.1.20"}
  [asset-handler :- AssetHandler
   twixt-options]
  (let [cache (mem/wrap-with-transforming-cache asset-handler
                                                (partial is-compressable? (:compressable twixt-options))
                                                compress-asset)]
    (fn [asset-path options]
      (let [delegate (if (:gzip-enabled options) cache asset-handler)]
        (delegate asset-path options)))))

(defn wrap-with-compression-analyzer
  "Ring middleware that analyzes the incoming request to determine if the
  client can accept compressed streams."
  [handler]
  (fn [request]
    (handler (assoc-in request [:twixt :gzip-enabled] (is-gzip-supported? request)))))