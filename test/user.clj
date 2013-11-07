(ns user
  (:use io.aviso.twixt
        [io.aviso.twixt exceptions tracker]
        ring.adapter.jetty))

(defn handler
  [request]
  (trace "Invoking handler (that throws exceptions)"
         ;; This will fail at some depth:
         (get-asset-uris (:twixt request) "invalid-coffeescript.coffee")))

(def app
  (default-twixt-handler handler default-options true))


(defn launch []
  (let [server (run-jetty (app) {:port 8888 :join? false})]
    #(.stop server)))