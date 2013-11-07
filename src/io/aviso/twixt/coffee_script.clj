(ns io.aviso.twixt.coffee-script
  "Twixt transformer for compiling CoffeeScript to JavaScript."
  (:use io.aviso.twixt.streamable)
  (:import [org.mozilla.javascript ScriptableObject])
  (:require [io.aviso.twixt
             [rhino :as rhino]
             [tracker :as tracker]
             [utils :as utils]]))

(defn- extract-value [^ScriptableObject object key]
  (str (.get object key)))

(defn- converter [streamable new-content]
  (replace-content streamable (str "Compiled " (source-name streamable)) "text/javascript" new-content))

(defn- coffee-script-compiler [asset]
  (let [name (:resource-path asset)]
    (tracker/log-time
      #(format "Compiled `%s' to JavaScript in %.2f ms" name %)
      (tracker/trace
        #(format "Compiling `%s' to JavaScript" name)
        (let [^ScriptableObject result
              (rhino/invoke-javascript ["META-INF/twixt/coffee-script-1.6.3.js" "META-INF/twixt/invoke-coffeescript.js"]
                                       "compileCoffeeScriptSource"
                                       (-> asset :content utils/as-string)
                                       name)]

          ;; The script returns an object with key "exception" or key "output":
          (when (.containsKey result "exception")
            (throw (RuntimeException. (extract-value result "exception"))))

          (let [output (extract-value result "output")
                compiled (.getBytes output "utf-8")]
            (assoc asset
              ;; Indicate to upper levels that the raw content has been transformed in some way worth caching.
              :compiled true
              :content-type "text/javascript"
              :dependencies {name (select-keys asset [:checksum :modified-at])}
              :content compiled
              :size (alength compiled)
              :checksum (compute-checksum compiled))))))))

(defn wrap-with-coffee-script-compilation
  "Wraps an asset pipeline handler such that CoffeeScript source is compiled."
  [handler]
  (fn coffee-script-compilation [path]
    (let [asset (handler path)]
      (if (= "text/coffeescript" (:content-type asset))
        (coffee-script-compiler asset)
        asset))))