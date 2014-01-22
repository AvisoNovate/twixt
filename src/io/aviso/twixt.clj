(ns io.aviso.twixt
  "Pipeline for accessing and transforming assets for streaming. Integrates with Ring."
  (:import [java.util Calendar TimeZone])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as l]
            [io.aviso.twixt
             [asset :as asset]
             [coffee-script :as cs]
             [compress :as compress]
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
  (utils/replace-asset-content {:asset-path    asset-path ; to compute relative assets
                                :resource-path resource-path
                                :modified-at   (utils/modified-at url)}
                               (extract-content-type content-types resource-path)
                               (utils/read-content url)))

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
   ;; Identify which content types are compressable; all other content types are assumed to not be compressable.
   :compressable       #{"text/*" "application/edn" "application/json"}
   :cache-folder       (System/getProperty "twixt.cache-dir" (System/getProperty "java.io.tmpdir"))
   :stack-frame-filter default-stack-frame-filter})

(defn- get-single-asset
  [asset-pipeline asset-path context]
  (or (asset-pipeline asset-path context)
      (throw (IllegalArgumentException. (format "Asset path `%s' does not map to an available resource.")))))

(defn get-asset-uris
  "Converts a number of asset paths into client URIs. Each path must exist.

  context - the :twixt key, extracted from the Ring request map (as provided by the twixt-setup handler)
  paths - asset paths"
  [{:keys [asset-pipeline path-prefix] :as context} & paths]
  (if (nil? context)
    (throw (IllegalArgumentException. "nil context provided to get-asset-uris")))
  (->> paths
       (map #(get-single-asset asset-pipeline % context))
       (map (partial asset/asset->request-path path-prefix))))

(defn get-asset-uri
  "Uses get-asset-uris to get a single URI for a single asset path. The resource must exist."
  [context asset-path]
  (first (get-asset-uris context asset-path)))

(defn find-asset-uri
  "Returns the URI for an asset, if it exists. If not, returns nil."
  [{:keys [asset-pipeline path-prefix] :as context} asset-path]
  (if-let [asset (asset-pipeline asset-path context)]
    (asset/asset->request-path path-prefix asset)))

(defn wrap-with-tracing
  "The first middleware in the asset pipeline, used to trace the constuction of the asset."
  [handler]
  (fn tracer [asset-path context]
    (t/trace
      #(format "Accessing asset `%s'" asset-path)
      (handler asset-path context))))

(defn default-wrap-pipeline-with-compilation
  "Used when constructing the asset pipeline, wraps a handler (normally, the asset resolver)
  with additional pipeline handlers for Less, CoffeeScript, and Jade compilation as well as CSS URL Rewriting."
  [handler development-mode]
  (->
    handler
    less/wrap-with-less-compilation
    cs/wrap-with-coffee-script-compilation
    (jade/wrap-with-jade-compilation development-mode)
    rewrite/wrap-with-css-rewriting))

(defn default-wrap-pipeline-with-caching
  "Used when constructing the asset pipeline to wrap the handler with production-mode or development-mode
  caching. This is invoked before adding support for compression."
  [handler twixt-options asset-resolver development-mode]
  (cond->
    handler
    ;; The file system cache should only be used in development and should come after anything downstream
    ;; that might compile.
    development-mode (fs/wrap-with-filesystem-cache (:cache-folder twixt-options) asset-resolver)
    (not development-mode) mem/wrap-with-sticky-cache
    development-mode mem/wrap-with-invalidating-cache))

(defn default-wrap-pipeline-with-compressed-caching
  "Used when constructing the asset pipeline, after compression has been enabled, to cache the
  compressed version of assets."
  [handler development-mode]
  (cond->
    handler
    ;; Currently don't bother with file system cache for compression; it's fast enough not
    ;; to worry.
    (not development-mode) compress/wrap-with-sticky-compressed-caching
    development-mode compress/wrap-with-invalidating-compressed-caching))

(defn default-asset-pipeline
  "Sets up the default pipeline in either development mode or production mode.

  The asset pipeline starts with a resolver, which is then intercepted using asset pipeline middleware.
  As with Ring, middleware is a function that accepts a handler and returns a handler. The handler
  is passed an asset path and a context. The initial context is the value of the :twixt key from the
  Ring request map.

  The context will contain a :asset-pipeline key whose value is the asset pipeline in use.
  The context will contain a :path-prefix key, extracted from the twixt options.
  The context may also be passed to `get-asset-uri` (and related functions).

  In some cases, middlware may modify the context before passing it forward to the next handler, typically
  by adding additional keys."
  [twixt-options development-mode]
  (let [resolver (make-asset-resolver twixt-options)
        production-mode (not development-mode)]
    (->
      resolver
      (default-wrap-pipeline-with-compilation development-mode)
      (default-wrap-pipeline-with-caching twixt-options resolver development-mode)
      (compress/wrap-with-compression twixt-options)
      (default-wrap-pipeline-with-compressed-caching development-mode)
      wrap-with-tracing)))

(defn wrap-with-twixt-setup
  "Wraps a handler with another handler that provides the :twixt key in the request object.

  The :twixt key is the default asset pipeline context, which is needed by get-asset-uri in order to resolve asset paths
  to an actual asset. It also contains the keys :asset-pipeline (the pipeline used to resolve assets) and
  :stack-frame-filter (which is used by the HTML exception report).

  This provides the information needed by the actual Twixt handler, as well as anything else downstream that needs to
  generate Twixt asset URIs."
  [handler twixt-options asset-pipeline]
  (let [twixt (-> twixt-options
                  ;; Pass down only what is needed to generate asset URIs, or to produce the HTML exception report.
                  (select-keys [:path-prefix :stack-frame-filter])
                  (assoc :asset-pipeline asset-pipeline))]
    (fn twixt-setup [request]
      (handler (assoc request :twixt twixt)))))

(defn- parse-path
  "Parses the complete request path into a checksum, compressed-flag, and asset path."
  [^String path-prefix ^String path]
  (let [suffix (.substring path (.length path-prefix))
        slashx (.indexOf suffix "/")
        full-checksum (.substring suffix 0 slashx)
        compressed? (.startsWith full-checksum "z")
        checksum (if compressed? (.substring full-checksum 1) full-checksum)
        asset-path (.substring suffix (inc slashx))]
    [checksum
     compressed?
     asset-path]))

(def ^:private far-future
  (-> (doto (Calendar/getInstance (TimeZone/getTimeZone "UTC"))
        (.add Calendar/YEAR 10))
      .getTime))

;;; This has an issue, in that it is not extensible. So we end up with a special case for compression ... but
;;; perhaps there are other cases. This may be the dual of the asset pipeline.

(defn- asset->ring-response
  [asset]
  (cond->
    ;; First the standard stuff ...
    (-> asset
        :content
        io/input-stream
        r/response
        (r/header "Content-Length" (:size asset))
        ;; Because any change to the content will create a new checksum and a new URL, a change to the content
        ;; is really an entirely new resource. The current resource is therefore immutable and can have a far-future expires
        ;; header.
        (r/header "Expires" far-future)
        (r/content-type (:content-type asset)))
    ;; The optional extras ...
    (:compressed asset) (r/header "Content-Encoding" "gzip")))

(defn- asset->redirect-response
  [status path-prefix asset]
  {:status  status
   :headers {"Location" (asset/asset->request-path path-prefix asset)}
   :body    ""})

(def ^:private asset->301-response (partial asset->redirect-response 301))

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
        #(format "Handling asset request `%s'" path)
        (let [[requested-checksum compressed? asset-path] (parse-path path-prefix path)
              context (:twixt request)
              ;; When actually servicing an asset request, we have to trust the data in the URL
              ;; that determines whether to server the normal or gzip'ed resource.
              context' (assoc context :gzip-enabled compressed?)
              asset-pipeline (:asset-pipeline context)]
          (create-asset-response path-prefix
                                 requested-checksum
                                 (asset-pipeline asset-path context')))))))

(defn wrap-with-asset-redirector
  "In some cases, it is not possible for the client to know what the full asset URI will be, such as when the
  URL is composed on the client (in which case, the asset checksum will not be known). The redirector accepts
  any request path that maps to an asset and returns a redirect to the asset's true URL. Non-matching
  requests are passed through to the provided handler.

  For a file under `META-INF/assets`, such as `META-INF/assets/myapp/icon.png`, the redirector will match
  the URI `/myapp/icon.png` and send a redirect to `/assets/123abc/myapp/icon.png`.

  This middleware is not applied by default."
  [handler]
  (fn asset-redirector [{path    :uri
                         context :twixt
                         :as     request}]
    (let [{:keys [asset-pipeline path-prefix]} context
          asset-path (.substring path 1)]
      (if-let [asset (asset-pipeline asset-path context)]
        (asset->redirect-response 302 path-prefix asset)
        (handler request)))))

(defn wrap-with-twixt
  "Invokes the twixt-handler and delegates to the provided handler if twixt-handler returns nil.

  This assumes that the resulting handler will then be wrapped with the twixt setup.

  In most cases, you will want to use the wrap-with-twixt function in the exceptions namespace."
  [handler]
  (fn twixt-wrapper [request]
    (or (twixt-handler request)
        (handler request))))