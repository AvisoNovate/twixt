(ns io.aviso.twixt.exceptions
  "Support for generating pretty and useful HTML reports when server-side exceptions occur."
  (:use
    hiccup.core
    hiccup.page
    ring.util.response)
  (:import
    [clojure.lang APersistentMap Sequential PersistentHashMap$ArrayNode$Seq PersistentHashMap]
    [java.util Map]
    [java.util.regex Pattern])
  (:require
    [clojure.string :as s]
    [io.aviso
     [repl :as repl]
     [exception :as exception]
     [twixt :as t]]))

(def ^:private path-separator (System/getProperty "path.separator"))

(defn- exception-message
  [^Throwable exception]
  (or (.getMessage exception)
      (-> exception .getClass .getName)))

(defprotocol MarkupGeneration
  "Used to convert arbitrary values into markup strings.

  Extended onto nil, `String`, `APersistentMap`, `Sequential` and `Object`."
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
                (for [[k v] (sort-by str m)]
                  [[:dt (to-markup k)] [:dd (to-markup v)]]
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
    [^Map m]
    (to-markup (PersistentHashMap/create m))))

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

(defn- apply-stack-frame-filter
  [filter frames]
  (loop [result []
         [frame & more-frames] frames]
    (if (nil? frame)
      result
      (case (filter frame)
        :show
        (recur (conj result frame) more-frames)

        ;; We could perhaps do this differntly, but unlike console output, we treat these the same.
        (:hide :omit)
        (recur (conj result (assoc frame :filtered true)) more-frames)

        :terminate
        result))))

(defn- stack-frame->row-markup
  [{:keys [file line names]
    :as   frame}]
  (let [clojure? (-> names empty? not)
        java-name (element->java-name frame)]
    [:tr (if (:filtered frame) {:class :filtered} {})
     [:td.function-name
      (if clojure?
        (list
          (element->clojure-name frame)
          [:div.filtered java-name])
        java-name)
      ]
     [:td.source-location file (if line ":")]
     [:td (or line "")]
     ]))

(defn- single-exception->markup
  "Given an analyzed exception, generate markup for it."
  [twixt
   {^Throwable e :exception
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
                (for [[k v] (sort-by str properties)]
                  [[:dt (-> k h)] [:dd (to-markup v)]]))
         ])]
     (if root
       (list
         [:div.btn-toolbar.spacing-below
          [:button.btn.btn-default.btn-sm {:data-action :toggle-filter} "Toggle Stack Frame Filter"]]
         [:table.table.table-hover.table-condensed.table-striped
          (->>
            e
            exception/expand-stack-trace
            (apply-stack-frame-filter (:stack-frame-filter twixt))
            (map stack-frame->row-markup))
          ]))
     ]))


(defn exception->markup
  "Returns the markup (as a string) for a root exception and its stack of causes,
  including a stack trace for the deepest exception."
  [twixt root-exception]
  (->>
    root-exception
    exception/analyze-exception
    (map (partial single-exception->markup twixt))
    (apply str)))

(defn- is-path?
  [^String k ^String v]
  (and (.endsWith k ".path")
       (.contains v path-separator)))

(defn- split-string [^String s ^String sep]
  (s/split s (-> sep Pattern/quote Pattern/compile)))

(defn- path->markup [^String path]
  `[:ul
    ~@(for [v (.split path path-separator)]
        [:li v])])

(defn- sysproperties->markup
  []
  (let [props (System/getProperties)]
    [:dl
     (apply concat
            (for [k (-> props keys sort)
                  :let [v (.get props k)]]
              [[:dt k]
               [:dd (if (is-path? k v) (path->markup v) v)]]))]))

(defn build-report
  "Builds an HTML exception report (as a string).
  
  request
  : Ring request map, which must contain the `:twixt` key

  exception
  : instance of Throwable to report"
  [request exception]
  (let [twixt (:twixt request)]
    (html5
      [:head
       [:title "Exception"]
       (apply include-css (t/get-asset-uris twixt
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
        (exception->markup twixt exception)

        [:h3 "Request"]

        (to-markup request)

        [:h3 "System Properties"]
        (sysproperties->markup)
        ]
       (apply include-js (t/get-asset-uris twixt
                                           "bootstrap3/js/jquery.js"
                                           "twixt/exception.coffee"))
       ])))

(defn wrap-with-exception-reporting
  "Wraps the handler to report any uncaught exceptions as an HTML exception report.
  This wrapper should wrap around other handlers (including the Twixt handler itself), but be nested within
  the twixt-setup handler (which provides the `:twixt` request map key)."
  [handler]
  (fn [request]
    (try
      (handler request)
      (catch Throwable t
        (->
          (build-report request t)
          response
          (content-type "text/html")
          (status 500))))))


(defn default-stack-frame-filter
  "The default stack frame filter function, used by the HTML excepton report to identify frames that can be hidden
  by default.

  This implementation extends the standard frame filter (`io.aviso.repl/standard-frame-filter`),
  to also omit frames with no line number.

  The HTML exception report treats `:omit` and `:hide` identically; frames marked as either are
  initially hidden, but can be revealed in the client."
  [frame]
  (cond
    (nil? (:line frame)) :omit
    :else (repl/standard-frame-filter frame)))

(defn register-exception-reporting
  "Must be invoked to configure the Twixt options with the default `:stack-frame-filter` key, [[default-stack-frame-filter]]."
  [options]
  (assoc options :stack-frame-filter default-stack-frame-filter))

