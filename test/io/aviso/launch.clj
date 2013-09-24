(ns io.aviso.launch
  (use io.aviso.twixt 
       [io.aviso.twixt exceptions tracker]       
       ring.adapter.jetty))

(defn make-handler [twixt] 
  (fn [request]
    (trace "Invoking handler (that throws exceptions)"
           ;; This will fail at some depth:
           (get-asset-uri twixt "invalid-coffeescript.coffee"))))

(defn app []
  (let [twixt (new-twixt {:development-mode true})]
    (->
      (make-handler twixt)
      (wrap-with-twixt twixt)
      (wrap-with-exception-reporting twixt))))


(defn launch []
  (let [server (run-jetty (app) {:port 8888 :join? false})]
    #(.stop server)))