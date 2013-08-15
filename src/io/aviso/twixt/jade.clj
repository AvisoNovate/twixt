(ns io.aviso.twixt.jade
  "Support for using Jade templates (using jade4j)."
  (:use io.aviso.twixt.streamable)
  (:import [java.io Reader InputStreamReader BufferedReader]
           [de.neuland.jade4j Jade4J]
           [de.neuland.jade4j.parser Parser]
           [de.neuland.jade4j.parser.node Node]
           [de.neuland.jade4j.exceptions JadeException]
           [de.neuland.jade4j.template JadeTemplate ReaderTemplateLoader TemplateLoader])
  (:require [io.aviso.twixt 
             [tracker :as tracker]
             [fs-cache :as fs]]))


(defn- ^TemplateLoader create-template-loader 
  [source ^String name]
  (let [^Reader reader (->
                         (open source)
                         InputStreamReader.
                         BufferedReader.)]
    (ReaderTemplateLoader. reader name)))

(defn- converter [streamable new-content]
  (replace-content streamable (str "Compiled " (source-name streamable)) "text/html" new-content))

(defn- jade-compiler [source]
    (let [name (source-name source)]
      (tracker/log-time
        #(format "Compiled `%s' to HTML in %.2f ms" name %)
        (tracker/trace
          #(format "Compiling `%s' from Jade to HTML" name)
          (try
            (let [loader (create-template-loader source name)
                  parser (Parser. name loader)
                  rootNode (.parse parser)
                  ^JadeTemplate template (doto (JadeTemplate.)
                                           (.setRootNode rootNode)
                                           (.setTemplateLoader loader))
                  ;; Need a way in the future to turn pretty to false for production
                  ^String compiled (Jade4J/render template {} true)]
              (converter source (as-bytes compiled)))
            (catch JadeException e
              (throw (RuntimeException.
                       (format "Jade Compilation exception on line %d: %s"
                               (.getLineNumber e)
                               (or (.getMessage e) (-> e .getClass .getName)))
                       e))))))))

(defn jade-compiler-factory
  [options]
  (fs/optionally-wrap-with-cache options "jade" converter jade-compiler))
