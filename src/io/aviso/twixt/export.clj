(ns io.aviso.twixt.export
  "Support for exporting assets to the file system.

  Ideally, exporting would be fully asynchronous; however that would require a dedicated thread, and
  that is a problem as there is no lifecycle for
  a Ring request handler to know when the Ring server is itself shutdown."
  {:added "0.1.17"}
  (:require [schema.core :as s]
            [io.aviso.twixt.schemas :refer [AssetPath ExportsConfiguration]]
            [io.aviso.tracker :as t]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [io.aviso.twixt :as twixt]
            [clojure.java.io :as io]
            [clojure.tools.logging :as l]))

(deftype ^:private Token [running?
                          ^Thread thread]

  Object
  (finalize [_]
    (reset! running? false)
    (.interrupt thread)))

(defn- export-asset
  [output-dir {:keys [asset-path content]} output-alias]
  (t/track
    (if (= asset-path output-alias)
      (format "Exporting `%s'." asset-path)
      (format "Exporting `%s' (as `%s')." asset-path output-alias))

    (let [output-file (io/file output-dir output-alias)]
      (when-not (.exists output-file)
        (-> output-file .getParentFile .mkdirs))

      (io/copy content output-file))))

(defn- check-exports
  [context output-dir assets checksums]
  (t/track
    "Checking exported assets for changes."
    (let [context' (assoc context :gzip-enabled false)]
      (doseq [[asset-path output-alias] assets]
        (try
          (t/track
            (format "Checking `%s' for changes." asset-path)
            (cond-let
              [asset (twixt/find-asset asset-path context')]

              (nil? asset)
              nil

              [asset-checksum (:checksum asset)]

              (= asset-checksum (get @checksums asset-path))
              nil

              :else
              (do
                (export-asset output-dir asset output-alias)
                (swap! checksums assoc asset-path asset-checksum)))
            (catch Throwable _
              ;; Reported by the tracker and ignored.
              )))))))


(defn- start-exporter-thread
  [context {:keys [interval-ms output-dir assets]}]
  (let [checksums     (atom {})
        ;; We want the assets to all be the same shape: asset path and output path.
        assets'       (map #(if (string? %)
                             [% %]
                             %)
                           assets)
        running?      (atom true)
        first-pass    (promise)
        export-body   (fn []
                        (l/info "Twixt asset export thread started.")
                        (while @running?
                          (try
                            (check-exports context output-dir assets' checksums)
                            (deliver first-pass true)
                            (Thread/sleep interval-ms)

                            (catch Throwable _))
                          ;; Real errors are reported inside check-exports, and InterruptedException is ignored.
                          )
                        (l/info "Shutting down Twixt asset export thread."))
        export-thread (doto (Thread. ^Runnable export-body)
                        (.setName "Twixt Export")
                        (.setDaemon true)
                        .start)]
    ;; Wait for completion of first export pass:

    @first-pass

    ;; The token is returned to the call, which keeps it in an atom. At shutdown time,
    ;; the entire Ring request pipeline will be GC'ed, at which point the token will become
    ;; weakly referenced.
    (Token. running? export-thread)))


(s/defn wrap-with-exporter
  "Wraps a Ring handler so that, periodically, assets identified in the configuration
  are checked for changes and copied out to the file system (as needed).

  The first checks and exports occur before delegating to the wrapped handler; this will allow
  a standard resource handler (say, ring.middleware.resource/wrap-resource) to operate.

  Subsequent checks happen at intervals on a secondary thread.
  The thread will shutdown once the Ring request pipeline is GC'ed.
  It requires an explicit call to System/gc to ensure that the pipeline is GC'ed
  (and the necessary object finalizer called).

  Note that asset exporting is largely intended for development purposes."
  [ring-handler
   {:keys [assets] :as configuration} :- ExportsConfiguration]
  (if (empty? assets)
    ring-handler
    (let [token (atom nil)]
      (fn [request]
        (when (nil? @token)
          (reset! token (start-exporter-thread (:twixt request) configuration)))

        (ring-handler request)))))
