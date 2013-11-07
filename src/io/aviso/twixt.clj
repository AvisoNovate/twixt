(ns io.aviso.twixt
  "Pipeline for accessing and transforming assets for streaming. Integrates with Ring."
  (:import [java.io File]
           [java.net URISyntaxException]
           [java.util Date])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as l]
            [io.aviso.twixt
             [coffee-script :as cs]
             [less :as less]
             [jade :as jade]
             [utils :as utils]
             [tracker :as tracker]]
            [ring.util
             [response :as r]
             [mime-type :as mime]]))

;;; Lots of stuff from Tapestry 5.4 is not yet implemented; probably won't be until this is spun off as an open-source
;;; GitHub project.
;;; - content checksum in URL
;;; - optional GZip compression
;;; - caching of GZip compressed content
;;; - rewriting of url()'s in CSS files
;;; - multiple domains (the context, the file system, etc.)

;; Not the same as io/file!
(defn- as-file
  [url]
  (try
    (-> url .toURI File.)
    (catch URISyntaxException e
      (-> url .getPath File.))))

(defn- modified-at
  [url]
  (some-> url as-file .lastModified Date.))

(defn- extract-file-extension [^String path]
  (let [dotx (.lastIndexOf path ".")]
    (.substring path (inc dotx))))

(defn- extract-content-type
  "Uses the resource-path's file extension to identify the content type."
  [content-types resource-path]
  (get content-types (extract-file-extension resource-path) "application/octet-stream"))

(defn- make-asset-map
  [content-types asset-path resource-path url]
  (let [content (utils/read-content url)]
    {:content       content
     :asset-path    asset-path ; to compute relative assets
     :resource-path resource-path
     :content-type  (extract-content-type content-types resource-path)
     :size          (alength content)
     :checksum      (utils/compute-checksum content)
     :modified-at   (modified-at url)
     }))

(defn make-asset-resolver
  "Factory for the resolver function which converts a path into an asset map.

  The factory function is passed the twixt options, which defines the root resource folder
  for assets as well as content types.

  The resolver function is passed an asset path, which is converted to a classpath resource
  via the configuration; if the resource exists, it is converted to an asset map.

  The asset map has the following keys:
  - :content the content of the asset in a form that is compatible with clojure.java.io
  - :resource-path the full path of the underlying resource
  - :content-type the MIME type of the content, as determined from the path's extension
  - :size size of the asset in bytes
  - :checksum Adler32 checksum of the content
  - :modified-at instant at which the file was last modified (not always trustworthy for files packaged in JARs)"
  [{:keys [root content-types]}]
  (fn resolver [path]
    (let [resource-path (str root path)]
      (if-let [url (io/resource resource-path)]
        (make-asset-map content-types path resource-path url)))))

(defn- match? [^String path-prefix ^String path]
  (and
    (.startsWith path path-prefix)
    (not (or (= path path-prefix)
             (.endsWith path "/")))))

(def default-options
  "Provides the default options when using Twixt; these rarely need to be changed except, perhaps, for :path-prefix
  or :cache-folder."
  {:path-prefix   "/assets/"
   :root          "META-INF/assets/"
   :content-types (merge mime/default-mime-types {"coffee" "text/coffeescript"
                                                  "less"   "text/less"
                                                  "jade"   "text/jade"})
   :cache-folder  (System/getProperty "twixt.cache-dir" (System/getProperty "java.io.tmpdir"))})

(defn- get-single-asset
  [asset-pipeline asset-path]
  (or (asset-pipeline asset-path)
      (throw (IllegalArgumentException. (format "Asset path `%s' does not map to an available resource.")))))

(defn get-asset-uris
  "Converts a number of asset paths into client URIs.

  twixt - the :twixt key, extracted from the Ring request map (as provided by the twixt-setup handler)
  paths - asset paths"
  [{:keys [asset-pipeline path-prefix]} & paths]
  (->> paths
       (map (partial get-single-asset asset-pipeline))
       (map :asset-path)
       ;; This will get more complex in the future when we incorporate the asset's checksum into the URI.
       ;; Also, there will be the question of raw assets vs. gzipped assets.
       ;; In the future, we need the final content to generate the URL that includes a checksum; in
       ;; the present, we are forcing the compilation of files now, rather than later.
       (map #(str path-prefix %))))

(defn get-asset-uri
  "Uses get-asset-uris to get a single URI for a single asset path."
  [twixt asset-path]
  (first (get-asset-uris twixt asset-path)))

(defn default-asset-pipeline
  "Sets up the default development-mode pipeline, which will ultimately include cross-execution file-system caching."
  [twixt-options development-mode]
  (let [resolver (make-asset-resolver twixt-options)]
    (cond->
      resolver
      true less/wrap-with-less-compilation
      true cs/wrap-with-coffee-script-compilation
      true (jade/wrap-with-jade-compilation development-mode))))


(defn wrap-with-twixt-setup
  "Wraps a handler with another handler that provides the :twixt key in the request object.

  This provides the information needed by the actual Twixt handler, as well as anything else downstream that needs to generate Twixt asset URIs."
  [handler twixt-options asset-pipeline]
  (fn twixt-setup [request]
    (handler (assoc request :twixt {:asset-pipeline asset-pipeline
                                    :path-prefix    (:path-prefix twixt-options)}))))

(defn twixt-handler
  "A Ring request handler that identifies requests targetted for Twixt assets.  Returns a Ring response map
  if the request is for an existing asset, otherwise returns nil."
  [request]
  (let [path-prefix (-> request :twixt :path-prefix)
        path (:uri request)]
    (when (match? path-prefix path)
      (tracker/trace
        #(format "Handling asset request `%s'." path)
        (let [asset-path (.substring path (.length path-prefix))
              asset-pipeline (-> request :twixt :asset-pipeline)]
          (if-let [asset-map (asset-pipeline asset-path)]
            (-> asset-map
                :content
                io/input-stream
                r/response
                ;; TODO: more headers, including ETags
                (r/content-type (:content-type asset-map)))
            ;; else - TODO, only if path-prefix is not ""
            (l/warnf "Asset path `%s' in request does not match an available file." path)))))))

(defn wrap-with-twixt
  "Invokes the twixt-handler and delegates to the provided handler if twixt-handler returns nil."
  [handler]
  (fn twixt-wrapper [request]
    (or (twixt-handler request)
        (handler request))))