(ns io.aviso.twixt.export
  "Support for exporting assets to the file system.

  Exports are 'hooked' into Ring request processing, so the checks only occur when requests are incoming.

  Ideally, exporting would be fully asynchronous; however that would require a dedicated thread, and
  that is a problem as there is no lifecycle for
  a Ring request handler to know when the Ring server is itself shutdown."
  {:added "0.1.17"}
  (:require [schema.core :as s]
            [io.aviso.twixt.schemas :refer [AssetPath ExportsConfiguration]]
            [io.aviso.tracker :as t]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [io.aviso.twixt :as twixt]
            [clojure.java.io :as io]))

(defn- export-asset
  [output-dir {:keys [asset-path content]}]
  (t/track
    (format "Exporting `%s'." asset-path)

    (let [output-file (io/file output-dir asset-path)]
      (when-not (.exists output-file)
        (-> output-file .getParentFile .mkdirs))

      (io/copy content output-file))))

(defn- check-exports
  [{context :twixt} {:keys [output-dir assets]} time-modified]

  (t/track
    "Checking exported assets for changes."
    (doseq [asset-path assets]
      (t/track
        (format "Checking `%s' for changes." asset-path)
        (cond-let
          [asset (twixt/find-asset asset-path context)]

          (nil? asset)
          nil

          [asset-modified-at (:modified-at asset)]

          (= asset-modified-at (get @time-modified asset-path))
          nil

          :else
          (do
            (export-asset output-dir asset)
            (swap! time-modified assoc asset-path asset-modified-at)))))))


(s/defn wrap-with-exporter
  "Wraps a Ring handler so that, periodically, assets identified in the configuration
  are checked for changes and copied out to the file system (as needed).

  Checks and exports occur before delegating to the wrapped handler; this will allow
  a standard resource handler (say, ring.middleware.resource/wrap-resource) to operate."
  [ring-handler
   {:keys [interval-ms assets] :as configuration} :- ExportsConfiguration]
  (if (empty? assets)
    ring-handler
    (let [time-modified (atom {})
          next-check    (atom 0)]
      (fn [request]
        (let [now (System/currentTimeMillis)]
          (when (< @next-check now)
            (check-exports request configuration time-modified)
            (reset! next-check (+ now interval-ms)))

          ;; Continue on to whatever the request was originally for.
          (ring-handler request))))))
