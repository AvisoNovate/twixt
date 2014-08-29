(defproject io.aviso/twixt "0.1.14-SNAPSHOT"
  :description "An extensible asset pipeline for Clojure web applications"
  :url "https://github.com/AvisoNovate/twixt"
  :license {:name "Apache Sofware Licencse 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [io.aviso/pretty "0.1.12"]
                 [io.aviso/tracker "0.1.1"]
                 [ring/ring-core "1.3.1"]
                 [com.google.javascript/closure-compiler "v20140814"]
                 [org.mozilla/rhino "1.7R4"]
                 [com.github.sommeri/less4j "1.8.0"]
                 [de.neuland-bfi/jade4j "0.4.0"]
                 [hiccup "1.0.5"]]
  :test-paths ["spec"]
  :plugins [[speclj "3.1.0"]]
  :codox {:src-dir-uri               "https://github.com/AvisoNovate/twixt/blob/master/"
          :src-linenum-anchor-prefix "L"
          :defaults                  {:doc/format :markdown}}
  :profiles {:dev {:dependencies [[log4j "1.2.17"]
                                  [speclj "3.1.0"]
                                  [ring/ring-jetty-adapter "1.2.0"]]
                   :jvm-opts     ["-Xmx1g"]}})
