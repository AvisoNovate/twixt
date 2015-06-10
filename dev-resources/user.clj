(ns user
  (:use speclj.config
        io.aviso.tracker
        io.aviso.repl
        clojure.pprint
        [io.aviso.twixt :only [get-asset-uris default-options]]
        io.aviso.exception
        io.aviso.twixt.startup
        ring.adapter.jetty))

(install-pretty-exceptions)

(alter-var-root #'*default-frame-rules*
                conj
                [:package "speclj" :terminate])

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

(defn launch
  []
  (run-jetty (app) {:port 8888 :join? false}))

(defn shutdown
  [server]
  (.stop server))