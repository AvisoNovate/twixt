(ns io.aviso.twixt.dependency
  "DependencyChangeTracker protocol."
  (:import [java.net URL URISyntaxException]
           [java.io File])
  (:require [clojure.tools.logging :as l]))

(defprotocol DependencyChangeTracker
  
  ;; Would be nice to add ability to one tracker to track another; when a tracker goes "dirty" it can notify
  ;; its dependents that they are now dirty as well.
  
  (^DependencyChangeTracker track! 
                            [this ^URL url] 
                            "Adds the URL (if a file: protocol) as a tracked dependency, returning
                            the same DependencyChangeTracker (its internal state changes).")
  
  (^boolean dirty? 
            [this] 
            "Checks the dependencies, if any, to determine if any have changed, returning true when such a change is found."))


;; Not the same as io/file!
(defn- ^File as-file [^URL url]
  (try
    (-> url .toURI File.)
    (catch URISyntaxException e
      (-> url .getPath File.))))

;; Passed a map entry from the DCT's resources map.
(defn- last-modified-changed?
  [[url {:keys [^File file ^long last-modified]}]]
  (l/tracef "Checking `%s' for DTM change" file)
  (when (not (= last-modified (.lastModified file)))
    (l/tracef "DTM changed from %tc +%<tL to %tc +%<tL" last-modified (.lastModified file))
    true))

(defn create-placeholder-tracker
  "Creates a placeholder implementation of dependency tracker, used in production mode when dependency tracking is not desired."
  []
  (reify DependencyChangeTracker
    (track! [this url] this)
    (dirty? [this] false)))

(defn create-dependency-tracker
  "Creates a new dependency tracker."
  []
  ;; resources is keyed on url of the resource; values are a map with keys :file and :last-modified
  (let [resources (atom {})
        dirty (atom false)]
    (reify DependencyChangeTracker
      (track! 
        [this url]
        (l/tracef "Adding `%s' as tracked dependency.", url)
        ;; Only track URLs that map to files; in production, all the assets will be inside a JAR and will not need to be
        ;; tracked; they can only change as part of a full redeploy.
        (if (->> url .getProtocol (= "file"))
          (let [file (as-file url)
                last-modified (.lastModified file)]
            (l/tracef "DTM for `%s' is %tc +%<tL", (.getName file) last-modified)
            (swap! resources assoc url {:file file :last-modified last-modified})))
        this)
      (dirty? 
        [this]
        ;; Once we see that it is dirty, we don't do further checks. This will be even more important in the future,
        ;; where DCT's may be linked together.
        (if-not @dirty
          (reset! dirty (some last-modified-changed? @resources)))
        @dirty))))
