(ns io.aviso.twixt.fs-cache
  "Provide asset pipeline middleware that implements a file-system cache.
  The cache will persist between executions of the application; this is used in development to prevent
  duplicated, expensive work from being performed after a restart."
  (:import [java.util UUID]
           [java.io PushbackReader File Writer])
  (require
    [clojure.java.io :as io]
    [clojure
     [edn :as edn]
     [pprint :as pp]]
    [medley.core :as medley]
    [clojure.tools.logging :as l]
    [io.aviso.tracker :as t]))


(defn- checksum-matches?
  [asset-resolver asset-path cached-checksum]
  (->
    asset-path
    (asset-resolver nil)
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
  [^File file]
  (if (.exists file)
    (with-open [reader (-> file io/reader PushbackReader.)]
      (edn/read reader))))

(defn- read-attachments
  [asset asset-cache-dir]
  (if (-> asset :attachments empty?)
    asset
    (update asset :attachments
            (fn [attachments]
              (medley/map-vals
                #(assoc % :content (io/file asset-cache-dir (:content %)))
                attachments)))))

(def ^:private asset-file-name "asset.edn")
(def ^:private content-file-name "content")

(defn- read-cached-asset
  "Attempt to read the cached asset, if it exists.

  `asset-cache-dir` is the directory containing the two files (asset.edn and content)."
  [^File asset-cache-dir]
  (if (.exists asset-cache-dir)
    (t/track
      #(format "Reading from asset cache `%s'" asset-cache-dir)
      (some->
        (io/file asset-cache-dir asset-file-name)
        read-cached-asset-data
        (assoc :content (io/file asset-cache-dir content-file-name))
        (read-attachments asset-cache-dir)))))


(defn- write-attachment
  "Writes the attachment's content to a file, returns the attachment with `:content` modified
  to be the simple name of the file written."
  [asset-cache-dir name attachment]
  (let [file-name (str (UUID/randomUUID) "-" name)
        content-file (io/file asset-cache-dir file-name)]
    (io/copy (:content attachment) content-file)
    (assoc attachment :content file-name)))

(defn- write-attachments
  "Writes attachments as files in the cache dir, updating each's `:content` key into a simple file name."
  [asset asset-cache-dir]
  (if (-> asset :attachments empty?)
    asset
    (update asset :attachments
            (fn [attachments]
              (into {}
                    (map (fn [[name attachment]]
                           [name (write-attachment asset-cache-dir name attachment)])
                         attachments))))))

(defn- write-cached-asset
  [^File asset-cache-dir asset]
  (t/track
    #(format "Writing to asset cache `%s'" asset-cache-dir)
    (.mkdirs asset-cache-dir)
    (let [content-file (io/file asset-cache-dir content-file-name)
          asset-file (io/file asset-cache-dir asset-file-name)]
      ;; Write the content first
      (io/copy (:content asset) content-file)
      (with-open [^Writer writer (io/writer asset-file)]
        (.write writer (-> asset
                           (write-attachments asset-cache-dir)
                           ;; The name of the content file is (currently) a fixed value,
                           ;; so we can just remove the key, rather than replace it with a file name.
                           (dissoc :content)
                           ;; Override *print-length* and *print-level* to ensure it is all written out.
                           ^String (pp/write :length nil :level nil :stream nil)))
        (.write writer "\n")))))

(defn- delete-dir-and-contents
  [^File dir]
  (when (.exists dir)
    (t/track
      #(format "Deleting directory `%s'" dir)
      (doseq [file (.listFiles dir)]
        (t/track
          #(format "Deleting file `%s'" file)
          (io/delete-file file)))
      (io/delete-file dir))))

(defn wrap-with-filesystem-cache
  "Used to implement file-system caching of assets (this is typically only used in development, not production).
  File system caching improves startup and first-request time by avoiding the cost of recompling assets; instead
  the assets are stored on the file system. The asset checksums are used to see if the cache is still valid.

  Only assets that have truthy value for :compiled key will be file-system cached. This is set by the various
  compiler and transformers, such as the CoffeeScript to JavaScript transformer.

  Assets that are accessed for aggregation are not cached (the final aggregated asset will be cached).

  asset-handler
  : handler to be wrapped

  cache-dir-name
  : name of root folder to store cache in (from the Twixt options); the directory will be created as necessary"
  [asset-handler cache-dir-name]
  (let [cache-dir (io/file cache-dir-name "compiled")]
    (l/infof "Caching compiled assets to `%s'." cache-dir)
    (.mkdirs cache-dir)
    (fn [asset-path {:keys [asset-resolver for-aggregation] :as context}]
      (let [asset-cache-dir (io/file cache-dir asset-path)
            cached-asset (read-cached-asset asset-cache-dir)]
        (if (is-valid? asset-resolver cached-asset)
          cached-asset
          (do
            (delete-dir-and-contents asset-cache-dir)
            (let [asset (asset-handler asset-path context)]
              (if (and
                    (:compiled asset)
                    (not for-aggregation))
                (write-cached-asset asset-cache-dir asset))
              asset)))))))