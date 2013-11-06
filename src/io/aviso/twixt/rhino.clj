(ns io.aviso.twixt.rhino
  "Code to execute Rhino for purposes such as compiling CoffeeScript to JavaScript."
  ;; Liberally borrowing from Dieter!
  (:import [org.mozilla.javascript Context NativeObject])
  (:require [clojure.java.io :as io]
            [io.aviso.twixt.tracker :as tracker]))


(defn- load-script [context scope file]
  (with-open [content-reader (-> file io/resource io/reader)]
    (tracker/trace
      (str "Loading JavaScript from " file)
      (.evaluateReader context scope content-reader file 1 nil))))

(defn- invoke-function [context scope function-name arguments]
  (let [js-fn (.get scope function-name scope)]
    (.call js-fn context scope nil (into-array arguments))))

(defn invoke-javascript
  [script-urls javascript-fn-name & arguments]
  (let [context (Context/enter)]
    (try
      ;; Apparently, CoffeeScript can blow away a 64K limit pretty easily.
      (.setOptimizationLevel context -1)

      (let [scope (.initStandardObjects context)]

        ;; Dieter maintains a pool of configured Context instances so that we don't
        ;; have to re-load the scripts on each compilation. That would be nice.
        (doseq [file script-urls]
          (load-script context scope file))

        (invoke-function context scope javascript-fn-name arguments))

      (finally (Context/exit)))))
