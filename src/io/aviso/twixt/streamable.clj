(ns io.aviso.twixt.streamable
  "Defines Streamable protocol and implementation, and supporting functions."
  (require [io.aviso.twixt.dependency :as d]
           [clojure.java.io :as io]
           [clojure.string :as s])
  (import [java.io InputStream ByteArrayOutputStream]
          [java.util.zip Adler32]
          [java.nio.charset Charset]
          [java.net URL]))

(def ^Charset utf-8 (Charset/forName "UTF-8"))

(defn as-bytes [^String input]
  "Returns the input string as a byte array using UTF-8."
  (.getBytes input utf-8))

(defn read-content
  "Reads the content of a provided source (compatible with clojure.java.io/input-stream) as a byte array. The InputStream is not closed."
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
      (->> path-terms reverse (s/join "/"))
      (let [term (first terms)
            remaining (rest terms)]
        (cond
          (or (= term ".") (= term "")) (recur path-terms remaining)
          (= term "..") (if (empty? path-terms)
                          ;; You could rewrite this with reduce, but then generating this exception would be more difficult:
                          (throw (IllegalArgumentException. (format "Relative path `%s' for `%s' would go above root." relative start)))
                          (recur (drop-last path-terms) remaining))
          :else (recur (conj path-terms term) remaining))))))

(defn- invalid-create-relative [path]
  (throw (IllegalStateException. "Relative paths from transformed resources do not make sense.")))

(defn compute-checksum 
  "Returns a hex string of the Adler32 checksum of the content."
  [^bytes content]
  (-> 
    (doto (Adler32.) (.update content))
    .getValue
    Long/toHexString))

(defprotocol Streamable
  ;; TODO - additional functions related to content aggregation
  (^String source-name 
           [this] 
           "String identifying the source of the content (used primarily for error reporting).")
  (^String as-string 
           [this] 
           [this charset]  
           "Convert content bytestream to text (uses UTF-8 if not specified).")
  (^String content-type 
           [this] 
           "MIME type of content.")
  (^Streamable relative 
               [this relative-path] 
               "Returns a new Streamable relative to this one, or null if it does not exist. 
               The new streamable will share its DependencyChangeTracker (used by dirty?)
               and the underlying resource will be tracked for changes.")
  (^int content-size 
        [this] 
        "Number of bytes in the stream.")
  (^InputStream open 
                [this] 
                "Opens a stream to the content.")
  (^String checksum 
           [this] 
           "Returns a checksum string of the content of this Streamable.")
  (^Streamable replace-content 
               [this source-name content-type source]
               "Replaces the content (say, after CoffeeScript compilation), but maintains the same tracker (used by dirty?).
               source must be compatible with clojure.java.io/input-stream.
               It is not allowed to find relative Streamables from the returned Streamable.")
  (^boolean dirty? [this]
            "Returns true if this Streamable (or any related Streamable due to (relative) is dirty 
            (the underlying source resource has changed since the Streamable was created)."))

(defn- create-streamable-from-source
  [tracker create-relative source-name content-type source]
  (let [^bytes content (read-content source)
        content-checksum (compute-checksum content)]
    (reify
      Streamable
      (source-name [this] source-name)
      (content-type [this] content-type)
      (content-size [this] (.length content))
      (relative [this relative-path] (create-relative relative-path))
      (open [this] (io/input-stream content))
      (as-string [this ] (as-string this utf-8))
      (as-string [this charset] (String. content charset))
      (dirty? [this] (d/dirty? tracker))
      (checksum [this] content-checksum)
      (replace-content
        [this source-name content-type source]
        (create-streamable-from-source tracker invalid-create-relative 
                                       source-name content-type source)))))

(defn create-streamable
  "Creates a Streamable.
  
  tracker - Used to track dependencies
  create-relative - Used to create relative streamables; passed a relative path.
  path - Used to as the source-name.
  content-type - MIME content type.
  source - source of content (compatible with clojure.java.io/input-stream)."
  [tracker create-relative path content-type source]
  (create-streamable-from-source tracker create-relative 
                                 path content-type source))



