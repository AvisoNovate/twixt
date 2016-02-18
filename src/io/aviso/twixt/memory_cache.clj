(ns io.aviso.twixt.memory-cache
  "Provides asset pipeline middleware implementing an in-memory cache for assets.
  This cache is used to bypass the normal loading, compiling, and transforming steps."
  (:require [io.aviso.twixt.schemas :refer [Asset AssetHandler]]
            [io.aviso.toolchest.macros :refer [cond-let]]
            [schema.core :as s])
  (:import [java.util.concurrent.locks ReentrantLock Lock]))

(s/defn wrap-with-transforming-cache :- AssetHandler
  "A cache that operates in terms of a transformation of assets: the canonical example
  is GZip compression, where a certain assets can be compressed to form new assets.

  Assets that are obtained for aggregation are _never_ cached; it is assumed that they are
  intermediate results and only the final aggregated asset needs to be cached.

  This cache assumes that another cache is present that can quickly obtain the
  non-transformed version of the asset.

  asset-handler
  : The downstream asset handler, from which the untransformed asset is obtained.

  store-in-cache?
  : Function that is passed an Asset and determines if it can be transformed and stored in the cache.

  asset-tranformer
  : Function that is passed the untransformed Asset and returns the transformed Asset to be stored
  in the cache."
  {:added "0.1.20"}
  [asset-handler :- AssetHandler
   store-in-cache? :- (s/=> s/Bool Asset)
   asset-transformer :- (s/=> Asset Asset)]
  (let [cache (atom {})]
    (fn [asset-path {:keys [for-aggregation] :as context}]
      (cond-let
        ;; We assume this is cheap because of caching
        [prime-asset (asset-handler asset-path context)]

        for-aggregation
        prime-asset

        (nil? prime-asset)
        nil

        (not (store-in-cache? prime-asset))
        prime-asset

        [{:keys [transformed prime-checksum]} (get cache asset-path)]

        (= prime-checksum (:checksum prime-asset))
        transformed

        :else
        (let [transformed (asset-transformer prime-asset)]
          (swap! cache assoc asset-path {:transformed    transformed
                                         :prime-checksum prime-checksum})
          transformed)))))

(defmacro ^:private with-lock
  [lock & body]
  `(let [^Lock lock# ~lock]
     (try
       (.lock lock#)
       ~@body
       (finally
         (.unlock lock#)))))

(defn- update-cache
  [cache asset-handler asset-path context]
  (let [cached-asset (asset-handler asset-path context)]
    (swap! cache update-in [asset-path]
           assoc :cached-asset cached-asset
           :cached-at (System/currentTimeMillis))

    cached-asset))

(s/defn wrap-with-memory-cache :- AssetHandler
  "Wraps another asset handler in a simple in-memory cache.

  This is useful for both compiled and raw assets, as it does two things.

  On first access to an asset, a cache entry is created. Subsequent accesses
  to the cache entry within the check interval will be served from the cache.

  After the check interval, the delegate asset handler is used to see if the asset has changed,
  replacing it in the cache."
  {:added "0.1.20"}
  [asset-handler :- AssetHandler
   check-interval-ms :- s/Num]
  (let [cache      (atom {})
        cache-lock (ReentrantLock.)]
    (fn [asset-path context]
      (trampoline
        (fn reentry-point []
          (cond-let
            [now (System/currentTimeMillis)
             {:keys [cached-asset cached-at] :as cache-entry} (get @cache asset-path)]

            (and cached-asset
                 (< now (+ cached-at check-interval-ms)))
            cached-asset

            (nil? cache-entry)
            (let [lock (ReentrantLock.)]
              (with-lock cache-lock
                         ;; There can be a race condition where two threads are trying to first
                         ;; access the same asset. This detects that; the first thread will have
                         ;; added a cache entry with just the :lock key, then released the cache
                         ;; lock while obtaining the asset. The second thread will reach this point,
                         ;; see that there's something in the cache, and loop back
                         ;; to fall through the normal path, and the per-asset lock.
                         (if (contains? @cache asset-path)
                           ;; This releases the cache-lock and falls through the normal
                           ;; processing. Depending on the timing, either the asset will just be in the cache
                           ;; and ready to go, or this thread will block while the other thread
                           ;; is obtaining the asset.
                           reentry-point
                           (do
                             ;; Do this now, with the cache-lock locked
                             (swap! cache assoc-in [asset-path :lock] lock)
                             ;; And do this via trampoline, after the cache-lock is unlocked.
                             #(with-lock lock
                                         (update-cache cache asset-handler asset-path context))))))

            :else
            (with-lock (:lock cache-entry)
                       (update-cache cache asset-handler asset-path context))))))))