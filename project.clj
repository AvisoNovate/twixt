(defproject io.aviso/twixt "0.1.3"
  :description "An extensible asset pipeline for Clojure web applications"
  :url "https://github.com/AvisoNovate/twixt"
  :license {:name "Apache Sofware Licencse 2.0"
            :url "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [org.clojure/tools.logging "0.2.6"]
                 [ring/ring-core "1.2.0"]
                 [org.mozilla/rhino "1.7R4"]
                 [com.github.sommeri/less4j "1.1.2"]
                 [de.neuland/jade4j "0.3.15"]
                 [io.aviso/pretty "0.1.3"]
                 [hiccup "1.0.4"]]
  :repositories [["jade4j" "https://raw.github.com/neuland/jade4j/master/releases"]]
  :repl-options {
                 :init-ns io.aviso.launch
                 :port 4001}
  ;; The Sublime Text nREPL plugin is out of date, so...
  :repl-port 4001
  :profiles {:dev {:dependencies [[log4j "1.2.17"]
                                  [ring/ring-jetty-adapter "1.2.0"]]}})
