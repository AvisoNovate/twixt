(ns io.aviso.twixt.jade
  "Provides asset pipeline middleware for compiling Jade templates to HTML using jade4j."
  (:import [de.neuland.jade4j JadeConfiguration]
           [de.neuland.jade4j.exceptions JadeException]
           [de.neuland.jade4j.template TemplateLoader])
  (:require [clojure.java.io :as io]
            [io.aviso.tracker :as t]
            [io.aviso.twixt.utils :as utils]))

(defn- create-template-loader [root-asset asset-resolver]
  (reify TemplateLoader
    ;; getLastModified is only needed for caching, and we disable Jade's caching
    ;; in favor of Twixt's.
    (getLastModified [this name] -1)
    (getReader [this name]
      (if (= name (:asset-path root-asset))
        (-> root-asset :content io/reader)
        (t/track
          #(format "Including Jade source from asset `%s'." name)
          (-> name
              ;; The asset resolver does nothing with the options passed to it
              (asset-resolver nil)
              (utils/nil-check "Included asset does not exist.")
              :content
              ;; We have to trust that Jade will close the reader.
              io/reader))))))

(defn- create-configuration
  [pretty-print asset asset-resolver]
  (doto (JadeConfiguration.)
    (.setPrettyPrint pretty-print)
    (.setCaching false)
    (.setTemplateLoader (create-template-loader asset asset-resolver))))

(defn- jade-compiler [pretty-print asset {:keys [asset-resolver] :as context}]
  (let [name (:resource-path asset)]
    (t/timer
      #(format "Compiled `%s' to HTML in %.2f ms" name %)
      (t/track
        #(format "Compiling `%s' from Jade to HTML" name)
        (try
          (let [configuration (create-configuration pretty-print asset asset-resolver)
                template (.getTemplate configuration (:asset-path asset))
                compiled-output (.renderTemplate configuration template {})]
            (utils/create-compiled-asset asset "text/html" compiled-output))
          (catch JadeException e
            (throw (RuntimeException.
                     (format "Jade Compilation exception on line %d: %s"
                             (.getLineNumber e)
                             (or (.getMessage e) (-> e .getClass .getName)))
                     e))))))))

(defn register-jade
  "Updates the Twixt options with support for compiling Jade into HTML."
  [options pretty-print]
  (-> options
      (assoc-in [:content-types "jade"] "text/jade")
      (assoc-in [:content-transformers "text/jade"] (partial jade-compiler pretty-print))))