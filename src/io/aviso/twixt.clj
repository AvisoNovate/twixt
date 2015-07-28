(ns io.aviso.twixt
  "Pipeline for accessing and transforming assets for streaming.

   Twixt integrates with Ring, as a set of Ring request filters.

   Twixt plugs into the Ring pipeline, but most of its work is done in terms of the asset pipeline.

   An asset pipeline handler is passed two values: an asset path and a Twixt context, and returns an asset.

   The asset path is a string, identifying the location of the asset on the classpath, beneath
   `META-INF/assets`.

   The Twixt context provides additional information that may be needed
   when resolving the asset.

   The asset itself is a map with a specific set of keys.

   As with Ring, there's the concept of middleware; functions that accept an asset handler (plus optional
   additional parameters) and return a new asset handler."
  (:require [clojure.java.io :as io]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [io.aviso.tracker :as t]
            [io.aviso.twixt
             [asset :as asset]
             [compress :as compress]
             [css-rewrite :as rewrite]
             [fs-cache :as fs]
             [memory-cache :as mem]
             [js-minification :as js]
             [schemas :refer [Asset AssetHandler AssetPath AssetURI TwixtContext ResourcePath]]
             [utils :as utils]]
            [ring.util.mime-type :as mime]
            [schema.core :as s])
  (:import [java.net URL]))

;;; Lots of stuff from Tapestry 5.4 is not yet implemented
;;; - multiple domains (the context, the file system, etc.)
;;; - CSS minification
;;; - AMD/RequireJS modules

(defn- extract-file-extension [^String path]
  (let [dotx (.lastIndexOf path ".")]
    (.substring path (inc dotx))))

(defn- extract-content-type
  "Uses the resource-path's file extension to identify the content type."
  [content-types resource-path]
  (get content-types (extract-file-extension resource-path) "application/octet-stream"))

(s/defn new-asset :- Asset
  "Create a new Asset.

  content-types
  : The content-types map from the Twixt options.

  asset-path
  : The path to the asset from the asset Root. This may be used in some tracking/debugging output.

  resource-path
  : The path to the asset on the classpath, used to locate the Asset's raw content.

  url
  : A URL used to access the raw content for the asset."
  {:size "0.1.17"}
  [content-types :- {s/Str s/Str}
   asset-path :- AssetPath
   resource-path :- ResourcePath
   url :- URL]
  (let [^bytes content-bytes (utils/read-content url)
        checksum             (utils/compute-checksum content-bytes)
        modified-at          (utils/modified-at url)]
    {:asset-path    asset-path
     :resource-path resource-path
     :modified-at   modified-at
     :content-type  (extract-content-type content-types resource-path)
     :content       content-bytes
     :size          (alength content-bytes)
     :checksum      checksum
     :dependencies  {resource-path {:asset-path  asset-path
                                    :checksum    checksum
                                    :modified-at modified-at}}}))

(defn- make-simple-resolver
  [{:keys [content-types]} resource-path-root]
  (fn [asset-path _context]
    (let [resource-path (str resource-path-root asset-path)]
      (if-let [url (io/resource resource-path)]
        (new-asset content-types asset-path resource-path url)))))

(s/defn make-asset-resolver :- AssetHandler
  "Factory for the standard Asset resolver function which converts a path into an Asset.

  The factory function is passed the Twixt options, which defines content types mappings.

  The resolver function is passed an asset path and a pipeline context (which is ignored);
  The asset path is converted to a classpath resource via the configuration;
  if the resource exists, it is converted to an Asset.

  If the Asset does not exist, the resolver returns nil.

  An Asset has the minimum following keys:

  :content
  : content of the asset in a form that is compatible with clojure.java.io

  :asset-path
  : path of the asset under the root folder /META-INF/assets/

  :resource-path
  : full path of the underlying resource

  :content-type
  : MIME type of the content, as determined from the path's extension

  :size
  : size of the asset in bytes

  :checksum
  : Adler32 checksum of the content

  :modified-at
  : instant at which the file was last modified (not always trustworthy for files packaged in JARs)

  :compiled
  : _optional_ - true for assets that represent some form of compilation (or aggregation) and should be cached

  :aggregate-asset-paths
  : _optional_ - seq of asset paths from which a stack asset was constructed

  :dependencies
  : _optional_ - used to track underlying dependencies; a map of resource path to details about that resource
  (keys :asset-path, :checksum, and  :modified-at)

  :attachments
  : _optional_ - map of string name to attachment (with keys :content, :size, and :content-type)"
  [twixt-options]
  (make-simple-resolver twixt-options "META-INF/assets/"))

(s/defn make-webjars-asset-resolver :- AssetHandler
  "As with [[make-asset-resolver]], but "
  {:since "0.1.17"}
  [twixt-options]
  (make-simple-resolver twixt-options "META-INF/resources/webjars/"))


(def default-options
  "Provides the default options when using Twixt; these rarely need to be changed except, perhaps, for :path-prefix
  or :cache-folder, or by plugins."
  {:path-prefix          "/assets/"
   :content-types        mime/default-mime-types
   :resolver-factories   [make-asset-resolver make-webjars-asset-resolver]
   ;; Content transformer, e.g., compilers (such as CoffeeScript to JavaScript). Key is a content type,
   ;; value is a function passed an asset and Twixt context, and returns a new asset.
   :content-transformers {}
   ;; Identify which content types are compressable; all other content types are assumed to not be compressable.
   :compressable         #{"text/*" "application/edn" "application/json"}
   :js-optimizations     :default
   :cache-folder         (System/getProperty "twixt.cache-dir" (System/getProperty "java.io.tmpdir"))})

(defn- get-single-asset
  [asset-pipeline context asset-path]
  {:pre [(some? asset-pipeline)
         (some? context)
         (some? asset-path)]}
  (or (asset-pipeline asset-path context)
      (throw (ex-info (format "Asset path `%s' does not map to an available resource." asset-path)
                      context))))

(s/defn get-asset-uris :- [AssetURI]
  "Converts a number of asset paths into client URIs.
  Each path must exist.

  An asset path does not start with a leading slash.
  The default asset resolver locates each asset on the classpath under `META-INF/assets/`.

  Unlike [[get-asset-uri]], this function will (in _development mode_) expand stack assets
  into the asset URIs for all component assets.

  context
  : the :twixt key, extracted from the Ring request map

  paths
  : asset paths to convert to URIs"
  [{:keys [asset-pipeline path-prefix development-mode] :as context} :- TwixtContext
   & paths :- [AssetPath]]
  (loop [asset-uris []
         [path & more-paths] paths]
    (if-not (some? path)
      asset-uris
      (let [asset (get-single-asset asset-pipeline context path)
            aggregate-asset-paths (-> asset :aggregate-asset-paths seq)]
        (if (and development-mode aggregate-asset-paths)
          (recur (into asset-uris (apply get-asset-uris context aggregate-asset-paths))
                 more-paths)
          (recur (conj asset-uris (asset/asset->request-path path-prefix asset))
                 more-paths))))))

(s/defn get-asset-uri :- AssetURI
  "Converts a single asset paths into a client URI.
  Throws an exception if the path does not exist.

  The default asset resolver locates each asset on the classpath under `META-INF/assets/`.

  This works much the same as [[get-asset-uris]] except that stack asset will
  be a URI to the stack (whose content is the aggregation of the components of the stack)
  rather than the list of component asset URIs.
  This matches the behavior of `get-asset-uris` in _production_, but you should use
  `get-asset-uris` in development, since in development, you want the individual
  component assets, rather than the aggregated whole.

  context
  : the :twixt key, extracted from the Ring request map

  asset-path
  : path to the asset; asset paths do __not__ start with a leading slash"
  [context :- TwixtContext
   asset-path :- AssetPath]
  (let [{:keys [asset-pipeline path-prefix]} context]
    (->>
      asset-path
      (get-single-asset asset-pipeline context)
      (asset/asset->request-path path-prefix))))

(s/defn find-asset-uri :- (s/maybe AssetURI)
  "Returns the URI for an asset, if it exists.
  If not, returns nil."
  [{:keys [asset-pipeline path-prefix] :as context}
   asset-path :- AssetPath]
  (if-let [asset (asset-pipeline asset-path context)]
    (asset/asset->request-path path-prefix asset)))

(s/defn wrap-pipeline-with-tracing :- AssetHandler
  "The first middleware in the asset pipeline, used to trace the construction of the asset."
  [asset-handler :- AssetHandler]
  (fn [asset-path context]
    (t/track
      #(format "Accessing asset `%s'" asset-path)
      (asset-handler asset-path context))))

(s/defn wrap-pipeline-with-per-content-type-transformation :- AssetHandler
  [asset-handler :- AssetHandler
   {:keys [content-transformers]}]
  (fn [asset-path context]
    (let [asset (asset-handler asset-path context)
          content-type (:content-type asset)
          transformer (get content-transformers content-type)]
      (if transformer
        (transformer asset context)
        asset))))

(s/defn default-wrap-pipeline-with-content-transformation :- AssetHandler
  "Used when constructing the asset pipeline, wraps a handler (normally, the asset resolver)
   with additional pipeline handlers based on
   the `:content-transformers` key of the Twixt options, plus JavaScript minification and CSS URL Rewriting."
  [asset-handler :- AssetHandler
   twixt-options]
  (->
    asset-handler
    (wrap-pipeline-with-per-content-type-transformation twixt-options)
    (js/wrap-with-javascript-minimizations twixt-options)
    rewrite/wrap-with-css-rewriting))

(s/defn default-wrap-pipeline-with-caching :- AssetHandler
  "Used when constructing the asset pipeline to wrap the handler with production-mode or development-mode caching.

  This is invoked before adding support for compression."
  [asset-handler :- AssetHandler
   twixt-options
   development-mode :- s/Bool]
  (cond->
    asset-handler
    ;; The file system cache should only be used in development and should come after anything downstream
    ;; that might compile.
    development-mode (fs/wrap-with-filesystem-cache (:cache-folder twixt-options))
    (not development-mode) mem/wrap-with-sticky-cache
    development-mode mem/wrap-with-invalidating-cache))

(s/defn default-wrap-pipeline-with-compressed-caching :- AssetHandler
  "Used when constructing the asset pipeline, after compression has been enabled, to cache the
  compressed version of assets."
  [asset-handler :- AssetHandler
   development-mode :- s/Bool]
  (cond->
    asset-handler
    ;; Currently don't bother with file system cache for compression; it's fast enough not
    ;; to worry.
    (not development-mode) compress/wrap-with-sticky-compressed-caching
    development-mode compress/wrap-with-invalidating-compressed-caching))

(s/defn wrap-pipeline-with-asset-resolver :- AssetHandler
  "Wraps the asset handler so that the :asset-resolver key is set to the asset resolver; the asset resolver
  is a way to bypass intermediate steps and gain access to the asset in its completely untransformed format."
  [asset-handler :- AssetHandler
   asset-resolver]
  (fn [asset-path context]
    (asset-handler asset-path (assoc context :asset-resolver asset-resolver))))

(defn- merge-handlers [handlers]
  (fn [asset-path context]
    (loop [[h & more-handlers] handlers]
      (cond-let
        (nil? h)
        nil

        [result (h asset-path context)]

        (some? result)
        result

        :else
        (recur more-handlers)))))

(s/defn default-asset-pipeline :- AssetHandler
  "Sets up the default pipeline.

  The asset pipeline starts with a resolver, which is then intercepted using asset pipeline middleware.
  As with Ring, middleware is a function that accepts an asset-handler and returns an asset-handler. The asset-handler
  is passed an asset path and a context. The initial context is the value of the `:twixt` key from the
  Ring request map.

  In production mode, JavaScript will be minimized.

  The context will contain an :asset-pipeline key whose value is the asset pipeline in use.
  The context will contain a :path-prefix key, extracted from the twixt options.
  The context may also be passed to [[get-asset-uri]] (and related functions).

  In some cases, middlware may modify the context before passing it forward to the next asset-handler, typically
  by adding additional keys."
  [twixt-options]
  (let [{:keys [development-mode resolver-factories]} twixt-options
        asset-resolvers (for [factory resolver-factories]
                          (factory twixt-options))
        asset-resolver  (merge-handlers asset-resolvers)]
    (->
      asset-resolver
      (default-wrap-pipeline-with-content-transformation twixt-options)
      (default-wrap-pipeline-with-caching twixt-options development-mode)
      (compress/wrap-pipeline-with-compression twixt-options)
      (default-wrap-pipeline-with-compressed-caching development-mode)
      (wrap-pipeline-with-asset-resolver asset-resolver)
      wrap-pipeline-with-tracing)))

