(ns io.aviso.twixt.memory-cache
  "Provides asset pipeline middleware implementing an in-memory cache for assets.
  This cache is used to bypass the normal loading, compiling, and transforming steps."
  (:require [clojure.java.io :as io]
            [io.aviso.twixt.utils :as utils]))

(defn wrap-with-sticky-cache
  "A sticky cache is permanent; it does not check to see if the underlying files have changed.
   This is generally used only in production, where the files are expected to be packaged into JARs, which would
   require an application restart anyway.

   Cached values are permanent; in even the largest web application, the amount of assets is relatively finite,
   so no attempt has been made to evict assets from the cache.

   Assets that are accessed for aggregation are not cached (the aggregated assed will be cached).

   The optional `store-in-cache?` parameter is a function; it is passed an asset, and returns true
   if the asset should be stored in the cache."
  ([asset-handler]
   (wrap-with-sticky-cache asset-handler (constantly true)))
  ([asset-handler store-in-cache?]
   (let [cache (atom {})]
     (fn [asset-path {:keys [for-aggregation] :as context}]
       (if-let [asset (get @cache asset-path)]
         asset
         (when-let [asset (asset-handler asset-path context)]
           (if (and (not for-aggregation)
                    (store-in-cache? asset))
             (swap! cache assoc asset-path asset))
           asset))))))

(defn- modified-at-matches?
  "Returns true if the resource is valid (actual `modified-at` matches the provided value from the asset map).

  Also returns true if the `modified-at` is nil (meaning the file is inside a JAR, not on the filesystem)."
  [resource-path modified-at]
  ;; For resources inside JARs, we may not have a modified time, and that's OK,
  ;; because those don't change.
  (when modified-at
    (= modified-at
       (some-> resource-path io/resource utils/modified-at))))

(defn- is-valid?
  [asset]
  (cond
    (nil? asset) false
    ;; Non-compiled assets may have no :dependencies key, so use the standard keys
    (nil? (:dependencies asset)) (modified-at-matches? (:resource-path asset) (:modified-at asset))
    ;; Compiled assets will have :dependencies that include the original resource.
    ;; Keys are the resource path, values are a map with keys :modified-at and :checksum
    :else (every?
            (fn [[resource-path {:keys [modified-at]}]]
              (modified-at-matches? resource-path modified-at))
            (:dependencies asset))))

(defn wrap-with-invalidating-cache
  "An invalidating cache is generally used in development; it does more work than the sticky cache;
  assets that are obtained from the cache are checked to ensure they are still valid;
  invalid cached assets are discarded and re-fetched from downstream.

  Assets that are obtained for aggregation are _not_ cached; it is assumed that they are
  intermediate results and only the final aggregated asset needs to be cached.

  A cached asset is invalid if any of its dependencies has changed (based on modified-at timestamp)."
  ([asset-handler]
   (wrap-with-invalidating-cache asset-handler (constantly true)))
  ([asset-handler store-in-cache?]
   (let [cache (atom {})]
     (fn [asset-path {:keys [for-aggregation] :as context}]
       (let [asset (get @cache asset-path)]
         (if (is-valid? asset)
           asset
           (do
             (swap! cache dissoc asset-path)
             (when-let [asset (asset-handler asset-path context)]
               (if (and
                     (not for-aggregation)
                     (store-in-cache? asset))
                 (swap! cache assoc asset-path asset))
               asset))))))))