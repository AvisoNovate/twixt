(ns io.aviso.utils-test
  (:use io.aviso.twixt.utils
        clojure.test))

(deftest map-merging

  (is (=
        (merge-maps-recursively {:a 1} {:b 2} {:a 3 :c 3})
        {:a 3 :b 2 :c 3}))

  (deftest merge-map-values
    (is (=
          (merge-maps-recursively {:a {:b 1}} {:a {:b 2 :c 2}})
          {:a {:b 2 :c 2}})))

  (deftest merge-seq-values
    (is (=
          (merge-maps-recursively {:a [1]} {:a [2] :c [3 4]} {:c [5 6]})
          {:a [1 2]
           :c [3 4 5 6]}))))

(deftest relative-paths

  (are [start relative expected] (= (compute-relative-path start relative) expected)

                                 "foo/bar.gif" "baz.png" "foo/baz.png"

                                 "foo/bar.gif" "./baz.png" "foo/baz.png"

                                 "foo/bar.gif" "../zip.zap" "zip.zap"

                                 "foo/bar/gif" "../frozz/pugh.pdf" "foo/frozz/pugh.pdf")

  (is (thrown? IllegalArgumentException (compute-relative-path "foo/bar.png" "../../too-high.pdf"))))
