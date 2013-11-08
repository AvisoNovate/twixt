(ns io.aviso.twixt.memory-cache
  "Provides an in-memory cache for assets that can be used to bypass the normal loading, compiling, and transforming
  steps."
  (:require [clojure.java.io :as io]
            #_ [clojure.tools.logging :as l]
            [io.aviso.twixt.utils :as utils]))

(defn wrap-with-sticky-cache
  "A sticky cache is permanent; it does not check to see if the underlying files have changed. This is generally
   used only in production, where the files are expected to be packaged into JARs, which would
   require an application restart anyway.

  Cached values are permanent; in even the largest web application, the amount of assets is relatively finite,
  so no attempt has been made to evict assets from the cache. "
  [handler]
  (let [cache (atom {})]
    (fn sticky-cache [asset-path]
      (if-let [asset (get @cache asset-path)]
        asset
        (when-let [asset (handler asset-path)]
          (swap! cache assoc asset-path asset)
          asset)))))

(defn- modified-at-matches?
  "Returns true if the resource is valid (actual modified-at matches the provided value from the asset map).

  Also returns true if the modified-at is nil (meaning the file is inside a JAR, not on the filesystem)."
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

  A cached asset is invalid if any of its dependencies has changed (based on modified-at timestamp)."
  [handler]
  (let [cache (atom {})]
    (fn invalidating-cache [asset-path]
      (let [asset (get @cache asset-path)]
        (if (is-valid? asset)
          asset
          (do
            (swap! cache dissoc asset-path)
            (when-let [asset (handler asset-path)]
              (swap! cache assoc asset-path asset)
              asset)))))))