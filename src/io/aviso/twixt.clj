(ns io.aviso.twixt
  "Pipeline for accessing and transforming assets for streaming. Integrates with Ring."
  (use io.aviso.twixt.streamable)
  (require [clojure.java.io :as io]
           [clojure.tools.logging :as l]
           [io.aviso.twixt
            [dependency :as d]
            [coffee-script :as cs]
            [less :as less]
            [jade :as jade]
            [utils :as u]
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

(defprotocol Twixt
  "Captures the configuration and logic for asset access and transformation."
  
  (get-asset-uri [this asset-path] "Given an asset path (under the configured asset root), returns the URI that can be used to access the asset, or null if the asset does not exist.")
  (get-streamable [this path] "Gets the Streamable defined by the path. May return nil if the path does not map to a streamable.")
  (create-middleware [this] "Creates a Ring middleware function (a function that takes and returns a Ring handler function)."))

(defn- wrap-cache-handler [delegate]
  (let [streamable-cache (atom {})]
    (fn streamable-cache-handler [path]
      (let [cached (get @streamable-cache path)]
        (if (or (nil? cached) (-> cached dirty?))
          (do
            ;; A small race condition: two threads may both continue down through the delegate.
            ;; However, the result will be identical regardless of which thread wins the race.
            (swap! streamable-cache dissoc path)
            (let [result (delegate path)]
              (if result
                (swap! streamable-cache assoc path result))
              result))
          ;; Otherwise: cached value is up-to-date and fine
          cached)))))


(defn- create-streamable-source
  "Creates a source for streamables on the classpath, under the given root folder (e.g., \"META-INF/assets/\"."
  [root dependency-tracker-factory to-content-type]
  ;; In the future, we will have multiple domains from which to locate assets; some will be "virtual" (such as
  ;; aggregated JavaScript), others representing filesystem or database contents.
  (fn streamable-source
    ([path]
     (streamable-source path (dependency-tracker-factory)))
    ([path tracker]
     (let [resource-path (str root path)
           url (io/resource resource-path)]
       (if url
         (let [create-relative (fn [relative-path]
                                 ;; Calculate a new relative path but re-use the tracker.
                                 (streamable-source (compute-relative-path path relative-path) tracker))]
           (create-streamable (d/track! tracker url) create-relative resource-path (to-content-type path) url)))))))

(defn- extract-file-extension [^String path]
  (let [dotx (.lastIndexOf path ".")]
    (.substring path (inc dotx))))

(defn- create-path->content-type [content-types]
  (fn to-content-type [path]
    (or (->> path
             extract-file-extension
             (get content-types))
        "application/octet-stream")))

(defn- transform-by-content-type
  "Transforms a source streamable by mapping its content type to a map of content-type to transformer function.
  If a function is found, it is passed the source."
  [source transformers]
  (let [transformer (some->> source content-type (get transformers))]
    (if transformer
      (recur (transformer source) transformers)
      ;; No transformer for this content type, pass it through unchanged
      source)))

(defn- create-wrap-transformer
  "Handles transformation from on content type to another.
  
  transformers - map from content/type to transformer factory
  
  A transformer factory is is passed the Twixt configuration, and must return a transformer.
  A transformer is passed a streamable and returns a new streamable."
  [twixt-config transformers]
  (let [instantiated-transformers (u/transform-values transformers #(% twixt-config))]
    (fn wrap [delegate]
      (fn transform-handler [path]
        (if-let [streamable (delegate path)]
          (transform-by-content-type streamable instantiated-transformers))))))


(defn- wrap-tracker [delegate]
  (fn [path]
    (tracker/trace
      #(format "Constructing Streamable for `%s'." path)
      (delegate path))))

(defn- create-streamable-pipeline
  [core-provider wrap-transformer]
  (->
    core-provider
    ;; TODO:
    ;; - GZip caching
    ;; - CSS minimization
    ;; - JS minimization
    ;; - JS Aggregation
    wrap-transformer
    wrap-cache-handler
    wrap-tracker))

(defn- match? [^String path-prefix ^String path]
  (and
    (.startsWith path path-prefix)
    (not (or (= path path-prefix)
             (.endsWith path "/")))))

(defn- create-twixt-handler [path-prefix streamable-pipeline]
  (fn twixt-handler [req]
    (let [^String path (:uri req)]
      (if (match? path-prefix path)
        (tracker/trace
          #(format "Handling asset request `%s'." path)
          (if-let [streamable (streamable-pipeline (.substring path (.length path-prefix)))]
            (-> streamable
                open
                r/response
                (r/content-type (content-type streamable)))
            ;; else
            (l/warnf "Asset path `%s' in request does not match an available file." path)))))))

(def default-options
  {:path-prefix "/assets/"
   :root "META-INF/assets/"
   :content-types (merge mime/default-mime-types {"coffee" "text/coffeescript"
                                                  "less" "text/less"
                                                  "jade" "text/jade"})
   :transformers {"text/coffeescript" cs/coffee-script-compiler-factory
                  "text/less" less/less-compiler-factory
                  "text/jade" jade/jade-compiler-factory}
   :development-mode false
   :cache-enabled false ;; file-system cache is always enabled in development mode
   :cache-folder (System/getProperty "twixt.cache-dir" (System/getProperty "java.io.tmpdir"))})

(defn new-twixt
  "Creates a new Twixt from the provided options. All of the options are merged together (recursively) to form
  the final set of options."
  [& options]
  (let [merged-options (apply u/merge-maps-recursively default-options options)
        {:keys [path-prefix root content-types transformers options development-mode]} merged-options
        to-content-type (create-path->content-type content-types)
        tracker-factory (if development-mode d/create-dependency-tracker d/create-placeholder-tracker)
        core-provider (create-streamable-source root tracker-factory to-content-type)
        wrap-transformer (create-wrap-transformer merged-options transformers)
        pipeline (create-streamable-pipeline core-provider wrap-transformer)]
    (reify
      
      Twixt
      
      (get-asset-uri
        [this asset-path]
        ;; This will get more complex when the checksum is incorprated into the asset's URI.
        (tracker/trace
          #(format "Constructing URI for asset `%s'" asset-path)
          (when (pipeline asset-path)
            (str path-prefix asset-path))))
      
      (get-streamable [this path] (pipeline path))
      
      (create-middleware
        [this]
        (fn middleware [handler]
          (l/infof "Mapping request URL `%s' to resources under `%s'%s." 
                   path-prefix root
                   (if development-mode " (development mode)"))
          (let [twixt-handler (create-twixt-handler path-prefix pipeline)]
            (fn [req]
              (or
                (twixt-handler req)
                (handler req)))))))))

(defn wrap-with-twixt 
  "Wraps a handler with Twixt middleware, to handle requests for assets."
  [handler twixt]
  ((-> twixt create-middleware) handler))

(defn get-asset-uris 
  "Converts a number of asset paths into client URIs."
  [twixt & paths]
  (map (partial get-asset-uri twixt) paths))
