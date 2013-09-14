(ns io.aviso.launch
  (use io.aviso.twixt 
       io.aviso.twixt.exceptions
       ring.adapter.jetty))

(defn handler [request]
  (throw (RuntimeException. "Exception inside handler!")))

(defn app []
  (let [twixt (new-twixt {:development-mode true})]
    (->
      handler
      (wrap-with-twixt twixt)
      (wrap-with-exception-reporting twixt))))


(defn launch []
  (run-jetty (app) {:port 8888 :join? false}))