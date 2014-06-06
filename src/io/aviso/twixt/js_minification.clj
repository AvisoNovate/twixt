(ns io.aviso.twixt.js-minification
  "Provides support for JavaScript minification using the Google Closure compiler."
  (:import (com.google.javascript.jscomp CompilerOptions ClosureCodingConvention DiagnosticGroups CheckLevel
                                         SourceFile Result CompilationLevel))
  (:require [clojure.java.io :as io]
            [io.aviso.twixt.utils :as utils]
            [io.aviso.tracker :as t]
            [clojure.string :as str]))

;; See bug http://dev.clojure.org/jira/browse/CLJ-1440
;; this is an ugly workaround.

(def ClosureCompiler com.google.javascript.jscomp.Compiler)

(defn minimize-javascript-asset
  [{file-path :resource-path :as asset}]
  (t/timer
    #(format "Minimized `%s' (%,d bytes) in %.2f ms"
             file-path (:size asset) %)
    (t/track
      #(format "Minimizing `%s' using Google Closure" file-path)
      (let [options (doto (CompilerOptions.)
                      (.setCodingConvention (ClosureCodingConvention.))
                      (.setOutputCharset "utf-8")
                      (.setWarningLevel DiagnosticGroups/CHECK_VARIABLES CheckLevel/WARNING))
            _ (.setOptionsForCompilationLevel CompilationLevel/SIMPLE_OPTIMIZATIONS options)
            compiler ^{:type ClosureCompiler} (doto (.newInstance ClosureCompiler) .disableThreads)
            input (SourceFile/fromInputStream file-path
                                              (-> asset :content io/input-stream))
            result ^Result (.compile compiler [] [input] options)]

        (if (.success result)
          (utils/create-compiled-asset asset "text/javascript" (.toSource compiler) nil)
          (throw (ex-info (str "JavaScript minimization failed: "
                               (str/join "; " (-> result .errors seq)))
                          options)))))))

(defn wrap-with-javascript-minimizations
  "Identifies JavaScript assets and, if not aggregating, passes them through the Google Closure compiler."
  [asset-handler]
  (fn [asset-path {:keys [for-aggregation] :as options}]
    (let [{:keys [content-type] :as asset} (asset-handler asset-path options)]
      (if (and (= "text/javascript" content-type)
               (not for-aggregation))
        (minimize-javascript-asset asset)
        asset))))