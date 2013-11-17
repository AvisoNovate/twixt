(ns io.aviso.twixt.css-rewrite
  "Defines an asset pipeline filter used to rewrite URLs in CSS files. Relative URLs in CSS
  files will become invalid due to the inclusion of the individual asset's checksum in the
  asset request path."
  (:require [clojure.string :as str]
            [io.aviso.twixt
             [asset :as asset]
             [tracker :as t]
             [utils :as utils]]))

(def ^:private url-pattern #"url\(\s*(['\"]?)(.+?)([\#\?].*?)?\1\s*\)")
(def ^:private complete-url-pattern #"^[#/]|(\p{Alpha}\w*:)")

(defn- rewrite-relative-url
  [asset-path context relative-url]
  (t/trace
    #("Rewriting relative URL `%s'" relative-url)
    (let [referenced-path (utils/compute-relative-path asset-path relative-url)
          asset ((:asset-pipeline context) referenced-path context)]
      (if asset
        (asset/asset->request-path (:path-prefix context) asset)
        (throw (ex-info
                 (format "Unable to locate asset `%s'." referenced-path)
                 {:source-asset asset-path
                  :relative-url relative-url}))))))

(defn- css-url-match-handler [asset-path context match]
  (let [url (nth match 2)
        parameters (nth match 3)]
    (str "url(\""
      (if (re-matches complete-url-pattern url)
        (str url parameters)
        (str (rewrite-relative-url asset-path context url)
             parameters))
      "\")")))

(defn- rewrite-css
  [asset context]
  (t/trace
    #(format "Rewriting URLs in `%s'" (:asset-path asset))
    (let [content (utils/as-string (:content asset))
          content' (str/replace content
                                url-pattern
                                (partial css-url-match-handler (:asset-path asset) context))]
      (utils/replace-asset-content asset "text/css" (utils/as-bytes content')))))

(defn wrap-with-css-rewriting
  [handler]
  (fn css-rewriter [asset-path context]
    (let [asset (handler asset-path context)]
      (if (= "text/css" (:content-type asset))
        (rewrite-css asset context)
        asset))))
