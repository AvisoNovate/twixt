(ns example
  (:use ring.adapter.jetty
        io.aviso.twixt.startup)
  (:require [io.aviso.tracker :as t]
            [io.aviso.twixt :refer [get-asset-uris default-options]]))

(defn handler
  [request]
  (t/track "Invoking handler (that throws exceptions)"
           (if (= (:uri request) "/fail")
             ;; This will fail at some depth:
             (doall
               (get-asset-uris (:twixt request) "invalid-coffeescript.coffee")))))

(defn app
  []
  (wrap-with-twixt handler (assoc-in default-options [:cache :cache-dir] "target/twixt-cache") false))

(defn launch
  []
  (run-jetty (app) {:port 8888 :join? false}))

(defn shutdown
  [server]
  (.stop server))