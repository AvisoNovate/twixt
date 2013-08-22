(ns io.aviso.twixt.fs-cache
  "Manages a simple file system cache for Streamables."
  (:use io.aviso.twixt.streamable)
  (:import [java.io File InputStream])
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.tools.logging :as l]
            [io.aviso.twixt.tracker :as t]))

(defn- munge-name
  [streamable]
  (let [^String name (source-name streamable)
        dotx (.lastIndexOf name ".")]
    (str (s/replace (.substring name 0 dotx) #"[^\p{Alnum}\-\._]+" "_")
         \-
         (checksum streamable)
         (.substring name dotx))))

(defn ^File find-cache-file
  [cache-dir streamable]
  "Checks the file system cache for previously generated content for the streamable. Returns the File
  for the cached content, which may or may not exist."
  (io/file cache-dir (munge-name streamable)))

(defn- write-to-cache-file 
  [streamable file]
  (t/trace #(format "Writing `%s' content to `%s'" (source-name streamable) file)
           (with-open [content-stream (open streamable)
                       output-stream (io/output-stream file)]
             (io/copy content-stream output-stream))))

(defn- create-cache-dir [folder]
  (let [file (io/file folder)]
    (if (.exists file)
      (l/infof "Using cache folder `%s'." file)
      (do
        (.mkdirs file)
        (l/infof "Created cache folder `%s'." file)))))

(defn wrap-with-cache 
  "Takes a cache directory and a delegate function. The delegate accepts a Streamable and returns a Streamable.
  A file system cache is used for storing the results of 
  
  cache-dir - File, String, or otherwise compatible with clojure.java.io/as-file. The cache-dir is created.
  subdir - optional sub-directory beneath the cache dir
  creator - Passed original (untransformed) Streamable and a source (e.g., the file) and provides a new Streamble.
  delegate - function whose result is being cached; invoked if the cache file is missing."
  ([cache-dir subdir creator delegate]
   (wrap-with-cache (io/file cache-dir subdir) creator delegate))
  ([cache-dir creator delegate]   
   (create-cache-dir cache-dir)
   (fn check-cache [streamable]
     (t/trace #(format "Checking file system cache for `%s'." (source-name streamable))
              (let [cache-file (find-cache-file cache-dir streamable)]
                (if (.exists cache-file)
                  (creator streamable cache-file)
                  (let [transformed (delegate streamable)]
                    ;; This will be used in a later execution
                    (write-to-cache-file transformed cache-file)
                    transformed)))))))

(defn optionally-wrap-with-cache
  "Returns the delegate unchanged, or wrapped via wrap-with-cache, as per standard Twixt options."
  [options subdir creator delegate]
  (if (or (:development-mode options) (:cache-enabled options))
    (wrap-with-cache (:cache-folder options) subdir creator delegate)
    delegate))