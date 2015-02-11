(ns user
  (:use speclj.config
        io.aviso.tracker
        io.aviso.repl
        clojure.pprint
        [io.aviso.twixt :only [get-asset-uris default-options]]
        io.aviso.exception
        io.aviso.twixt.startup
        ring.adapter.jetty)
  ;; See https://github.com/slagyr/speclj/issues/79
  (:require speclj.run.standard))

(install-pretty-exceptions)

(alter-var-root #'*default-frame-filter*
                (fn [default-frame-filter]
                  (fn [frame]
                    (if (= (:package frame) "speclj")
                      :terminate
                      (default-frame-filter frame)))))

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