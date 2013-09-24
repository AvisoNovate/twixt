(ns io.aviso.utils-test
  (use io.aviso.twixt.utils
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