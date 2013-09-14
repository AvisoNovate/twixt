(ns io.aviso.twixt.exceptions
  "Support for generating pretty and useful HTML reports when server-side exceptions occur."
  (use hiccup.core
       hiccup.page
       io.aviso.twixt
       ring.util.response)
  (require [clojure.tools.logging :as l]))

(defn- exception-message [exception]
  (or (.getMessage exception) 
      (-> exception .getClass .getName)))

(defn build-report
  "Builds an HTML exception report (as a string)."
  [twixt exception]
  (html5
    [:head
     [:title "Exception"]
     (apply include-css (get-asset-uris twixt
                                        "bootstrap3/css/bootstrap.css"))]
    [:body
     [:div.container
      [:div.panel.panel-danger
       [:div.panel-heading
        [:h3.panel-title "An unexpected exception has occured."]]
       [:div.panel-body
        [:div.alert.alert-danger (h (exception-message exception))]
        ]
       ]
      ]
     ]))

#_ (defn spy [value label]
  (l/infof "spy %s: %s" label value)
  value) 

(defn wrap-with-exception-reporting 
  "Wraps the handler to report any uncaught exceptions as an HTML exception report.
  
  handler - handler to wrap
  twixt - initialized instance of Twixt"
  [handler twixt]
  (fn exception-catching-handler [request]
    (try
      (handler request)
      (catch Throwable t
        ;; TODO: logging!
        (->          
          (build-report twixt t)
          #_ (spy "report")
          response
          (content-type "text/html")
          (status 500)
          #_ (spy "response")
          )))))