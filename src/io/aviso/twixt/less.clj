(ns io.aviso.twixt.less
  "Provides asset pipeline middleware for compiling Less source files to CSS."
  (:import
    [com.github.sommeri.less4j LessSource LessSource$FileNotFound Less4jException LessCompiler$Problem LessCompiler LessCompiler$CompilationResult LessSource$StringSource LessCompiler$Configuration]
    [com.github.sommeri.less4j.core DefaultLessCompiler])
  (:require
    [clojure.java.io :as io]
    [clojure.string :as str]
    [io.aviso.tracker :as t]
    [io.aviso.twixt.utils :as utils]))

;; Putting this logic inside the (proxy) call causes some really awful Clojure compiler problems.
;; This shim seems to defuse that.
(defn- find-relative
  [asset-resolver asset relative-path context]
  (->
    asset
    :asset-path
    (utils/compute-relative-path relative-path)
    (asset-resolver context)))

(defn- ^LessSource create-less-source
  [asset-resolver dependencies asset context]
  ;; Whenever a LessSource is created, associated the asset as a dependency; this includes the primary source
  ;; and all imported sources.
  (swap! dependencies utils/add-asset-as-dependency asset)
  (proxy [LessSource] []
    (relativeSource [filename]
      (if-let [rel (find-relative asset-resolver asset filename context)]
        (create-less-source asset-resolver dependencies rel context)
        (throw (new LessSource$FileNotFound))))

    (getName [] (-> asset :asset-path utils/path->name))

    (toString [] (:resource-path asset))

    (getContent []
      (->
        asset
        :content
        utils/as-string
        (.replace "\r\n" "\n")))

    (getBytes [] (:content asset))))

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


(defn- compile-less
  [^LessCompiler less-compiler asset {:keys [asset-resolver] :as context}]
  (let [path (:resource-path asset)]
    (t/timer
      #(format "Compiled `%s' to CSS in %.2f ms" path %)
      (t/track
        #(format "Compiling `%s' from Less to CSS" path)
        (try
          (let [dependencies (atom {})
                root-source (create-less-source asset-resolver dependencies asset context)
                ^LessCompiler$Configuration options  (doto (LessCompiler$Configuration.)
                                                              (.setLinkSourceMap false))
                ;; A bit of work to trick the Less compiler into writing the right thing into the output CSS.
                ^LessCompiler$CompilationResult output (.compile less-compiler root-source options)
                complete-css (str
                               (.getCss output)
                               "\n/*# sourceMappingURL="
                               (utils/path->name path)
                               "@source.map */")
                source-map (.getSourceMap output)]
            (->
              asset
              (utils/create-compiled-asset "text/css" complete-css @dependencies)
              (utils/add-attachment "source.map" "application/json" (utils/as-bytes source-map))))
          (catch Less4jException e
            (throw (RuntimeException. (format-less-exception e) e))))))))

(defn register-less
  "Updates the Twixt options with support for compiling Less into CSS."
  [options]
  (-> options
      (assoc-in [:content-types "less"] "text/less")
      (assoc-in [:content-transformers "text/less"] (partial compile-less (DefaultLessCompiler.)))))
