(ns io.aviso.twixt
  "Pipeline for accessing and transforming assets for streaming. Integrates with Ring."
  (:import [java.util Calendar TimeZone])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as l]
            [io.aviso.twixt
             [asset :as asset]
             [coffee-script :as cs]
             [css-rewrite :as rewrite]
             [fs-cache :as fs]
             [jade :as jade]
             [less :as less]
             [memory-cache :as mem]
             [utils :as utils]
             [tracker :as t]]
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
     :modified-at   (utils/modified-at url)}))

(defn make-asset-resolver
  "Factory for the resolver function which converts a path into an asset map.

  The factory function is passed the twixt options, which defines the root resource folder
  for assets as well as content types.

  The resolver function is passed an asset path and a pipeline context (which is ignored);
  The asset path is which is converted to a classpath resource
  via the configuration; if the resource exists, it is converted to an asset map.

  The asset map has the following keys:
  - :content the content of the asset in a form that is compatible with clojure.java.io
  - :resource-path the full path of the underlying resource
  - :content-type the MIME type of the content, as determined from the path's extension
  - :size size of the asset in bytes
  - :checksum Adler32 checksum of the content
  - :modified-at instant at which the file was last modified (not always trustworthy for files packaged in JARs)"
  [{:keys [root content-types]}]
  (fn resolver [path context]
    (let [resource-path (str root path)]
      (if-let [url (io/resource resource-path)]
        (make-asset-map content-types path resource-path url)))))

(defn- match? [^String path-prefix ^String path]
  (and
    (.startsWith path path-prefix)
    (not (or (= path path-prefix)
             (.endsWith path "/")))))

(defn default-stack-frame-filter
  "The default stack frame filter function, used by the HTML excepton report to identify frames that can be hidden
  by default.

  This implementation hides frames that:
  - Are in the clojure.lang package
  - Are in the sun.reflect package
  - Do not have a line number."
  [frame]
  (not
    (or
      (nil? (:line frame))
      (-> frame :package (= "clojure.lang"))
      (-> frame :package (= "sun.reflect")))))

(def default-options
  "Provides the default options when using Twixt; these rarely need to be changed except, perhaps, for :path-prefix
  or :cache-folder."
  {:path-prefix        "/assets/"
   :root               "META-INF/assets/"
   :content-types      (merge mime/default-mime-types {"coffee" "text/coffeescript"
                                                       "less"   "text/less"
                                                       "jade"   "text/jade"})
   :cache-folder       (System/getProperty "twixt.cache-dir" (System/getProperty "java.io.tmpdir"))
   :stack-frame-filter default-stack-frame-filter})

(defn- get-single-asset
  [asset-pipeline asset-path context]
  (or (asset-pipeline asset-path context)
      (throw (IllegalArgumentException. (format "Asset path `%s' does not map to an available resource.")))))

(defn get-asset-uris
  "Converts a number of asset paths into client URIs.

  twixt - the :twixt key, extracted from the Ring request map (as provided by the twixt-setup handler)
  paths - asset paths"
  [{:keys [asset-pipeline path-prefix] :as context} & paths]
  (->> paths
       (map #(get-single-asset asset-pipeline % context))
       (map (partial asset/asset->request-path path-prefix))))

(defn get-asset-uri
  "Uses get-asset-uris to get a single URI for a single asset path."
  [twixt asset-path]
  (first (get-asset-uris twixt asset-path)))

(defn wrap-with-tracing
  "The first middleware in the asset pipeline, used to trace the constuction of the asset."
  [handler]
  (fn tracer [asset-path context]
    (t/trace
      #(format "Accessing asset `%s'" asset-path)
      (handler asset-path context))))

(defn default-asset-pipeline
  "Sets up the default pipeline in either development mode or production mode."
  [twixt-options development-mode]
  (let [resolver (make-asset-resolver twixt-options)
        production-mode (not development-mode)]
    (cond->
      resolver
      true less/wrap-with-less-compilation
      true cs/wrap-with-coffee-script-compilation
      true (jade/wrap-with-jade-compilation development-mode)
      true rewrite/wrap-with-css-rewriting
      ;; The file system cache should only be used in development and should come after anything downstream
      ;; that might compile.
      development-mode (fs/wrap-with-filesystem-cache (:cache-folder twixt-options) resolver)
      production-mode mem/wrap-with-sticky-cache
      development-mode mem/wrap-with-invalidating-cache
      true wrap-with-tracing)))

(defn wrap-with-twixt-setup
  "Wraps a handler with another handler that provides the :twixt key in the request object.

  This provides the information needed by the actual Twixt handler, as well as anything else downstream that needs to generate Twixt asset URIs."
  [handler twixt-options asset-pipeline]
  (let [twixt (-> twixt-options
                  ;; Pass down only what is needed to generate asset URIs, or to produce the HTML exception report.
                  (select-keys [:path-prefix :stack-frame-filter])
                  (assoc :asset-pipeline asset-pipeline))]
    (fn twixt-setup [request]
      (handler (assoc request :twixt twixt)))))

(defn- parse-path
  "Parses the complete request path into a checksum and asset path."
  [path-prefix path]
  (let [suffix (.substring path (.length path-prefix))
        slashx (.indexOf suffix "/")]
    [(.substring suffix 0 slashx)
     (.substring suffix (inc slashx))]))

(def ^:private far-future (-> (doto (Calendar/getInstance (TimeZone/getTimeZone "UTC"))
                                (.add Calendar/YEAR 10))
                              .getTime))

(defn- asset->ring-response
  [asset]
  (-> asset
      :content
      io/input-stream
      r/response
      (r/header "Content-Length" (:size asset))
      ;; Because any change to the content will create a new checksum and a new URL, a change to the content
      ;; is really an entirely new resource. The current resource is therefore immutable and can have a far-future expires
      ;; header.
      (r/header "Expires" far-future)
      (r/content-type (:content-type asset))))

(defn- asset->301-response
  [path-prefix asset]
  {
    :status  301
    :headers {"Location" (asset/asset->request-path path-prefix asset)}
    :body    ""})

(defn- create-asset-response
  [path-prefix requested-checksum asset]
  (cond
    (nil? asset) nil
    (= requested-checksum (:checksum asset)) (asset->ring-response asset)
    :else (asset->301-response path-prefix asset)))

(defn twixt-handler
  "A Ring request handler that identifies requests targetted for Twixt assets.  Returns a Ring response map
  if the request is for an existing asset, otherwise returns nil.

  Asset URLs always include the intended asset's checksum; if the actual asset checksum does not match, then
  a 301 (moved permanently) response is sent with the correct asset URL."
  [request]
  (let [path-prefix (-> request :twixt :path-prefix)
        path (:uri request)]
    (when (match? path-prefix path)
      (t/trace
        #(format "Handling asset request `%s'." path)
        (let [[requested-checksum asset-path] (parse-path path-prefix path)
              twixt (:twixt request)
              asset-pipeline (:asset-pipeline twixt)]
          (create-asset-response path-prefix requested-checksum (asset-pipeline asset-path twixt)))))))

(defn wrap-with-twixt
  "Invokes the twixt-handler and delegates to the provided handler if twixt-handler returns nil.

  This assumes that the resulting handler will then be wrapped with the twixt setup.

  In most cases, you will want to use the wrap-with-twixt function in the exceptions namespace."
  [handler]
  (fn twixt-wrapper [request]
    (or (twixt-handler request)
        (handler request))))