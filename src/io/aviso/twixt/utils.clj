(ns io.aviso.twixt.utils
  "Some re-usable utilities. This namespace should be considered unsupported (subject to change at any time)."
  (:import [java.io CharArrayWriter ByteArrayOutputStream File]
           [java.nio.charset Charset]
           [java.util.zip Adler32]
           (java.net URISyntaxException)
           (java.util Date))
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]))

(defn transform-values
  "Transforms a map by passing each value through a provided function."
  [m f]
  (into {} (map (fn [[k v]] [k (f v)]) m)))

(declare merge-maps-recursively)

(defn- merge-values [l r]
  (cond
    ;; We know how to merge two maps together:
    (and (map? l) (map? r)) (merge-maps-recursively l r)
    ;; and how to merge two seqs  together
    (and (seq? l) (seq? r)) (concat l r)
    ;; In any other case the right (later) value replaces the left (earlier) value
    :else r))

(defn merge-maps-recursively
  "Merges any number of maps together, recursively. When merging values:
  - two maps are merged, recursively
  - two seqs are concatinated
  - otherwise, the 'right' value overwrites the 'left' value"
  [& maps]
  (apply merge-with merge-values maps))

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

(defn compute-checksum
  "Returns a hex string of the Adler32 checksum of the content."
  [^bytes content]
  (->
    (doto (Adler32.)
      (.update content))
    .getValue
    Long/toHexString))

(defn create-compiled-asset
  "Used to transform an asset map after it has been compiled from one form to another."
  [source-asset content-type ^String content]
  (let [content-bytes (.getBytes content "UTF-8")]
    (assoc source-asset
      :compile true
      :content-type content-type
      :dependencies {(:resource-path source-asset) (select-keys source-asset [:checksum :modified-at])}
      :content content-bytes
      :size (alength content-bytes)
      :checksum (compute-checksum content-bytes))))

(defn content-type-matcher
  "Creates a handler for the asset pipeline that delegates to the provided handler, but passes
  the obtained asset through a transformer function if it matches the expected content type."
  [handler content-type transformer]
  (fn [asset-path]
    (if-let [asset (handler asset-path)]
      (if (= content-type (:content-type asset))
        (transformer asset)
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

;; Not the same as io/file!
(defn- as-file
  [url]
  (try
    (-> url .toURI File.)
    (catch URISyntaxException e
      (-> url .getPath File.))))

(defn modified-at
  [url]
  (some-> url as-file .lastModified Date.))

(defn pretty
  "Pretty-prints a value, returning it as a string."
  [value]
  (pp/write value :length 20 :stream nil))