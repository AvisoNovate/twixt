(ns user
  (:use
    speclj.config
    io.aviso.tracker
    io.aviso.repl
    clojure.pprint
    [io.aviso.twixt :only [get-asset-uris default-options]]
    io.aviso.twixt.startup
    ring.adapter.jetty)
  #_ (:require
    ;; See https://github.com/slagyr/speclj/issues/79
    speclj.run.standard))

(install-pretty-exceptions)

(alter-var-root #'default-config assoc :color true :reporters ["documentation"])

(defn handler
  [request]
  (track "Invoking handler (that throws exceptions)"
         (if (= (:uri request) "/fail")
           ;; This will fail at some depth:
           (doall
             (get-asset-uris (:twixt request) "invalid-coffeescript.coffee")))))

(defn app
  []
  (wrap-with-twixt handler default-options true))

(defn launch []
  (let [server (run-jetty (app) {:port 8888 :join? false})]
    #(.stop server)))