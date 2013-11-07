(ns io.aviso.twixt-test
  (:use clojure.test
        io.aviso.twixt
        io.aviso.twixt.utils)
  (:require [clojure.java.io :as io]))

(defn read-asset-content [asset]
  (->
    asset
    :content
    read-content
    String.
    .trim))

(defn read-resource-content [path]
  (->
    (str "META-INF/" path)
    io/resource
    read-content
    String.
    .trim))

(def cache-folder (format "%s/%x" (System/getProperty "java.io.tmpdir") (System/nanoTime)))

(def development-mode-pipeline (default-asset-pipeline
                                 (assoc default-options :cache-folder cache-folder)
                                 true))

(deftest asset-pipeline
  (let [resolver (make-asset-resolver default-options)]
    (testing "asset not found"
      (is (nil? (resolver "does/not/exist.gif"))))

    (testing "asset found"
      (let [asset (resolver "coffeescript-source.coffee")]
        (is (not (nil? asset)))
        (is (-> asset :modified-at nil? not))
        (are [key value] (= (key asset) value)
                         :resource-path "META-INF/assets/coffeescript-source.coffee"
                         :asset-path "coffeescript-source.coffee"
                         :content-type "text/coffeescript"
                         :size 30
                         :checksum "adbe0aaf")))))


#_ (deftest uri-generation

  (is (= (get-asset-uri twixt "sample.js") "/assets/sample.js"))
  (is (nil? (get-asset-uri twixt "does-not-exist.js"))))

(deftest coffeescript-compilation

  (let [asset (development-mode-pipeline "coffeescript-source.coffee")]
    (is (= (-> asset :content-type) "text/javascript"))
    (is (= (-> asset :checksum) "5f181fb6"))
    (is (= (read-asset-content asset)
           (read-resource-content "assets/compiled-coffeescript-source.js"))))

  #_ (deftest compiled-results-are-cached
    (let [v1 (get-streamable twixt "coffeescript-source.coffee")
          v2 (get-streamable twixt "coffeescript-source.coffee")]

      ;; Clojure doesn't have a good way of checking that two references are to the same object (e.g., Groovy's is()
      ;; method). Instead, we compare the :content keys, which are a byte array, which should compare based on identity
      ;; rather than content.
      (is (= (:content v1) (:content v2)))))

  (testing "compilation failure"

    (is (thrown-with-msg? Exception
                          #"META-INF/assets/invalid-coffeescript.coffee:6:1: error: unexpected INDENT"
                          (development-mode-pipeline "invalid-coffeescript.coffee")))))

(deftest jade-compilation

  (let [asset (development-mode-pipeline "jade-source.jade")]
    (is (= (:content-type asset) "text/html"))
    (is (= (read-asset-content asset)
           (read-resource-content "assets/compiled-jade-source.html")))))



(deftest less-compilation

  (let [asset (development-mode-pipeline "sample.less")
        expected (read-resource-content "assets/compiled-sample.css")]

    (is (= (:content-type asset) "text/css"))
    (is (= (read-asset-content asset) expected)))

  (testing "compilation failure"

    (is (thrown-with-msg? Exception
                          #"META-INF/assets/invalid-less.less:3:5: no viable alternative at input 'p'"
                          (development-mode-pipeline "invalid-less.less")))))