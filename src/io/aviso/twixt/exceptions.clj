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

(defn- element->clojure-name [element]
  (let [names (:names element)]
    (list
      (s/join "/" (butlast names))
      "/"
      [:strong (last names)])))

(defn- element->java-name [element]
  (let [class-name (:class element)
        dotx (.lastIndexOf class-name ".")]
    (list
      (if (pos? dotx)
        (list
          ;; Take up to and including the "dot"
          [:span.package-name (.substring class-name 0 (inc dotx))]
          (.substring class-name (inc dotx)))
        class-name)
      " &mdash; "
      (:method element))))

(defn- stack-trace-element->row-markup
  [element]
  (let [clojure? (-> element :names empty? not)
        class-name (:class element)
        java-name (element->java-name element)
        ^String line (:line element)]
    [:tr
     [:td.function-name
      (if clojure?
        (list
          (element->clojure-name element)
          [:div.filtered java-name])
        java-name)
      ]
     [:td.source-location (:file element)]
     [:td (if (and (-> line s/blank? not)
                   (-> line (Integer/parseInt) pos?))
            line
            "")]
     ]))

(defn- exception->markup
  "Given an analyzed exception, generate markup for it."
  [{^Throwable e :exception
    :keys        [properties root]}]
  (html
    [:div.panel.panel-default
     [:div.panel-heading
      [:h3.panel-title (-> e .getClass .getName)]
      ]
     [:div.panel-body
      [:h4 (.getMessage e)]
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


(defn write-exception-stack
  "Writes the markup for a root exception and its stack of causes, including a stack trace for the deepest exception."
  [^Throwable root-exception]
  (->>
    root-exception
    exception/analyze-exception
    (map exception->markup)
    (apply str)))

(defn build-report
  "Builds an HTML exception report (as a string).
  
  twixt - Twixt instance, used to resolve client URIs for assets
  request - Ring request map
  exception - Exception to report"
  [twixt request exception]
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
      (write-exception-stack exception)

      [:h3 "Request"]

      (to-markup request)

      [:h3 "System Properties"]
      (to-markup (System/getProperties))
      ]
     (apply include-js (get-asset-uris twixt
                                       "bootstrap3/js/jquery-2.0.3.js"
                                       "twixt/exception.coffee"))
     ]))

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
          (build-report twixt request t)
          response
          (content-type "text/html")
          (status 500))))))