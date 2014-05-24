(ns io.aviso.twixt
  "Pipeline for accessing and transforming assets for streaming. Integrates with Ring.

   Twixt plugs into the Ring pipeline, but most of its work is done in terms of the asset pipeline.

   An asset pipeline handler is passed two values: an asset path and a Twixt context, and returns an asset.

   The asset path is a string, identifying the location of the asset on the classpath, beneath
   `META-INF/assets`.

   The Twixt context provides additional information that may be needed
   when resolving the asset.

   The asset itself is a map with a specific set of keys.

   As with Ring, there's the concept of middleware; functions that accept an asset handler (plus optional
   additional parameters) and return a new asset handler."
  (:require
    [clojure.java.io :as io]
    [io.aviso.tracker :as t]
    [io.aviso.twixt
     [asset :as asset]
     [compress :as compress]
     [css-rewrite :as rewrite]
     [fs-cache :as fs]
     [memory-cache :as mem]
     [utils :as utils]]
    [ring.util.mime-type :as mime]))

;;; Lots of stuff from Tapestry 5.4 is not yet implemented
;;; - multiple domains (the context, the file system, etc.)
;;; - JavaScript aggregation
;;; - JavaScript minification
;;; - CSS minification

(defn- extract-file-extension [^String path]
  (let [dotx (.lastIndexOf path ".")]
    (.substring path (inc dotx))))

(defn- extract-content-type
  "Uses the resource-path's file extension to identify the content type."
  [content-types resource-path]
  (get content-types (extract-file-extension resource-path) "application/octet-stream"))

(defn- make-asset-map
  [content-types asset-path resource-path url]
  (utils/replace-asset-content {:asset-path    asset-path   ; to compute relative assets
                                :resource-path resource-path
                                :modified-at   (utils/modified-at url)}
                               (extract-content-type content-types resource-path)
                               (utils/read-content url)))

(defn make-asset-resolver
  "Factory for the resolver function which converts a path into an asset map.

  The factory function is passed the twixt options, which defines content types mappings.

  The resolver function is passed an asset path and a pipeline context (which is ignored);
  The asset path is converted to a classpath resource via the configuration;
  if the resource exists, it is converted to an asset map.

  If the asset does not exist, the resolver returns nil.

  The asset map has the following keys:

  - `:content` - the content of the asset in a form that is compatible with clojure.java.io
  - `:resource-path` - the full path of the underlying resource
  - `:content-type` - the MIME type of the content, as determined from the path's extension
  - `:size` - size of the asset in bytes
  - `:checksum` - Adler32 checksum of the content
  - `:modified-at` - instant at which the file was last modified (not always trustworthy for files packaged in JARs)"
  [{:keys [content-types]}]
  (fn [asset-path context]
    (let [resource-path (str "META-INF/assets/" asset-path)]
      (if-let [url (io/resource resource-path)]
        (make-asset-map content-types asset-path resource-path url)))))

(defn default-stack-frame-filter
  "The default stack frame filter function, used by the HTML excepton report to identify frames that can be hidden
  by default.

  This implementation hides frames that:

  - Are in the `clojure.lang` package
  - Are in the `sun.reflect` package
  - Do not have a line number."
  [frame]
  (not
    (or
      (nil? (:line frame))
      (-> frame :package (= "clojure.lang"))
      (-> frame :package (= "sun.reflect")))))

(def default-options
  "Provides the default options when using Twixt; these rarely need to be changed except, perhaps, for `:path-prefix`
  or `:cache-folder`, or by plugins."
  {:path-prefix          "/assets/"
   :content-types        mime/default-mime-types
   ;; Content transformer, e.g., compilers (such as CoffeeScript to JavaScript). Key is a content type,
   ;; value is a function passed an asset and Twixt context, and returns a new asset.
   :content-transformers {}
   ;; Identify which content types are compressable; all other content types are assumed to not be compressable.
   :compressable         #{"text/*" "application/edn" "application/json"}
   :cache-folder         (System/getProperty "twixt.cache-dir" (System/getProperty "java.io.tmpdir"))
   :stack-frame-filter   default-stack-frame-filter})

(defn- get-single-asset
  [asset-pipeline asset-path context]
  (or (asset-pipeline asset-path context)
      (throw (IllegalArgumentException. (format "Asset path `%s' does not map to an available resource."
                                                asset-path)))))

(defn get-asset-uris
  "Converts a number of asset paths into client URIs.
  Each path must exist.

  An asset path does not start with a leading slash.
  The default asset resolver locates each asset on the classpath under `META-INF/assets/`.

  - `context` - the `:twixt` key, extracted from the Ring request map
  - `paths` - asset paths"
  [{:keys [asset-pipeline path-prefix] :as context} & paths]
  (utils/nil-check context "nil context provided to get-asset-uris")
  (->> paths
       (map #(get-single-asset asset-pipeline % context))
       (map (partial asset/asset->request-path path-prefix))))

(defn get-asset-uri
  "Uses [[get-asset-uris]] to get a single URI for a single asset path.
  The resource must exist."
  [context asset-path]
  (first (get-asset-uris context asset-path)))

(defn find-asset-uri
  "Returns the URI for an asset, if it exists.
  If not, returns nil."
  [{:keys [asset-pipeline path-prefix] :as context} asset-path]
  (if-let [asset (asset-pipeline asset-path context)]
    (asset/asset->request-path path-prefix asset)))

(defn wrap-pipeline-with-tracing
  "The first middleware in the asset pipeline, used to trace the constuction of the asset."
  [asset-handler]
  (fn [asset-path context]
    (t/track
      #(format "Accessing asset `%s'" asset-path)
      (asset-handler asset-path context))))

(defn wrap-pipeline-with-per-content-type-transformation
  [asset-handler {:keys [content-transformers]}]
  (fn [asset-path context]
    (let [asset (asset-handler asset-path context)
          content-type (:content-type asset)
          transformer (get content-transformers content-type)]
      (if transformer
        (transformer asset context)
        asset))))

(defn default-wrap-pipeline-with-content-transformation
  "Used when constructing the asset pipeline, wraps a handler (normally, the asset resolver)
   with additional pipeline handlers based on
   the `:content-transformers` key of the Twixt options, plus CSS URL Rewriting."
  [asset-handler twixt-options]
  (->
    asset-handler
    (wrap-pipeline-with-per-content-type-transformation twixt-options)
    rewrite/wrap-with-css-rewriting))

(defn default-wrap-pipeline-with-caching
  "Used when constructing the asset pipeline to wrap the handler with production-mode or development-mode caching.

  This is invoked before adding support for compression."
  [asset-handler twixt-options development-mode]
  (cond->
    asset-handler
    ;; The file system cache should only be used in development and should come after anything downstream
    ;; that might compile.
    development-mode (fs/wrap-with-filesystem-cache (:cache-folder twixt-options))
    (not development-mode) mem/wrap-with-sticky-cache
    development-mode mem/wrap-with-invalidating-cache))

(defn default-wrap-pipeline-with-compressed-caching
  "Used when constructing the asset pipeline, after compression has been enabled, to cache the
  compressed version of assets."
  [asset-handler development-mode]
  (cond->
    asset-handler
    ;; Currently don't bother with file system cache for compression; it's fast enough not
    ;; to worry.
    (not development-mode) compress/wrap-with-sticky-compressed-caching
    development-mode compress/wrap-with-invalidating-compressed-caching))

(defn wrap-pipeline-with-asset-resolver
  "Wraps the asset handler so that the `:asset-resolver` key is set to the asset resolver; the asset resolver
  is a way to bypass intermediate steps and gain access to the asset in its completely untransformed format."
  [asset-handler asset-resolver]
  (fn [asset-path context]
    (asset-handler asset-path (assoc context :asset-resolver asset-resolver))))

(defn default-asset-pipeline
  "Sets up the default pipeline in either development mode or production mode.

  The asset pipeline starts with a resolver, which is then intercepted using asset pipeline middleware.
  As with Ring, middleware is a function that accepts an asset-handler and returns an asset-handler. The asset-handler
  is passed an asset path and a context. The initial context is the value of the `:twixt` key from the
  Ring request map.

  The context will contain a `:asset-pipeline` key whose value is the asset pipeline in use.
  The context will contain a `:path-prefix` key, extracted from the twixt options.
  The context may also be passed to [[get-asset-uri]] (and related functions).

  In some cases, middlware may modify the context before passing it forward to the next asset-handler, typically
  by adding additional keys."
  [twixt-options development-mode]
  (let [asset-resolver (make-asset-resolver twixt-options)]
    (->
      asset-resolver
      (default-wrap-pipeline-with-content-transformation twixt-options)
      (default-wrap-pipeline-with-caching twixt-options development-mode)
      (compress/wrap-pipeline-with-compression twixt-options)
      (default-wrap-pipeline-with-compressed-caching development-mode)
      (wrap-pipeline-with-asset-resolver asset-resolver)
      wrap-pipeline-with-tracing)))





