(ns io.aviso.twixt-spec
  (:use speclj.core
        clojure.pprint
        clojure.template
        [io.aviso.twixt :exclude [find-asset]]
        io.aviso.twixt.utils)
  (:require [clojure.java.io :as io]
            [ring.middleware.resource :as resource]
            [io.aviso.twixt
             [coffee-script :as cs]
             [jade :as jade]
             [less :as less]
             [ring :as ring]
             [startup :as startup]
             [stacks :as stacks]]
            [clojure.string :as str]
            [clojure.tools.logging :as l]
            [io.aviso.twixt :as twixt]))

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
    path
    io/resource
    read-as-trimmed-string))

(defmacro should-have-content
  [expected actual]
  `(let [expected# ~expected
         actual#   ~actual]
     (when (not (= expected# actual#))
       (-fail (format "Expected content did not match actual content:%n----%n%s%n----"
                      actual#)))))

(defn have-same-content
  [expected-content-resource asset]
  (let [expected (read-resource-content expected-content-resource)
        actual   (read-asset-content asset)]
    (if (= expected actual)
      true
      (printf "Content of `%s' did not match `%s'\n----\n%s\n----\n"
              (:resource-path asset)
              expected-content-resource
              actual))))

(defn- sorted-dependencies
  [asset]
  (->> asset :dependencies vals (map :asset-path) sort))

(defn- remove-hash-from-uri
  [^String asset-uri]
  (str/join "/" (->
                  asset-uri
                  (.split "/")
                  vec
                  (assoc 2 "[hash]"))))

;; Note: updating the CoffeeScript compiler will often change the outputs, including checksums, not least because
;; the compiler injects a comment with the compiler version.

(def compiled-coffeescript-checksum "54753589")

(describe "io.aviso.twixt"

  (with-all cache-dir (format "%s/%x" (System/getProperty "java.io.tmpdir") (System/nanoTime)))

  (defn with-sub-cache-dir [twixt-options subdir]
    (update-in twixt-options [:cache :cache-dir] str "/" subdir))

  (defn get-asset-with-options [twixt-options asset-path]
    (let [pipeline (default-asset-pipeline twixt-options)]
      (pipeline asset-path (assoc twixt-options :asset-pipeline pipeline))))

  (with-all options (->
                      default-options
                      (assoc :development-mode true
                             :js-optimizations :none)
                      (assoc-in [:cache :cache-dir] @cache-dir)
                      ;; This is usually done by the startup namespace:
                      cs/register-coffee-script
                      jade/register-jade
                      less/register-less
                      stacks/register-stacks))

  (with-all pipeline (default-asset-pipeline @options))

  (with-all twixt-context (assoc @options :asset-pipeline @pipeline))

  (defn find-asset
    [asset-path]
    (@pipeline asset-path @twixt-context))

  (context "asset pipeline"

    (it "returns nil when an asset is not found"

        (should-be-nil (@pipeline "does/not/exist.gif" @options))))

  (context "get-asset-uri"

    (it "throws IllegalArgumentException if an asset does not exist"
        (should-throw Exception
                      "Asset path `does/not/exist.png' does not map to an available resource."
                      (get-asset-uri @twixt-context "does/not/exist.png")))

    (it "returns the correct asset URI"
        (should= (str "/assets/" compiled-coffeescript-checksum "/coffeescript-source.coffee")
                 (find-asset-uri @twixt-context "coffeescript-source.coffee")))


    (it "can find WebJars assets"
        (should
          (have-same-content "META-INF/resources/webjars/bootstrap/3.3.5/js/alert.js"
                             (find-asset "bootstrap/js/alert.js"))))

    (it "can process compilation of WebJars assets"
        (should
          (have-same-content "expected/bootstrap-webjars.css"
                             (find-asset "bootstrap/less/bootstrap.less")))))

  (context "CoffeeScript compilation"

    (with-all asset (@pipeline "coffeescript-source.coffee" @twixt-context))

    (it "can read and transform a source file"

        (should-not-be-nil @asset)
        (should-not-be-nil (:modified-at @asset))

        (do-template [key expected]
                     (should= expected (key @asset)
                              {:resource-path "META-INF/assets/coffeescript-source.coffee"
                               :asset-path    "coffeescript-source.coffee"
                               :content-type  "text/javascript"
                               :compiled      true
                               :size          160
                               :checksum      compiled-coffeescript-checksum})))
    (it "has the correct compiled content"
        (should (have-same-content "expected/coffeescript-source.js" @asset)))

    (it "has the correct source.map attachment"
        (should-have-content
          (read-resource-content "expected/coffeescript-source.map")
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
          (should (have-same-content "expected/jade-source.html" @asset))))

    (context "using Jade includes"

      (with-all asset (@pipeline "sub/jade-include.jade" @twixt-context))

      (it "has the correct compiled content"
          (should (have-same-content "expected/jade-include.html" @asset)))

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
                           (assoc-in [:jade :variables "logoTitle"] "Our Logo")))


      (with-all asset (@pipeline "jade-helper.jade" @context'))

      (it "has the correct compiled content ignoring attr order"
          (should (or (= "<img title=\"Our Logo\" src=\"/assets/8ee745bf/aviso-logo.png\">" (read-asset-content @asset))
                      (= "<img src=\"/assets/8ee745bf/aviso-logo.png\" title=\"Our Logo\">" (read-asset-content @asset)))))

      (it "includes a dependency on the asset accessed by twixt.uri()"
          (should= ["aviso-logo.png" "jade-helper.jade"]
                   (sorted-dependencies @asset)))


      (it "supports multiple assets via twixt.uris()"
          (should (have-same-content "expected/jade-uris-helper.html"
                                     (@pipeline "jade-uris-helper.jade" @context'))))))

  (context "Less compilation"

    (context "basic compilation"
      (with-all asset (@pipeline "sample.less" @twixt-context))

      (it "can read and transform a source file"
          (should= "text/css" (:content-type @asset))
          (should (:compiled @asset)))

      (it "has the correct compiled content"
          (should (have-same-content "expected/sample.css" @asset)))

      (it "has an attached source.map"
          (should-have-content (read-resource-content "expected/sample.map")
                               (read-attachment-content @asset "source.map")))

      (it "includes dependencies for @import-ed files"
          (should= ["colors.less" "sample.less"]
                   (sorted-dependencies @asset)))

      (it "throws an exception for compilation failures"
          (try
            (@pipeline "invalid-less.less" @twixt-context)
            (should-fail)
            (catch Exception e
              #_(-> e .getMessage println)
              (should (-> e .getMessage (.contains "META-INF/assets/invalid-less.less:3:5: no viable alternative at input 'p'")))))))

    (context "using data-uri and getData method"

      (with-all asset (@pipeline "logo.less" @twixt-context))

      (it "has the correct compiled content"
          (should (have-same-content "expected/logo.css" @asset)))))

  (context "stack support"

    (context "simple stack"

      (with-all asset (@pipeline "stack/bedrock.stack" @twixt-context))

      (it "has the content type from the stack file"
          (should= "text/javascript" (:content-type @asset)))

      (it "is marked as compiled"
          (should (:compiled @asset)))

      (it "identifies the correct aggregated asset paths"
          (should= ["stack/fred.js" "stack/barney.js"]
                   (-> @asset :aggregate-asset-paths)))

      (it "includes dependencies on every file in the stack"
          (should= ["stack/barney.js" "stack/bedrock.stack" "stack/fred.js"]
                   (sorted-dependencies @asset)))

      (it "has the correct aggregated content"
          (should (have-same-content "expected/bedrock.js" @asset))))

    (context "stack with compilation"

      (with-all asset (@pipeline "stack/compiled.stack" @twixt-context))

      (it "identifies the correct aggregated asset path"
          (should= ["coffeescript-source.coffee" "stack/stack.coffee"]
                   (-> @asset :aggregate-asset-paths)))

      (it "includes dependencies on every file in the stack"
          (should= ["coffeescript-source.coffee" "stack/compiled.stack" "stack/stack.coffee"]
                   (sorted-dependencies @asset)))

      ;; Note: the compiled output includes the sourceMappingURL comments.
      ;; It is possible that will not work well in the client and may need to be filtered out.
      (it "has the correct aggregated content"
          (should (have-same-content "expected/compiled-stack.js" @asset))))

    (context "stack of stacks"
      (with-all asset (@pipeline "stack/meta.stack" @twixt-context))

      (it "identifies the correct aggregated asset path"
          (should= ["stack/fred.js" "stack/barney.js" "coffeescript-source.coffee" "stack/stack.coffee"]
                   (-> @asset :aggregate-asset-paths)))

      (it "includes dependencies on every file in the stack"
          (should= ["coffeescript-source.coffee" "stack/barney.js" "stack/bedrock.stack" "stack/compiled.stack" "stack/fred.js" "stack/meta.stack" "stack/stack.coffee"]
                   (sorted-dependencies @asset)))


      (it "has the correct aggregated content"
          (should (have-same-content "expected/meta.js" @asset))))

    (context "stack with missing component"

      (it "throws a reasonable exception when a component is missing"
          (try
            (get-asset-uri @twixt-context "stack/missing-component.stack")
            (should-fail)
            (catch Exception e
              (should= "Could not locate resource `stack/does-not-exist.coffee' (a component of `stack/missing-component.stack')."
                       (.getMessage e))))))

    (context "CSS stack"

      (with-all asset (@pipeline "stack/style.stack" @twixt-context))

      (it "has the content type from the stack file"
          (should= "text/css" (:content-type @asset)))

      (it "identifies the correct aggregated asset paths"
          (should= ["stack/local.less" "sample.less"]
                   (-> @asset :aggregate-asset-paths)))

      (it "identifies the correct dependencies"
          ;; Remember that a change to aviso-logo.png will change the checksum in the
          ;; compiled and CSS-rewritten stack/local.css, so it must be a dependency
          ;; of the final asset.
          (should= ["aviso-logo.png" "colors.less" "sample.less" "stack/local.less" "stack/style.stack"]
                   (sorted-dependencies @asset)))

      ;; Again, need to think about stripping out the sourceMappingURL lines.
      (it "contains the correct aggregated content"
          (should (have-same-content "expected/style.css" @asset))))

    (context "get-asset-uri"
      (it "always returns the stack asset URI"
          (should= "/assets/[hash]/stack/bedrock.stack"
                   (->> (get-asset-uri @twixt-context "stack/bedrock.stack")
                        remove-hash-from-uri))))

    (context "get-asset-uris"

      (it "returns a list of component asset URIs in place of a stack"

          (should= ["/assets/[hash]/stack/fred.js" "/assets/[hash]/stack/barney.js"]
                   (->> (get-asset-uris @twixt-context "stack/bedrock.stack")
                        (map remove-hash-from-uri))))

      (it "returns just the stack asset URI in production mode"
          (should= ["/assets/[hash]/stack/bedrock.stack"]
                   (->> (get-asset-uris (assoc @twixt-context :development-mode false) "stack/bedrock.stack")
                        (map remove-hash-from-uri))))))

  (context "JavaScript Minimization"
    (context "is enabled by default in production mode"

      (with-all prod-options (-> @options
                                 (assoc :development-mode false
                                        :js-optimizations :default)
                                 (with-sub-cache-dir "js-min-1")))

      (with-all prod-pipeline (default-asset-pipeline @prod-options))

      (with-all prod-twixt-context (assoc @prod-options :asset-pipeline @prod-pipeline))

      (with-all asset (@prod-pipeline "stack/meta.stack" @prod-twixt-context))

      (it "contains the correct minimized content"
          (should (have-same-content "expected/minimized/meta.js" @asset)))

      (it "can handle much larger files"
          (should (have-same-content "expected/minimized/bootstrap.js"
                                     (@prod-pipeline "stack/bootstrap.stack" @prod-twixt-context)))))

    (context "can be disabled in production mode"

      (with-all prod-options (-> @options
                                 (assoc :development-mode false
                                        :js-optimizations :none)
                                 (with-sub-cache-dir "js-min-2")))

      (with-all prod-pipeline (default-asset-pipeline @prod-options))

      (with-all prod-twixt-context (assoc @prod-options :asset-pipeline @prod-pipeline))

      (with-all asset (@prod-pipeline "stack/meta.stack" @prod-twixt-context))

      (it "contains the correct unoptimized content"
          (should (have-same-content "expected/meta.js" @asset))))

    (context "can be enabled in development mode"

      (with-all dev-options (-> @options
                                (assoc :js-optimizations :simple)
                                (with-sub-cache-dir "js-min-3")))

      (with-all dev-pipeline (default-asset-pipeline @dev-options))

      (with-all dev-twixt-context (assoc @dev-options :asset-pipeline @dev-pipeline))

      (with-all asset (@dev-pipeline "stack/fred.js" @dev-twixt-context))

      (it "contains the correct unoptimized content"
          (should (have-same-content "expected/minimized/fred.js" @asset)))))

  (context "CSS minification"
    (it "can be expicitly enabled"
        (let [asset (-> @options
                        (assoc :minimize-css true)
                        (with-sub-cache-dir "css-min-1")
                        (get-asset-with-options "sample.less"))]
          (should
            (have-same-content "expected/minimized/sample.css" asset))))

    (it "is enabled by default in production mode"
        (let [asset (-> @options
                        (assoc :development-mode false)
                        (with-sub-cache-dir "css-min-2")
                        ;; This is not a terrific idea in that a change to the Bootstrap dependency
                        ;; will invalidate this test. On the other hand, it ensures (on the side)
                        ;; that YUICompressor can handle compiling all of Bootstrap.
                        (get-asset-with-options "bootstrap/less/bootstrap.less"))]
          (should
            (have-same-content "expected/minimized/bootstrap.css" asset)))))

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
                     "/another/folder/")))

  (context "exporter"

    (with-all ring-handler (startup/wrap-with-twixt
                             (-> (fn [request]
                                   (-> request
                                       :twixt
                                       (get-asset-uri "sub/jade-include.jade")))
                                 (resource/wrap-resource "target/exported"))
                             (-> twixt/default-options
                                 (assoc-in [:exports :output-dir] "target/exported")
                                 (update-in [:exports :assets] into ["sub/jade-include.jade"]))
                             true))


    (it "can export a file"
        (let [response (@ring-handler {:request-method :get
                                       :uri            "/sub/jade-include.jade"})]


          (should-have-content (read-resource-content "expected/jade-include.html")
                               (-> "target/exported/sub/jade-include.jade"
                                   io/file
                                   read-as-trimmed-string))))

    (it "exposes the exported alias as the asset URI"
        (should= "/sub/jade-include.jade"
                 (@ring-handler {:request-method :get
                                 :uri            "any-match-ok"}))))

  ;; Slightly bogus; this lets the mass of exceptions written out by the executing tests have a chance to finish
  ;; before speclj outputs the report; without it, you often get a jumble of console output (including formatted exceptions)
  ;; and the report as well.  Perhaps another solution is to get speclj to pipe its output through clojure.tools.logging?
  (it "needs to slow down to let the console catch up"
      (Thread/sleep 1000))

  (after-all (System/gc)))



(run-specs)