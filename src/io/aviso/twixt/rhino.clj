(ns io.aviso.twixt.rhino
  "Code to execute Rhino for purposes such as compiling CoffeeScript to JavaScript."
  ;; Liberally borrowing from Dieter!
  (:import
    [org.mozilla.javascript Context NativeObject ScriptableObject Scriptable Function])
  (:require
    [clojure.java.io :as io]
    [io.aviso.tracker :as t]))


(defn- load-script [^Context context scope file]
  (with-open [content-reader (-> file io/resource io/reader)]
    (t/track
      #(str "Loading JavaScript from " file)
      (.evaluateReader context scope content-reader file 1 nil))))

(defn- invoke-function [^Context context ^Scriptable scope ^String function-name arguments]
  (let [^Function js-fn (.get scope function-name scope)]
    (.call js-fn context scope nil (into-array arguments))))

(defn invoke-javascript
  "Invokes a JavaScript function, returning the result.

  script-paths
  : JavaScript files to load, as seq of classpath resource paths

  javascript-fn-name
  : name of JavaScript function to execute

  arguments
  : additional arguments to pass to the function

  Returns the JavaScript result; typically this will be a JavaScript object, and the properties
  of it can be accessed via the methods of `java.util.Map`."
  [script-paths javascript-fn-name & arguments]
  (let [context (Context/enter)]
    (try
      ;; Apparently, CoffeeScript can blow away a 64K limit pretty easily.
      (.setOptimizationLevel context -1)

      (let [scope (.initStandardObjects context)]

        ;; Dieter maintains a pool of configured Context instances so that we don't
        ;; have to re-load the scripts on each compilation. That would be nice.
        (doseq [file script-paths]
          (load-script context scope file))

        (invoke-function context scope javascript-fn-name arguments))

      (finally (Context/exit)))))
