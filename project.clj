(defproject io.aviso/twixt "0.1.15"
            :description "An extensible asset pipeline for Clojure web applications"
            :url "https://github.com/AvisoNovate/twixt"
            :license {:name "Apache Sofware Licencse 2.0"
                      :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
            :dependencies [[org.clojure/clojure "1.6.0"]
                           [io.aviso/pretty "0.1.12"]
                           [io.aviso/tracker "0.1.2"]
                           [ring/ring-core "1.3.1"]
                           [com.google.javascript/closure-compiler "v20140814"]
                           [org.mozilla/rhino "1.7R4"]
                           [com.github.sommeri/less4j "1.8.3"]
                           [de.neuland-bfi/jade4j "0.4.0"]
                           [hiccup "1.0.5"]]
            :test-paths ["spec"]
            :plugins [[speclj "3.1.0"]
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
            :profiles {:dev {:dependencies [[log4j "1.2.17"]
                                            [speclj "3.1.0"]
                                            [ring/ring-jetty-adapter "1.2.0"]]
                             :jvm-opts     ["-Xmx1g"]}})
