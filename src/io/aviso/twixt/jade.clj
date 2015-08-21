(ns io.aviso.twixt.jade
  "Provides asset pipeline middleware for compiling Jade templates to HTML using jade4j."
  (:require [clojure.java.io :as io]
            [io.aviso.twixt :as twixt]
            [io.aviso.tracker :as t]
            [io.aviso.twixt.utils :as utils]
            [medley.core :as medley])
  (:import [de.neuland.jade4j JadeConfiguration]
           [de.neuland.jade4j.exceptions JadeException]
           [de.neuland.jade4j.template TemplateLoader]))

(defn- add-missing-extension
  [^String name ext]
  (if (.endsWith name ext)
    name
    (str name ext)))

(defn- create-template-loader [root-asset asset-resolver dependencies]
  (reify TemplateLoader
    ;; getLastModified is only needed for caching, and we disable Jade's caching
    ;; in favor of Twixt's.
    (getLastModified [_ name] -1)
    (getReader [_ name]
      (if (= name (:asset-path root-asset))
        (-> root-asset :content io/reader)
        (let [full-name (add-missing-extension name ".jade")]
          (t/track
            #(format "Including Jade source from asset `%s'." full-name)
            ;; Use the asset-resolver, not the asset pipeline, because we need
            ;; the un-compiled version of the asset.
            (let [included (asset-resolver full-name nil)]
              (utils/nil-check included "Included asset does not exist.")
              (swap! dependencies utils/add-asset-as-dependency included)
              (-> included
                  :content
                  ;; We have to trust that Jade will close the reader.
                  io/reader))))))))

(defn- wrap-asset-pipeline-with-dependency-tracker
  [asset-pipeline dependencies]
  (fn [asset-path context]
    (let [asset (asset-pipeline asset-path context)]
      (swap! dependencies utils/add-asset-as-dependency asset)
      asset)))

(defn- create-shared-variables
  [asset {{:keys [helpers variables]} :jade :as context} dependencies]
  (let [context' (update-in context [:asset-pipeline]
                            wrap-asset-pipeline-with-dependency-tracker dependencies)]
    (-> (medley/map-vals
          #(% asset context')
          (or helpers {}))
        (merge variables))))

(defn- ^JadeConfiguration create-configuration
  [pretty-print asset {:keys [asset-resolver] :as context} dependencies]
  (doto (JadeConfiguration.)
    (.setPrettyPrint pretty-print)
    (.setSharedVariables (create-shared-variables asset context dependencies))
    (.setCaching false)
    (.setTemplateLoader (create-template-loader asset asset-resolver dependencies))))

(defn- jade-compiler [asset context]
  (let [name (:resource-path asset)]
    (t/timer
      #(format "Compiled `%s' to HTML in %.2f ms" name %)
      (t/track
        #(format "Compiling `%s' from Jade to HTML" name)
        (try
          ;; Seed the dependencies with the Jade source file. Any included
          ;; sources will be added to dependencies.
          (let [dependencies (atom {name (utils/extract-dependency asset)})
                configuration (create-configuration (-> context :development-mode) asset context dependencies)
                template (.getTemplate configuration (:asset-path asset))
                compiled-output (.renderTemplate configuration template {})]
            (utils/create-compiled-asset asset "text/html" compiled-output @dependencies))
          (catch JadeException e
            (throw (RuntimeException.
                     (format "Jade Compilation exception on line %d: %s"
                             (.getLineNumber e)
                             (or (.getMessage e) (-> e .getClass .getName)))
                     e))))))))

(defn- complete-path
  "Computes the complete path for a partial path, relative to an existing asset.
  Alternately, if the path starts with a leading slash (an absolute path), the the leading path is stripped.

  The result is a complete asset path that can be passed to [[get-asset-uri]]."
  [asset ^String path]
  (if (.startsWith path "/")
    (.substring path 1)
    (utils/compute-relative-path (:asset-path asset) path)))

(defprotocol TwixtHelper
  "A Jade4J helper object that is used to allow a template to resolve asset URIs."
  (uri
    [this path]
    "Used to obtain the URI for a given asset, identified by its asset path.
    The path may be relative to the currently compiling asset, or may be absolute (with a leading slash).

    Throws an exception if the asset it not found.")
  (uris
    [this paths]
    "Used to obtain multiple URIs for any number of assets, each identified by a path.

    paths is a seq of asset path strings (eitehr relative to the current asset, or absolute).

    *Added in 0.1.21*"))

(defn- create-twixt-helper
  [asset context]
  (reify TwixtHelper
    (uri [_ path]
      (twixt/get-asset-uri context (complete-path asset path)))
    (uris [_ paths]
      (->> paths
           (map (partial complete-path asset))
           (mapcat (partial twixt/get-asset-uris context))))))

(defn register-jade
  "Updates the Twixt options with support for compiling Jade into HTML. Pretty printing of the output HTML is enabled
  in development mode, but disabled in production (for efficiency)."
  [options]
  (-> options
      (assoc-in [:content-types "jade"] "text/jade")
      (assoc-in [:content-transformers "text/jade"] jade-compiler)
      (assoc-in [:twixt-template :jade :helpers "twixt"] create-twixt-helper)))