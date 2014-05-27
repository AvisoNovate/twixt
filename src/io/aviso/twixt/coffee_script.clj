(ns io.aviso.twixt.coffee-script
  "Provides asset pipeline middleware to perform CoffeeScript to JavaScript compilation.

  CoffeeScript assets include a source map as an attachment.
  The source map includes the source of the CoffeeScript file.

  The compiled JavaScript includes the directive for the browser to load the source map."
  (:import
    [org.mozilla.javascript ScriptableObject]
    [java.util Map])
  (:require
    [io.aviso.tracker :as t]
    [io.aviso.twixt
     [rhino :as rhino]
     [utils :as utils]]))

(defn- ^String extract-value [^Map object key]
  (str (.get object key)))

(defn- coffee-script-compiler [asset context]
  (let [file-path (:resource-path asset)
        file-name (utils/path->name file-path)]
    (t/timer
      #(format "Compiled `%s' to JavaScript in %.2f ms" file-path %)
      (t/track
        #(format "Compiling `%s' to JavaScript" file-path)
        (let [^Map result
              (rhino/invoke-javascript ["META-INF/twixt/coffee-script.js" "META-INF/twixt/invoke-coffeescript.js"]
                                       "compileCoffeeScriptSource"
                                       (-> asset :content utils/as-string)
                                       file-path
                                       file-name)]

          ;; The script returns an object with key "exception" or key "output":
          (when (.containsKey result "exception")
            (throw (RuntimeException. (extract-value result "exception"))))
          (-> asset
              (utils/create-compiled-asset "text/javascript" (extract-value result "output") nil)
              (utils/add-attachment "source.map" "application/json" (-> result (extract-value "sourceMap") utils/as-bytes))))))))

(defn register-coffee-script
  "Updates the Twixt options with support for compiling CoffeeScript into JavaScript."
  [options]
  (-> options
      (assoc-in [:content-types "coffee"] "text/coffeescript")
      (assoc-in [:content-transformers "text/coffeescript"] coffee-script-compiler)))