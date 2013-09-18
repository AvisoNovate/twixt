(ns io.aviso.twixt.exceptions
  "Support for generating pretty and useful HTML reports when server-side exceptions occur."
  (use hiccup.core
       hiccup.page
       io.aviso.twixt
       ring.util.response)
  (import [clojure.lang APersistentMap Sequential]
          [java.util Map])
  (require [clojure.tools.logging :as l]))

(defn- exception-message [exception]
  (or (.getMessage exception) 
      (-> exception .getClass .getName)))


(defprotocol MarkupGeneration
  (to-markup 
    [value]
    "Returns HTML markup representing the value."))

(extend-type nil
  MarkupGeneration
  (to-markup [_] "<em>nil</em>"))

(extend-type String
  MarkupGeneration
  (to-markup [value] (h value)))

(extend-type Object
  MarkupGeneration
  (to-markup [value] (-> value .toString h)))

(extend-type APersistentMap
  MarkupGeneration
  (to-markup 
    [m]
    (html
      (if (empty? m)
      [:em "empty map"]
      [:dl
        (apply concat
               (for [k (-> m keys sort)]
                 [[:dt (to-markup k)] [:dd (-> (get m k) to-markup)]]
                 ))
        ]))))

(extend-type Sequential
  MarkupGeneration
  (to-markup 
    [coll]
    (html
      (if (empty? coll)
        [:em "none"]
        [:ul
         (for [v coll] [:li (to-markup v)])
         ]))))

(extend-type Map
  MarkupGeneration
  (to-markup 
    [m]
    (html
      (if (.isEmpty m)
      [:em "empty map"]
      [:dl
        (apply concat
               (for [k (-> m .keySet sort)]
                 [[:dt (to-markup k)] [:dd (-> (.get m k) to-markup)]]
                 ))
        ]))))

(defn- exception->markup 
  "Given an analyzed exception, generate markup for it."
  [{:keys [class message properties stack-trace]}]
  (html
    [:div.panel.panel-default
     [:div.panel-heading
      [:h3.panel-title class]
      ]
     [:div.panel-body
      message
      ;; TODO: sorting
      (when-not (empty? properties)
        [:dl
         (apply concat           
                (for [k (-> properties keys sort)]
                  [[:dt (-> k name h)] [:dd (-> (get properties k) to-markup)]]))
         ])
      ]
     ]))

(defn- match-keys 
  "Apply the function f to all values in the map; where the result is truthy, add the key to the result."
  [m f]
  ;; (seq m) is necessary because the source is via (bean), which returns an odd implementation of map
  (reduce (fn [result [k v]] (if (f v) (conj result k) result)) [] (seq m)))

(defn- analyze-exception 
  [exception]
  (let [properties (bean exception)
        cause (:cause properties)
        nil-property-keys (match-keys properties nil?)
        throwable-property-keys (match-keys properties #(.isInstance Throwable %))
        discarded-keys (concat [:suppressed :message :localizedMessage :class :stackTrace]
                               nil-property-keys
                               throwable-property-keys)
        visual-properties (apply dissoc properties discarded-keys)
        message (:message properties)]
    {
     :class (-> properties :class .getName)
     :message (and message (to-markup message))
     :properties visual-properties
     :stack-trace (if (nil? cause) (-> properties :stackTrace seq))
     :cause cause
     }))

(defn write-exception-stack [root-exception]
  (loop [result []
         exception root-exception]
    (if exception
      (let [analyzed (analyze-exception exception)]
        (recur (conj result (exception->markup analyzed)) (:cause analyzed)))
      (apply str result))))

(defn build-report
  "Builds an HTML exception report (as a string)."
  [twixt exception]
  (html5
    [:head
     [:title "Exception"]
     (apply include-css (get-asset-uris twixt
                                        "bootstrap3/css/bootstrap.css"
                                        "twixt/exception.less"))]
    [:body
     [:div.container
      [:div.panel.panel-danger
       [:div.panel-heading
        [:h3.panel-title "An unexpected exception has occured."]]
       [:div.panel-body
        [:div.alert.alert-danger (h (exception-message exception))]
        ]
       ]
      (write-exception-stack exception)      
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