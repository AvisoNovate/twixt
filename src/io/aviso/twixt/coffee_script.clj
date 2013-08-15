(ns io.aviso.twixt.coffee-script
  "Twixt transformer for compiling CoffeeScript to JavaScript."
  (:use io.aviso.twixt.streamable)
  (:import [org.mozilla.javascript ScriptableObject])
  (:require [io.aviso.twixt
             [fs-cache :as fs]
             [rhino :as rhino]
             [tracker :as tracker]]))

(defn- extract-value [^ScriptableObject object key]
  (str (.get object key)))

(defn- converter [streamable new-content]
  (replace-content streamable (str "Compiled " (source-name streamable)) "text/javascript" new-content))


(defn- coffee-script-compiler [source]
                                   (let [name (source-name source)]
                                     (tracker/log-time
                                       #(format "Compiled `%s' to JavaScript in %.2f ms" name %)
                                       (tracker/trace
                                         #(format "Compiling `%s' to JavaScript" name)
                                         (let [^ScriptableObject result
                                               (rhino/invoke-javascript ["META-INF/twixt/coffee-script-1.6.3.js" "META-INF/twixt/invoke-coffeescript.js"]
                                                                        "compileCoffeeScriptSource"
                                                                        (as-string source utf-8)
                                                                        name)]
                                           
                                           ;; The script returns an object with key "exception" or key "output":
                                           (when (.containsKey result "exception")
                                             (throw (RuntimeException. (extract-value result "exception"))))
                                           
                                           (converter source (as-bytes (extract-value result "output"))))))))
(defn coffee-script-compiler-factory
  [options]
  (fs/optionally-wrap-with-cache options "coffee-script" converter coffee-script-compiler))