(ns user
  (:use
    io.aviso.tracker
    io.aviso.repl
    [io.aviso.twixt :only [get-asset-uris default-options]]
    io.aviso.twixt.startup
    ring.adapter.jetty))

(install-pretty-exceptions)

(defn handler
  [request]
  (track "Invoking handler (that throws exceptions)"
         (if (= (:uri request) "/fail")
           ;; This will fail at some depth:
           (doall
             (get-asset-uris (:twixt request) "invalid-coffeescript.coffee")))))

(def app
  (wrap-with-twixt handler default-options true))

(defn launch []
  (let [server (run-jetty app {:port 8888 :join? false})]
    #(.stop server)))