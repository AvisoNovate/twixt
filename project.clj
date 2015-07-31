(defproject io.aviso/twixt "0.1.18"
            :description "An extensible asset pipeline for Clojure web applications"
            :url "https://github.com/AvisoNovate/twixt"
            :license {:name "Apache Sofware Licencse 2.0"
                      :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
            :dependencies [[org.clojure/clojure "1.6.0"]
                           [io.aviso/pretty "0.1.18"]
                           [io.aviso/tracker "0.1.6"]
                           [medley "0.6.0"]
                           [ring/ring-core "1.4.0"]
                           [com.google.javascript/closure-compiler "v20150609"]
                           [org.mozilla/rhino "1.7.7"]
                           [com.github.sommeri/less4j "1.13.0"]
                           [de.neuland-bfi/jade4j "0.4.3"]
                           [prismatic/schema "0.4.3"]
                           [hiccup "1.0.5"]
                           [org.webjars/bootstrap "3.3.5"]
                           [org.webjars/webjars-locator-core "0.27"]]
  :test-paths ["spec"]
  :plugins [[speclj "3.2.0"]
                      [lein-shell "0.4.0"]]
            :shell {:commands {"scp" {:dir "doc"}}}
            :aliases {"deploy-doc" ["shell"
                                    "scp" "-r" "." "hlship_howardlewisship@ssh.phx.nearlyfreespeech.net:io.aviso/twixt"]
                      "release"    ["do"
                                    "clean,"
                                    "spec,",
                                    "doc,"
                                    "deploy-doc,"
                                    "deploy" "clojars"]}
            :codox {:src-dir-uri               "https://github.com/AvisoNovate/twixt/blob/master/"
                    :src-linenum-anchor-prefix "L"
                    :defaults                  {:doc/format :markdown}}
  :profiles {:dev {:dependencies           [[ch.qos.logback/logback-classic "1.1.3"]
                                            [speclj "3.2.0"]
                                            [ring/ring-jetty-adapter "1.4.0"]]
                             :jvm-opts     ["-Xmx1g"]}})
