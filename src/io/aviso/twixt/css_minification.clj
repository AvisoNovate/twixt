(ns io.aviso.twixt.css-minification
  "CSS Miniification based on YUICompressor."
  {:added "0.1.20"}
  (:require [schema.core :as s]
            [io.aviso.twixt.schemas :refer [AssetHandler]]
            [io.aviso.tracker :as t]
            [clojure.java.io :as io]
            [io.aviso.twixt.utils :as utils])
  (:import [java.io StringWriter]
           [com.yahoo.platform.yui.compressor CssCompressor]))


(defn- run-minimizer
  [{:keys [resource-path content] :as asset}]
  (t/timer
    #(format "Minimized `%s' (%,d bytes) in %.2f ms"
             resource-path (:size asset) %)
    (t/track
      #(format "Minimizing `%s' using YUICompressor." resource-path)
      (let [compressed (with-open [writer (StringWriter. 1000)
                                   reader (io/reader content)]
                         (doto (CssCompressor. reader)
                           (.compress writer -1))

                         (.flush writer)
                         (-> writer .getBuffer str))]
        (utils/create-compiled-asset asset "text/css" compressed nil)))))

(s/defn wrap-with-css-minification :- AssetHandler
  "Enabled CSS minification based on the :compress-css key of the Twixt options.
  If the key is not present, the default is to enable compression only in production mode.

  When not enabled, returns the provided handler unchanged."
  [handler :- AssetHandler {:keys [development-mode] :as twixt-options}]
  (if (get twixt-options :minimize-css (not development-mode))
    (fn [asset-path {:keys [for-aggregation] :as context}]
      (let [{:keys [content-type] :as asset} (handler asset-path context)]
        (if (and (= "text/css" content-type)
                 (not for-aggregation))
          (run-minimizer asset)
          asset)))
    handler))