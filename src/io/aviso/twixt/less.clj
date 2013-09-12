(ns io.aviso.twixt.less
  "Less to CSS compilation transformer."
  (use io.aviso.twixt.streamable)
  (import [com.github.sommeri.less4j LessSource LessSource$FileNotFound Less4jException LessCompiler$Problem]
          [com.github.sommeri.less4j.core DefaultLessCompiler])
  (require [io.aviso.twixt.tracker :as tracker]
           [clojure.string :as s]))

;; Putting this logic inside the (proxy) call causes some really awful Clojure compiler problems.
;; This shim seems to defuse that.
(defn- find-relative [streamable relative-path]
  (relative streamable relative-path))

(defn- create-less-source
  [streamable]
  (proxy [LessSource] []
    (relativeSource [filename]
                    (if-let [rel (find-relative streamable filename)]
                      (create-less-source rel)
                      (throw (new LessSource$FileNotFound))))
    
    (toString [] (source-name streamable))
    
    (getContent []
                (-> (as-string streamable utf-8) (.replace "\r\n" "\n")))))

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
      (s/join "\n" problems))))

(defn less-compiler-factory
  [options]
  (let [less-compiler (DefaultLessCompiler.)]
    (fn compiler [source]
      (let [name (source-name source)]
        (tracker/log-time
          #(format "Compiled `%s' to CSS in %.2f ms" name %)
          (tracker/trace
            #(format "Compiling `%s' from Less to CSS." name)
            (try
              (let [root-source (create-less-source source)
                    output (.compile less-compiler root-source)]
                ;; We currently ignore any warnings.
                (replace-content source
                                 (str "Compiled " name)
                                 "text/css"
                                 (as-bytes (.getCss output))))
              (catch Less4jException e
                (throw (RuntimeException. (format-less-exception e) e))))))))))
