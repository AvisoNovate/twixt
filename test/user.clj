(ns user
  (:use io.aviso.twixt
        [io.aviso.twixt exceptions tracker]
        ring.adapter.jetty)
  (:require [clojure.tools.logging :as l]))

(defn handler
  [request]
  (trace "Invoking handler (that throws exceptions)"
         ;; This will fail at some depth:
         (l/info (get-asset-uris (:twixt request) "invalid-coffeescript.coffee"))))

(def app
  (wrap-with-twixt handler default-options true))

(defn launch []
  (let [server (run-jetty app {:port 8888 :join? false})]
    #(.stop server)))