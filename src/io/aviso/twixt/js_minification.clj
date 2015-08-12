(ns io.aviso.twixt.js-minification
  "Provides support for JavaScript minification using the Google Closure compiler."
  (:import (com.google.javascript.jscomp CompilerOptions ClosureCodingConvention DiagnosticGroups CheckLevel
                                         SourceFile Result CompilationLevel))
  (:require [clojure.java.io :as io]
            [io.aviso.twixt.utils :as utils]
            [io.aviso.twixt.schemas :refer [AssetHandler]]
            [io.aviso.tracker :as t]
            [clojure.string :as str]
            [schema.core :as s]))

;; See bug http://dev.clojure.org/jira/browse/CLJ-1440
;; this is an ugly workaround.

(def ^:private ClosureCompiler com.google.javascript.jscomp.Compiler)

(defn- minimize-javascript-asset
  [{file-path :resource-path :as asset} optimizations]
  (t/timer
    #(format "Minimized `%s' (%,d bytes) in %.2f ms"
             file-path (:size asset) %)
    (t/track
      #(format "Minimizing `%s' using Google Closure with compilation level %s" file-path optimizations)
      (let [options (doto (CompilerOptions.)
                      (.setCodingConvention (ClosureCodingConvention.))
                      (.setOutputCharset "utf-8")
                      (.setWarningLevel DiagnosticGroups/CHECK_VARIABLES CheckLevel/WARNING))
            _ (.setOptionsForCompilationLevel optimizations options)
            compiler (doto (.newInstance ClosureCompiler) .disableThreads)
            input (SourceFile/fromInputStream file-path
                                              (-> asset :content io/input-stream))
            result ^Result (.compile compiler [] [input] options)]

        (if (.success result)
          (utils/create-compiled-asset asset "text/javascript" (.toSource compiler) nil)
          (throw (ex-info (str "JavaScript minimization failed: "
                               (str/join "; " (-> result .errors seq)))
                          options)))))))

(def optimization-levels {:simple CompilationLevel/SIMPLE_OPTIMIZATIONS
                          :whitespace CompilationLevel/WHITESPACE_ONLY
                          :advanced CompilationLevel/ADVANCED_OPTIMIZATIONS})

(s/defn wrap-with-javascript-minimizations :- AssetHandler
  "Identifies JavaScript assets and, if not aggregating, passes them through the Google Closure compiler."
  [asset-handler :- AssetHandler
   {:keys [development-mode js-optimizations]}]
  (let [js-optimizations' (if (= :default js-optimizations)
                           (if development-mode :none :simple)
                           js-optimizations)
        level (optimization-levels js-optimizations')]
    (if (nil? level)
      asset-handler
      (fn [asset-path {:keys [for-aggregation] :as context}]
        (let [{:keys [content-type] :as asset} (asset-handler asset-path context)]
          (if (and (= "text/javascript" content-type)
                   (not for-aggregation))
            (minimize-javascript-asset asset level)
            asset))))))