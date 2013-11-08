(ns io.aviso.twixt.fs-cache
  "Keeps a file-system cache that can persist between executions of the application; this is used in development to prevent
  duplicated, expensive work from being performed after a restart."
  (:import [java.util UUID]
           [java.io PushbackReader])
  (require
    [clojure.java.io :as io]
    [clojure
     [edn :as edn]
     [string :as s]]
    [clojure.tools.logging :as l]
    [io.aviso.twixt
     [tracker :as t]
     [utils :as utils]]))


(defn- checksum-matches?
  [handler asset-path cached-checksum]
  (->
    asset-path
    handler
    :checksum
    (= cached-checksum)))

(defn- is-valid?
  [handler cached-asset]
  (and cached-asset
       (every?
         (fn [{:keys [asset-path checksum]}]
           (checksum-matches? handler asset-path checksum))
         (-> cached-asset :dependencies vals))))

(defn- read-cached-data
  [file]
  (if (.exists file)
    (with-open [reader (-> file io/reader PushbackReader.)]
      (edn/read reader))))

(defn- read-cached-asset
  [cache-dir asset-path]
  (t/trace
    #(format "Reading asset cache %s/%s" cache-dir asset-path)
    (let [container-dir (io/file cache-dir asset-path)]
      (if (.exists container-dir)
        (let [asset-file (io/file container-dir "asset.edn")]
          (if-let [cached-data (read-cached-data asset-file)]
            ;; Replace the :content value with the corresponding file within the directory.
            (update-in cached-data [:content] (partial io/file container-dir))))))))

(defn write-cached-asset [cache-dir asset]
  (let [asset-path (:asset-path asset)
        content-name (-> (UUID/randomUUID) .toString)]
    (t/trace
      #(format "Writing to asset cache %s/%s" cache-dir asset-path)
      (let [container-dir (io/file cache-dir asset-path)
            _ (.mkdirs container-dir)
            content-file (io/file container-dir content-name)
            asset-file (io/file container-dir "asset.edn")]
        (io/copy (:content asset) content-file)
        (with-open [writer (io/writer asset-file)]
          (.write writer (->
                           asset
                           (assoc :content content-name)
                           utils/pretty)))))))

(defn wrap-with-filesystem-cache
  "Wraps the asset pipeline handler with a cache similar to the in-memory validating cache, with some differences:

  - only assets with the :compiled property are cached
  - assets are only invalid if their checksum has changed; modified-at is ignored."
  [handler cache-dir-name]
  (let [cache-dir (io/file cache-dir-name "compiled")]
    (l/infof "Caching compiled assets to `%s'." cache-dir)
    (.mkdirs cache-dir)
    (fn file-system-cache [asset-path]
      (let [cached-asset (read-cached-asset cache-dir asset-path)]
        (if (is-valid? handler cached-asset)
          cached-asset
          ;; TODO: Delete existing cache file, if it exists?
          (let [asset (handler asset-path)]
            (if (:compiled asset)
              (write-cached-asset cache-dir asset))
            asset))))))