(ns io.aviso.twixt.coffee-script
  "Provides asset pipeline middleware to perform CoffeeScript to JavaScript compilation."
  (:import [org.mozilla.javascript ScriptableObject]
           [java.util Map])
  (:require [io.aviso.twixt
             [rhino :as rhino]
             [tracker :as tracker]
             [utils :as utils]]))

(defn- ^String extract-value [^Map object key]
  (str (.get object key)))

(defn- coffee-script-compiler [asset context]
  (let [name (:resource-path asset)]
    (tracker/log-time
      #(format "Compiled `%s' to JavaScript in %.2f ms" name %)
      (tracker/trace
        #(format "Compiling `%s' to JavaScript" name)
        (let [^Map result
              (rhino/invoke-javascript ["META-INF/twixt/coffee-script-1.6.3.js" "META-INF/twixt/invoke-coffeescript.js"]
                                       "compileCoffeeScriptSource"
                                       (-> asset :content utils/as-string)
                                       name)]

          ;; The script returns an object with key "exception" or key "output":
          (when (.containsKey result "exception")
            (throw (RuntimeException. (extract-value result "exception"))))

          (utils/create-compiled-asset asset "text/javascript" (extract-value result "output")))))))

(defn wrap-with-coffee-script-compilation
  "Wraps an asset pipeline handler such that CoffeeScript source is compiled."
  [handler]
  (utils/content-type-matcher handler "text/coffeescript" coffee-script-compiler))