(ns io.aviso.twixt.jade
  "Support for using Jade templates (using jade4j)."
  (:use io.aviso.twixt.streamable)
  (:import [java.io Reader InputStreamReader BufferedReader]
           [de.neuland.jade4j Jade4J]
           [de.neuland.jade4j.exceptions JadeException])
  (:require [io.aviso.twixt
             [tracker :as tracker]
             [fs-cache :as fs]]
            [clojure.java.io :as io]))

(defn- converter [streamable new-content]
  (replace-content streamable (str "Compiled " (source-name streamable)) "text/html" new-content))

(defn- jade-compiler [pretty-print source]
  (let [name (source-name source)]
    (tracker/log-time
      #(format "Compiled `%s' to HTML in %.2f ms" name %)
      (tracker/trace
        #(format "Compiling `%s' from Jade to HTML." name)
        (try
          (with-open [reader (-> source open io/reader)]
            (let [compiled (Jade4J/render reader name {} pretty-print)]
              (converter source (as-bytes compiled))))
          (catch JadeException e
            (throw (RuntimeException.
                     (format "Jade Compilation exception on line %d: %s"
                             (.getLineNumber e)
                             (or (.getMessage e) (-> e .getClass .getName)))
                     e))))))))

(defn jade-compiler-factory
  [options]
  (let [pretty-print (:development-mode options)]
    (fs/optionally-wrap-with-cache options "jade"
                                   converter
                                   (partial jade-compiler pretty-print))))
