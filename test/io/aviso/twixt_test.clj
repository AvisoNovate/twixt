(ns io.aviso.twixt-test
  (:use
    clojure.test
    clojure.pprint
    io.aviso.twixt
    io.aviso.twixt.utils)
  (:require
    [clojure.java.io :as io]
    [io.aviso.twixt
     [coffee-script :as cs]
     [jade :as jade]
     [less :as less]
     [ring :as ring]]))

(defn read-asset-content [asset]
  (asset asset "Can't read content from nil asset.")
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

(defn- sorted-dependencies
  [asset]
  (->> asset :dependencies vals (map :asset-path) sort))

(def cache-folder (format "%s/%x" (System/getProperty "java.io.tmpdir") (System/nanoTime)))

(def options
  (->
    default-options
    (assoc :cache-folder cache-folder)
    ;; This is usually done by the startup namespace:
    cs/register-coffee-script
    (jade/register-jade true)
    (less/register-less)))

(def pipeline (default-asset-pipeline options true))

(def context (assoc options :asset-pipeline pipeline))

;; Note: updating the CoffeeScript compiler will often change the outputs, including checksums, not least because
;; the compiler injects a comment with the compiler version.

(def compiled-coffeescript-checksum "aea835e2")

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
                       :size 162
                       :checksum compiled-coffeescript-checksum))))

(deftest get-missing-asset-is-an-exception
  (is (thrown-with-msg?
        IllegalArgumentException
        #"Asset path `does/not/exist\.png' does not map to an available resource\."
        (get-asset-uri context "does/not/exist.png"))))

(deftest find-an-asset
  (let [asset-uri (find-asset-uri context "coffeescript-source.coffee")]
    (is (not (nil? asset-uri)))
    (is (= asset-uri (str "/assets/" compiled-coffeescript-checksum "/coffeescript-source.coffee"))))

  (is (nil? (find-asset-uri context "does/not/exist.png"))))

(deftest coffeescript-compilation

  (let [asset (pipeline "coffeescript-source.coffee" context)]
    (is (= (-> asset :content-type) "text/javascript"))
    (is (= (-> asset :checksum) compiled-coffeescript-checksum))
    (is (= (read-asset-content asset)
           (read-resource-content "assets/compiled-coffeescript-source.js")))
    (is (:compiled asset))
    (is (= (sorted-dependencies asset) ["coffeescript-source.coffee"])))

  (testing "compilation failure"

    (is (thrown-with-msg? Exception
                          #"META-INF/assets/invalid-coffeescript.coffee:6:1: error: unexpected indentation"
                          (pipeline "invalid-coffeescript.coffee" context)))))

(deftest jade-compilation

  (let [asset (pipeline "jade-source.jade" context)]
    (is (:compiled asset))
    (is (= (:content-type asset) "text/html"))
    (is (= (read-asset-content asset)
           (read-resource-content "assets/compiled-jade-source.html")))))

(deftest jade-includes

  (let [asset (pipeline "sub/jade-include.jade" context)]

    (is (= (read-asset-content asset)
           (read-resource-content "assets/compiled-jade-include.html")))

    ;; Ensure that dependencies were found for the source and all includes
    (is (= ["common.jade" "sub/jade-include.jade" "sub/samedir.jade"]
           (sorted-dependencies asset)))))

(deftest jade-helpers

  (let [context' (->
                   context
                   ;; The merge is normally part of the Ring code.
                   (merge (:twixt-template options))
                   ;; This could be done by Ring middleware, or by
                   ;; modifying the :twixt-template as well.
                   (assoc-in [:jade :variables "logoTitle"] "Our Logo"))
        asset (pipeline "jade-helper.jade" context')]

    (is (= (read-asset-content asset)
           (read-resource-content "assets/compiled-jade-helper.html")))

    (is (= ["aviso-logo.png" "jade-helper.jade"]
           (sorted-dependencies asset)))))

(deftest less-compilation

  (let [asset (pipeline "sample.less" context)
        expected (read-resource-content "assets/compiled-sample.css")]

    (is (= (:content-type asset) "text/css"))
    (is (= (read-asset-content asset) expected))

    (is (:compiled asset))
    (is (= (sorted-dependencies asset) ["colors.less" "sample.less"])))

  (testing "data-uri function, getData method"
    (let [asset (pipeline "logo.less" context)
          expected (read-resource-content "assets/compiled-logo.css")]
      (is (= (read-asset-content asset) expected))))

  (testing "compilation failure"

    (is (thrown-with-msg? Exception
                          #"META-INF/assets/invalid-less.less:3:5: no viable alternative at input 'p'"
                          (pipeline "invalid-less.less" context)))))

(deftest asset-redirector
  (let [wrapped (ring/wrap-with-asset-redirector (constantly nil))
        request {:uri "/sample.less" :twixt context}
        response (wrapped request)]

    (is (= (:status response) 302))
    (is (= (:body response) ""))
    ;; Don't want false failures if the checksum doesn't match (perhaps due to platform issues).
    (is (re-matches #"/assets/.*/sample.less" (get-in response [:headers "Location"])))

    ;; Ensure that folder paths are not matched.

    (are [path]
      (-> {:uri path :twixt context} wrapped nil?)

      "/"
      "/a-folder"
      "/another/folder/"
      )))