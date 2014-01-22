(ns io.aviso.twixt.utils
  "Some re-usable utilities. This namespace should be considered unsupported (subject to change at any time)."
  (:import [java.io CharArrayWriter ByteArrayOutputStream File]
           [java.nio.charset Charset]
           [java.util.zip Adler32]
           [java.net URISyntaxException URL URI]
           [java.util Date])
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn as-string
  "Converts a source (compatible with clojure.java.io/IOFactory) into a String using the provided encoding.

  The default charset is UTF-8."
  ([source]
   (as-string source "UTF-8"))
  ([source charset]
   (with-open [reader (io/reader source :encoding charset)
               writer (CharArrayWriter. 1000)]
     (io/copy reader writer)
     (.toString writer))))

(defn as-bytes [^String string]
  "Converts a string to a byte array. The string should be UTF-8 encoded."
  (.getBytes string "UTF-8"))

(defn compute-checksum
  "Returns a hex string of the Adler32 checksum of the content."
  [^bytes content]
  (->
    (doto (Adler32.)
      (.update content))
    .getValue
    Long/toHexString))

(defn replace-asset-content
  "Modifies an asset map with new content."
  [asset content-type ^bytes content-bytes]
  (assoc asset
    :content-type content-type
    :content content-bytes
    :size (alength content-bytes)
    :checksum (compute-checksum content-bytes)))

(defn create-compiled-asset
  "Used to transform an asset map after it has been compiled from one form to another."
  [source-asset content-type ^String content]
  (->
    source-asset
    (replace-asset-content content-type (as-bytes content))
    (assoc
        :compiled true
                  :dependencies {(:resource-path source-asset)
                                  (select-keys source-asset [:checksum :modified-at :asset-path])})))

(defn content-type-matcher
  "Creates a handler for the asset pipeline that delegates to the provided handler, but passes
  the obtained asset through a transformer function if it matches the expected content type."
  [handler content-type transformer]
  (fn [asset-path context]
    (if-let [asset (handler asset-path context)]
      (if (= content-type (:content-type asset))
        (transformer asset context)
        asset))))

(defn read-content
  "Reads the content of a provided source (compatible with clojure.java.io/input-stream) as a byte array."
  [source]
  (with-open [bos (ByteArrayOutputStream.)
              in (io/input-stream source)]
    (io/copy in bos)
    (.toByteArray bos)))

(defn compute-relative-path
  [^String start ^String relative]
  (loop [path-terms (-> (.split start "/") seq vec drop-last) ; Drop the "file" part of the path, leaving the "folders" list
         terms (.split relative "/")]
    (if (empty? terms)
      (->> path-terms reverse (str/join "/"))
      (let [term (first terms)
            remaining (rest terms)]
        (cond
          (or (= term ".") (= term "")) (recur path-terms remaining)
          (= term "..") (if (empty? path-terms)
                          ;; You could rewrite this with reduce, but then generating this exception would be more difficult:
                          (throw (IllegalArgumentException. (format "Relative path `%s' for `%s' would go above root." relative start)))
                          (recur (drop-last path-terms) remaining))
          :else (recur (conj path-terms term) remaining))))))

(defn- url-to-file
  [^URL url]
  (-> url .toURI File.))

(defn- jar-to-file
  "For a URL that points to a file inside a jar, this returns the JAR file itself."
  [^URL url]
  (-> url
      .getPath
      (str/split #"!")
      first
      URI.
      File.))

;; Not the same as io/file!
(defn- ^File as-file
  "Locates a file from which a last modified date can be extracted."
  [^URL url]
  (cond
    (= "jar" (.getProtocol url)) (jar-to-file url)
    :else (url-to-file url)))

(defn modified-at
  [url]
  (some-> url as-file .lastModified Date.))
