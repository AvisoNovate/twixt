(ns io.aviso.twixt.less
  "Less to CSS compilation transformer."
  (:import [com.github.sommeri.less4j LessSource LessSource$FileNotFound Less4jException LessCompiler$Problem]
           [com.github.sommeri.less4j.core DefaultLessCompiler])
  (:require [io.aviso.twixt
             [tracker :as tracker]
             [utils :as utils]]
            [clojure.string :as str]))

(def
  ^:private
  ^:dynamic
  *dependencies*
  "A per-thread scratch pad to assemble dependencies."
  nil)

;; Putting this logic inside the (proxy) call causes some really awful Clojure compiler problems.
;; This shim seems to defuse that.
(defn- find-relative [asset-pipeline asset relative-path]
  (->
    asset
    :asset-path
    (utils/compute-relative-path relative-path)
    asset-pipeline))

(defn- create-less-source
  [asset-pipeline asset]
  ;; Whenever a LessSource is created, associated the asset as a dependency; this includes the primary source
  ;; and all imported sources.
  (swap! *dependencies* assoc (:resource-path asset) (select-keys asset [:checksum :modified-at :asset-path]))
  (proxy [LessSource] []
    (relativeSource [filename]
      (if-let [rel (find-relative asset-pipeline asset filename)]
        (create-less-source asset-pipeline rel)
        (throw (new LessSource$FileNotFound))))

    (toString [] (:resource-path asset))

    (getContent []
      (->
        asset
        :content
        utils/as-string
        (.replace "\r\n" "\n")))))

(defn- problem-to-string [^LessCompiler$Problem problem]
  (let [source (-> problem .getSource .toString)
        line (-> problem .getLine)
        character (-> problem .getCharacter)
        message (-> problem .getMessage)]
    (str source
         ":" line
         ":" character
         ": " message)))

(defn- format-less-exception [^Less4jException e]
  (let [problems (->> e .getErrors (map problem-to-string))]
    (str
      "Less compilation "
      (if (= 1 (count problems)) "error" "errors")
      ":\n"
      (str/join "\n" problems))))


(defn compile-less
  [less-compiler asset-pipeline asset]
  (let [name (:resource-path asset)]
    (tracker/log-time
      #(format "Compiled `%s' to CSS in %.2f ms" name %)
      (tracker/trace
        #(format "Compiling `%s' from Less to CSS." name)
        (binding [*dependencies* (atom {})]
          (try
            (let [root-source (create-less-source asset-pipeline asset)
                  output (.compile less-compiler root-source)]
              (->
                (utils/create-compiled-asset asset "text/css" (.getCss output))
                ;; But override the single dependency with the collected set of dependencies.
                (assoc :dependencies @*dependencies*)))
            (catch Less4jException e
              (throw (RuntimeException. (format-less-exception e) e)))))))))

(defn wrap-with-less-compilation
  [handler]
  (utils/content-type-matcher handler "text/less" (partial compile-less (DefaultLessCompiler.) handler)))