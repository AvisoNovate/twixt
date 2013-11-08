(ns io.aviso.twixt.exceptions
  "Support for generating pretty and useful HTML reports when server-side exceptions occur."
  (:use hiccup.core
        hiccup.page
        io.aviso.twixt
        ring.util.response)
  (:import [clojure.lang APersistentMap Sequential]
           [java.util Map])
  (:require [clojure.tools.logging :as l]
            [clojure.string :as s]
            [io.aviso.exception :as exception]))

(defn- exception-message [exception]
  (or (.getMessage exception)
      (-> exception .getClass .getName)))

(defprotocol MarkupGeneration
  "Used to convert arbitrary values into markup strings.

  Extended onto nil, String, APersistentMap, Sequential and Object."
  (to-markup
    [value]
    "Returns HTML markup representing the value."))

(extend-type nil
  MarkupGeneration
  (to-markup [_] "<em>nil</em>"))
h
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

(defn- seq->markup [coll]
  ;; Since *print-length* is normally nil, we provide an actual limit
  (let [limit (or *print-length* 10)
        values (take (inc limit) coll)
        trimmed (if (<= (count values) limit)
                  values
                  (concat
                    (take limit values)
                    ["..."]))]
    (for [v trimmed]
      [:li (to-markup v)])))

(extend-type Sequential
  MarkupGeneration
  (to-markup
      [coll]
    (html
      (if (empty? coll)
        [:em "none"]
        [:ul (seq->markup coll)]))))

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

(defn- element->clojure-name [element]
  (let [names (:names element)]
    (list
      (s/join "/" (drop-last names))
      "/"
      [:strong (last names)])))

(defn- element->java-name
  [{:keys [package class simple-class method]}]
  (list
    (if package
      (list
        [:span.package-name package]
        "."
        simple-class)
      class)
    " &mdash; "
    method))

(defn- stack-trace-element->row-markup
  [{:keys [file line names]
    :as   element}]
  (let [clojure? (-> names empty? not)
        java-name (element->java-name element)]
    [:tr
     [:td.function-name
      (if clojure?
        (list
          (element->clojure-name element)
          [:div.filtered java-name])
        java-name)
      ]
     [:td.source-location file]
     [:td (or line "")]
     ]))

(defn- single-exception->markup
  "Given an analyzed exception, generate markup for it."
  [{^Throwable e :exception
    :keys        [class-name message properties root]}]
  (html
    [:div.panel.panel-default
     [:div.panel-heading
      [:h3.panel-title class-name]
      ]
     [:div.panel-body
      [:h4 message]
      ;; TODO: sorting
      (when-not (empty? properties)
        [:dl
         (apply concat
                (for [k (-> properties keys sort)]
                  [[:dt (-> k name h)] [:dd (-> (get properties k) to-markup)]]))
         ])]
     (if root
       (list
         [:div.btn-toolbar.spacing-below
          [:button.btn.btn-default.btn-sm {:data-action :toggle-filter} "Toggle Stack Frame Filter"]]
         [:table.table.table-hover.table-condensed.table-striped
          (->>
            e
            exception/expand-stack-trace
            (map stack-trace-element->row-markup))
          ]))
     ]))


(defn exception->markup
  "Returns the markup (as a string) for a root exception and its stack of causes,
  including a stack trace for the deepest exception."
  [root-exception]
  (->>
    root-exception
    exception/analyze-exception
    (map single-exception->markup)
    (apply str)))

(defn build-report
  "Builds an HTML exception report (as a string).
  
  request - Ring request map, which must contain the :twixt key.
  exception - Exception to report"
  [request exception]
  (let [twixt (:twixt request)]
    (html5
      [:head
       [:title "Exception"]
       (apply include-css (get-asset-uris twixt
                                          "bootstrap3/css/bootstrap.css"
                                          "twixt/exception.less"))]
      [:body.hide-filtered
       [:div.container
        [:div.panel.panel-danger
         [:div.panel-heading
          [:h3.panel-title "An unexpected exception has occurred."]]
         [:div.panel-body
          [:h3 (h (exception-message exception))]
          ]
         ]
        (exception->markup exception)

        [:h3 "Request"]

        (to-markup request)

        [:h3 "System Properties"]
        (to-markup (System/getProperties))
        ]
       (apply include-js (get-asset-uris twixt
                                         "bootstrap3/js/jquery-2.0.3.js"
                                         "twixt/exception.coffee"))
       ])))

(defn wrap-with-exception-reporting
  "Wraps the handler to report any uncaught exceptions as an HTML exception report.  This wrapper
  should wrap around other handlers (including the Twixt handler itself), but be nested within
  the twixt-setup handler (which provides the :twixt request map key).
  
  handler - handler to wrap
  twixt - initialized instance of Twixt"
  [handler]
  (fn exception-catching-handler [request]
    (try
      (handler request)
      (catch Throwable t
        (->
          (build-report request t)
          response
          (content-type "text/html")
          (status 500))))))

(defn wrap-with-twixt
  "The default way to setup Twixt with exception reporting.

  With just a handler, uses the default Twixt options and production mode.

  Otherwise, specify alternate options and true or false for development mode."
  ([handler]
   (wrap-with-twixt handler default-options false))
  ([handler twixt-options development-mode]
   (let [asset-pipeline (default-asset-pipeline twixt-options development-mode)]
     (->
       handler
       wrap-with-twixt
       wrap-with-exception-reporting
       (wrap-with-twixt-setup twixt-options asset-pipeline)))))