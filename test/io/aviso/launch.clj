(ns io.aviso.launch
  (use io.aviso.twixt 
       [io.aviso.twixt exceptions tracker]       
       ring.adapter.jetty)
  (import [java.sql SQLException]))

(defn handler [request]
  (trace "Invoking handler (that throws exceptions)"
         (throw 
           (->>
             (SQLException. "Inner Exception" "SQL-STATE", 999)
             (RuntimeException. "Middle Exception")
             (IllegalArgumentException. "Outer Exception")))))

(defn app []
  (let [twixt (new-twixt {:development-mode true})]
    (->
      handler
      (wrap-with-twixt twixt)
      (wrap-with-exception-reporting twixt))))


(defn launch []
  (let [server (run-jetty (app) {:port 8888 :join? false})]
    #(.stop server)))