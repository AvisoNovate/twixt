(ns io.aviso.utils-spec
  (:use
    clojure.template
    io.aviso.twixt.utils
    speclj.core)
  (:require
    [clojure.java.io :as io]))


(defn- get-modified-at
  [resource-path]
  (-> resource-path io/resource modified-at .getTime))

(describe "io.aviso.twixt.utils"

          (context "compute-relative-path"

            (it "can compute relative paths"

                (do-template [start relative expected]

                             (should= expected
                                      (compute-relative-path start relative))

                             "foo/bar.gif" "baz.png" "foo/baz.png"

                             "foo/bar.gif" "./baz.png" "foo/baz.png"

                             "foo/bar.gif" "../zip.zap" "zip.zap"

                             "foo/bar/baz/biff.gif" "../gnip/zip.zap" "foo/bar/gnip/zip.zap"

                             "foo/bar/gif" "../frozz/pugh.pdf" "foo/frozz/pugh.pdf"))


            (it "throws IllegalArgumentException if ../ too many times"
                (should-throw IllegalArgumentException
                              (compute-relative-path "foo/bar.png" "../../too-high.pdf"))))

          (context "access to time modified"

            ;; This is to verify some underpinnings

            (it "can access time modified for a local file"

                ;; This will be on the classpath, but on the filesystem since it is part of the current project.

                (should (-> "META-INF/assets/bootstrap3/css/bootstrap.css"
                            get-modified-at
                            pos?)))

            (it "can access time modified for a resource in a JAR"
                (should (-> "META-INF/leiningen/io.aviso/pretty/LICENSE"
                            get-modified-at
                            pos?)))))


(run-specs)