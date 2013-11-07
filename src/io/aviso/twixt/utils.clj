(ns io.aviso.twixt.utils
  "Some re-usable utilities. This namespace should be considered unsupported."
  (:import [java.io CharArrayWriter]
           [java.nio.charset Charset]
           [java.util.zip Adler32])
  (:require [clojure.java.io :as io]))

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
