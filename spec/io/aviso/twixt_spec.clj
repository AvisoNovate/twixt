(ns io.aviso.twixt-spec
  (:use
    speclj.core
    clojure.pprint
    clojure.template
    io.aviso.twixt
    io.aviso.twixt.utils)
  (:require
    [clojure.java.io :as io]
    [io.aviso.twixt
     [coffee-script :as cs]
     [jade :as jade]
     [less :as less]
     [ring :as ring]]))

(defn read-as-trimmed-string
  [content]
  (-> content
      read-content
      String.
      .trim))

(defn read-asset-content
  [asset]
  (assert asset "Can't read content from nil asset.")
  (->
    asset
    :content
    read-as-trimmed-string))

(defn read-attachment-content
  [asset attachment-name]
  (assert asset "Can't read content from nil asset.")
  (->
    asset
    (get-in [:attachments attachment-name])
    :content
    read-as-trimmed-string))

(defn read-resource-content
  [path]
  (->
    (str "META-INF/" path)
    io/resource
    read-as-trimmed-string))

(defn- sorted-dependencies
  [asset]
  (->> asset :dependencies vals (map :asset-path) sort))

;; Note: updating the CoffeeScript compiler will often change the outputs, including checksums, not least because
;; the compiler injects a comment with the compiler version.

(def compiled-coffeescript-checksum "52753585")

(describe "io.aviso.twixt"

  (with-all cache-folder (format "%s/%x" (System/getProperty "java.io.tmpdir") (System/nanoTime)))

  (with-all options (->
                      default-options
                      (assoc :cache-folder @cache-folder)
                      ;; This is usually done by the startup namespace:
                      cs/register-coffee-script
                      (jade/register-jade true)
                      (less/register-less)))

  (with-all pipeline (default-asset-pipeline @options true))

  (with-all twixt-context (assoc @options :asset-pipeline @pipeline))


  (context "asset pipeline"

    (it "returns nil when an asset is not found"

        (should-be-nil (@pipeline "does/not/exist.gif" @options))))

  (context "get-asset-uri"

    (it "throws IllegalArgumentException if an asset does not exist"
        (should-throw IllegalArgumentException
                      "Asset path `does/not/exist.png' does not map to an available resource."
                      (get-asset-uri @twixt-context "does/not/exist.png")))

    (it "returns the correct asset URI"
        (should= (str "/assets/" compiled-coffeescript-checksum "/coffeescript-source.coffee")
                 (find-asset-uri @twixt-context "coffeescript-source.coffee"))))

  (context "CoffeeScript compilation"

    (with-all asset (@pipeline "coffeescript-source.coffee" @twixt-context))

    (it "can read and transform a source file"

        (should-not-be-nil @asset)
        (should-not-be-nil (:modified-at @asset))

        (do-template [key expected]
                     (should= expected (key @asset)

                              :resource-path "META-INF/assets/coffeescript-source.coffee"
                              :asset-path "coffeescript-source.coffee"
                              :content-type "text/javascript"
                              :compiled true
                              :size 160
                              :checksum compiled-coffeescript-checksum)))

    (it "has the correct compiled content"
        (should= (read-resource-content "assets/compiled-coffeescript-source.js")
                 (read-asset-content @asset)))

    (it "has the correct source.map attachment"
        (should= (read-resource-content "assets/compiled-coffeescript-source.map")
                 (read-attachment-content @asset "source.map")))

    (it "has the expected dependencies"
        (should= (sorted-dependencies @asset)
                 ["coffeescript-source.coffee"]))

    (it "throws an exception if the source is not valid"
        (try
          (@pipeline "invalid-coffeescript.coffee" @twixt-context)
          (should-fail)
          (catch Exception e
            (should (-> e .getMessage (.startsWith "META-INF/assets/invalid-coffeescript.coffee:6:1: error: unexpected indentation")))))))

  (context "Jade compilation"

    (context "simple Jade source"
      (with-all asset (@pipeline "jade-source.jade" @twixt-context))


      (it "can read and transform a source file"

          (should= "text/html" (:content-type @asset))
          (should (:compiled @asset)))

      (it "has the correct compiled content"
          (should= (read-resource-content "assets/compiled-jade-source.html")
                   (read-asset-content @asset))))

    (context "using Jade includes"

      (with-all asset (@pipeline "sub/jade-include.jade" @twixt-context))

      (it "has the correct compiled content"
          (should= (read-resource-content "assets/compiled-jade-include.html")
                   (read-asset-content @asset)))

      (it "has the expected dependencies"
          (should= ["common.jade" "sub/jade-include.jade" "sub/samedir.jade"]
                   (sorted-dependencies @asset))))

    (context "using Jade helpers"

      (with-all context' (->
                       @twixt-context
                       ;; The merge is normally part of the Ring code.
                       (merge (:twixt-template @options))
                       ;; This could be done by Ring middleware, or by
                       ;; modifying the :twixt-template as well.
                       (assoc-in [:jade :variables "logoTitle"] "Our Logo")) )


      (with-all asset (@pipeline "jade-helper.jade" @context'))

      (it "has the correct compiled content"
          (should= (read-resource-content "assets/compiled-jade-helper.html")
                   (read-asset-content @asset)))

      (it "includes a dependency on the asset accessed by twixt.uri()"
          (should= ["aviso-logo.png" "jade-helper.jade"]
                   (sorted-dependencies @asset)))))

  (context "Less compilation"

    (context "basic compilation"
      (with-all asset (@pipeline "sample.less" @twixt-context))

      (it "can read and transform a source file"
          (should= "text/css" (:content-type @asset))
          (should (:compiled @asset)))

      (it "has the correct compiled content"
          (should= (read-resource-content "assets/compiled-sample.css")
                   (read-asset-content @asset)))

      (it "has an attached source.map"
          (should= (read-resource-content "assets/compiled-sample.less-source.map")
                   (read-attachment-content @asset "source.map")))

      (it "includes dependencies for @import-ed files"
          (should= ["colors.less" "sample.less"]
                   (sorted-dependencies @asset)))

      (it "throws an exception for compilation failures"
          (try
            (@pipeline "invalid-less.less" @twixt-context)
            (should-fail)
            (catch Exception e
              #_ (-> e .getMessage println)
              (should (-> e .getMessage (.contains "META-INF/assets/invalid-less.less:3:5: no viable alternative at input 'p'")))))))

    (context "using data-uri and getData method"

      (with-all asset (@pipeline "logo.less" @twixt-context))

      (it "has the correct compiled content"
          (should= (read-resource-content "assets/compiled-logo.css")
                   (read-asset-content @asset)))))

  (context "asset redirector"
    (with-all wrapped (ring/wrap-with-asset-redirector (constantly nil)))

    (with-all request {:uri "/sample.less" :twixt @twixt-context})

    (with-all response (@wrapped @request))

    (it "sends a 302 response"

        (should= 302 (:status @response)))

    (it "sends an empty body"
        (should= "" (:body @response)))

    (it "sends a proper Location header"
        (should (re-matches #"/assets/.*/sample.less" (get-in @response [:headers "Location"]))))

    (it "returns nil for non-matching paths"
        (do-template [path]
                     (should-be-nil (@wrapped {:uri path :twixt @twixt-context}))
                     "/"
                     "/a-folder"
                     "/another/folder/"))

    )

  ;; Slightly bogus; this lets the mass of exception written out by the executing tests have a chance to finish
  ;; before speclj outputs the report; without it, you often get a jumble of console output (including formatted exceptions)
  ;; and the report as well.  Perhaps another solution is to get speclj to pipe its output through clojure.tools.logging?
  (it "needs to slow down to let the console catch up"
      (Thread/sleep 250)))



(run-specs)