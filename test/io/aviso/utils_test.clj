(ns io.aviso.utils-test
  (:use io.aviso.twixt.utils
        clojure.test))

(deftest relative-paths

  (are [start relative expected] (= (compute-relative-path start relative) expected)

                                 "foo/bar.gif" "baz.png" "foo/baz.png"

                                 "foo/bar.gif" "./baz.png" "foo/baz.png"

                                 "foo/bar.gif" "../zip.zap" "zip.zap"

                                 "foo/bar/gif" "../frozz/pugh.pdf" "foo/frozz/pugh.pdf")

  (is (thrown? IllegalArgumentException (compute-relative-path "foo/bar.png" "../../too-high.pdf"))))
