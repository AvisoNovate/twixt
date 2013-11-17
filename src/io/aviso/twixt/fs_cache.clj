(ns io.aviso.twixt.fs-cache
  "Provide asset pipeline middleware that implements a file-system cache.
  The cache will persist between executions of the application; this is used in development to prevent
  duplicated, expensive work from being performed after a restart."
  (:import [java.util UUID]
           [java.io PushbackReader File]
           (io.aviso.writer Writer))
  (require
    [clojure.java.io :as io]
    [clojure
     [edn :as edn]
     [pprint :as pp]
     [string :as s]]
    [clojure.tools.logging :as l]
    [io.aviso.twixt
     [tracker :as t]
     [utils :as utils]]))


(defn- checksum-matches?
  [asset-resolver asset-path cached-checksum]
  (->
    asset-path
    asset-resolver
    :checksum
    (= cached-checksum)))

(defn- is-valid?
  [asset-resolver cached-asset]
  (and cached-asset
       (every?
         (fn [{:keys [asset-path checksum]}]
           (checksum-matches? asset-resolver asset-path checksum))
         (-> cached-asset :dependencies vals))))

(defn- read-cached-asset-data
  [file]
  (if (.exists file)
    (with-open [reader (-> file io/reader PushbackReader.)]
      (edn/read reader))))

(def ^:private asset-file-name "asset.edn")
(def ^:private content-file-name "content")

(defn- read-cached-asset
  "Attempt to read the cached asset, if it exists.

  asset-cache-dir is the directory containing the two files (asset.edn and content)."
  [^File asset-cache-dir]
  (if (.exists asset-cache-dir)
    (t/trace
      #(format "Reading from asset cache `%s'." asset-cache-dir)
      (some->
        (io/file asset-cache-dir asset-file-name)
        read-cached-asset-data
        (assoc :content (io/file asset-cache-dir content-file-name))))))

(defn- write-cached-asset [asset-cache-dir asset]
  (t/trace
    #(format "Writing to asset cache `%s'." asset-cache-dir)
    (.mkdirs asset-cache-dir)
    (let [content-file (io/file asset-cache-dir content-file-name)
          asset-file (io/file asset-cache-dir asset-file-name)]
      ;; Write the content first
      (io/copy (:content asset) content-file)
      (with-open [writer (io/writer asset-file)]
        (.write writer (->
                         asset
                         (dissoc :content)
                         ;; Override *print-length* and *print-level* to ensure it is all written out.
                         (pp/write :length nil :level nil :stream nil)))
        (.write writer "\n")))))

(defn- delete-dir-and-contents
  [^File dir]
  (when (.exists dir)
    (t/trace
      #(format "Deleteing directory `%s.'" dir)
      (doseq [file (.listFiles dir)]
        (io/delete-file file))
      (io/delete-file dir))))

(defn wrap-with-filesystem-cache
  "Used to implement file-system caching of assets (this is typically only used in development, not production).
  File system caching improves startup and first-request time by avoiding the cost of recompling assets; instead
  the assets are stored on the file system. The asset checksums are used to see if the cache is still valid.

  Only assets that have truthy value for :compiled key will be file-system cached. This is set by the various
  compiler and transformers, such as the CoffeeScript to JavaScript transformer.

  - handler - to be wrapped
  - cache-dir-name - name of root folder to store cache in (from the Twixt options); the directory will be created as necessary
  - asset-resolver - used to directly resolve an asset path to an asset, bypassing any compilation"
  [handler cache-dir-name asset-resolver]
  (let [cache-dir (io/file cache-dir-name "compiled")]
    (l/infof "Caching compiled assets to `%s'." cache-dir)
    (.mkdirs cache-dir)
    (fn file-system-cache [asset-path context]
      (let [asset-cache-dir (io/file cache-dir asset-path)
            cached-asset (read-cached-asset asset-cache-dir)]
        (if (is-valid? asset-resolver cached-asset)
          cached-asset
          (do
            (delete-dir-and-contents asset-cache-dir)
            (let [asset (handler asset-path context)]
              (if (:compiled asset)
                (write-cached-asset asset-cache-dir asset))
              asset)))))))