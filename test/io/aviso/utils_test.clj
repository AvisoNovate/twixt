(ns io.aviso.utils-test
  (:use io.aviso.twixt.utils
        clojure.test)
  (:require [clojure.java.io :as io]))

(deftest relative-paths

  (are [start relative expected] (= (compute-relative-path start relative) expected)

                                 "foo/bar.gif" "baz.png" "foo/baz.png"

                                 "foo/bar.gif" "./baz.png" "foo/baz.png"

                                 "foo/bar.gif" "../zip.zap" "zip.zap"

                                 "foo/bar/gif" "../frozz/pugh.pdf" "foo/frozz/pugh.pdf")

  (is (thrown? IllegalArgumentException (compute-relative-path "foo/bar.png" "../../too-high.pdf"))))


(defn- get-modified-at
  [resource-path]
  (-> resource-path io/resource modified-at .getTime))

(deftest access-to-time-modified

  ;; This will be on the classpath, but on the filesystem since it is part of the current project.

  (is (-> "META-INF/assets/bootstrap3/css/bootstrap.css"
           get-modified-at
           pos?))

  ;; This will be inside a JAR (see issue #5)

  (is (-> "META-INF/leiningen/io.aviso/pretty/LICENSE"
          get-modified-at
          pos?)))