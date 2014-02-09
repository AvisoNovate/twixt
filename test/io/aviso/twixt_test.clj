(ns io.aviso.twixt-test
  (:use clojure.test
        io.aviso.twixt
        io.aviso.twixt.utils)
  (:require [clojure.java.io :as io]
            [io.aviso.twixt
             [coffee-script :as cs]
             [jade :as jade]
             [less :as less]
             [ring :as ring]]))

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

(def options
  (->
    default-options
    (assoc  :cache-folder cache-folder)
    ;; This is usually done by the startup namespace:
    cs/register-coffee-script
    (jade/register-jade true)
    (less/register-less)))

(def pipeline (default-asset-pipeline options true))

(def context (assoc options :asset-pipeline pipeline))

;; Note: updating the CoffeeScript compiler will often change the outputs, including checksums, not least because
;; the compiler injects a comment with the compiler version.

(def compiled-coffeescript-checksum "5ed71fb5")

(deftest asset-pipeline
  (testing "asset not found"
    (is (nil? (pipeline "does/not/exist.gif" options))))

  (testing "asset found"
    (let [asset (pipeline "coffeescript-source.coffee" context)]
      (is (not (nil? asset)))
      (is (-> asset :modified-at nil? not))
      (are [key value] (= (key asset) value)
                       :resource-path "META-INF/assets/coffeescript-source.coffee"
                       :asset-path "coffeescript-source.coffee"
                       :content-type "text/javascript"
                       :size 100
                       :checksum compiled-coffeescript-checksum))))

(deftest get-missing-asset-is-an-exception
  (is (thrown?
        Exception
        (get-asset-uri context "does/not/exist.png"))))

(deftest find-an-asset
  (let [asset-uri (find-asset-uri context "coffeescript-source.coffee")]
    (is (not (nil? asset-uri)))
    (is (= asset-uri (str "/assets/" compiled-coffeescript-checksum "/coffeescript-source.coffee"))))

  (is (nil? (find-asset-uri context "does/not/exist.png"))))



#_ (deftest uri-generation

  (is (= (get-asset-uri twixt "sample.js") "/assets/sample.js"))
  (is (nil? (get-asset-uri twixt "does-not-exist.js"))))

(deftest coffeescript-compilation

  (let [asset (pipeline "coffeescript-source.coffee" context)]
    (is (= (-> asset :content-type) "text/javascript"))
    (is (= (-> asset :checksum) compiled-coffeescript-checksum))
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
                          #"META-INF/assets/invalid-coffeescript.coffee:6:1: error: unexpected indentation"
                          (pipeline "invalid-coffeescript.coffee" context)))))

(deftest jade-compilation

  (let [asset (pipeline "jade-source.jade" context)]
    (is (= (:content-type asset) "text/html"))
    (is (= (read-asset-content asset)
           (read-resource-content "assets/compiled-jade-source.html")))))



(deftest less-compilation

  (let [asset (pipeline "sample.less" context)
        expected (read-resource-content "assets/compiled-sample.css")]

    (is (= (:content-type asset) "text/css"))
    (is (= (read-asset-content asset) expected)))


  (testing "data-uri function, getData method"
    (let [asset (pipeline "logo.less" context)
          expected (read-resource-content "assets/compiled-logo.css")]
      (is (= (read-asset-content asset) expected))))

  (testing "compilation failure"

    (is (thrown-with-msg? Exception
                          #"META-INF/assets/invalid-less.less:3:5: no viable alternative at input 'p'"
                          (pipeline "invalid-less.less" context)))))

(deftest asset-redirector
  (let [wrapped (ring/wrap-with-asset-redirector nil)
        request {:uri "/sample.less" :twixt context}
        response (wrapped request)]

    (is (= (:status response) 302))
    (is (= (:body response) ""))
    ;; Don't want false failures if the checksum doesn't match (perhaps due to platform issues).
    (is (re-matches #"/assets/.*/sample.less" (get-in response [:headers "Location"])))))