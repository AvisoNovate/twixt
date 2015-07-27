(ns io.aviso.twixt.css-rewrite
  "Defines an asset pipeline filter used to rewrite URIs in CSS files.
  Relative URLs in CSS files will become invalid due to the inclusion of the individual asset's checksum in the
  asset request path; instead, the relative URIs are expanded to complete URIs that include the individual asset's checksum."
  (:require [clojure.string :as str]
            [io.aviso.tracker :as t]
            [io.aviso.twixt
             [asset :as asset]
             [utils :as utils]
             [schemas :refer [AssetHandler]]]
            [schema.core :as s]))

(def ^:private url-pattern #"url\(\s*(['\"]?)(.+?)([\#\?].*?)?\1\s*\)")
(def ^:private complete-url-pattern #"^[#/]|(\p{Alpha}\w*:)")

(defn- rewrite-relative-url
  [asset-path context dependencies ^String relative-url]
  (if (.startsWith relative-url "data:")
    relative-url
    (t/track
      #(format "Rewriting relative URL `%s'" relative-url)
      (let [referenced-path (utils/compute-relative-path asset-path relative-url)
            asset ((:asset-pipeline context) referenced-path context)]
        (if-not asset
          (throw (ex-info
                   (format "Unable to locate asset `%s'." referenced-path)
                   {:source-asset asset-path
                    :relative-url relative-url})))

        (swap! dependencies utils/add-asset-as-dependency asset)
        (asset/asset->request-path (:path-prefix context) asset)))))

(defn- css-url-match-handler [asset-path context dependencies match]
  (let [url (nth match 2)
        parameters (nth match 3)]
    (str "url(\""
         (if (re-matches complete-url-pattern url)
           (str url parameters)
           (str (rewrite-relative-url asset-path context dependencies url)
                parameters))
         "\")")))

(defn- rewrite-css
  [asset context]
  (t/track
    #(format "Rewriting URLs in `%s'" (:asset-path asset))
    (let [content (utils/as-string (:content asset))
          ;; Using an atom this way is clumsy.
          dependencies (atom (:dependencies asset))
          content' (str/replace content
                                url-pattern
                                (partial css-url-match-handler (:asset-path asset) context dependencies))]
      (->
        asset
        (utils/replace-asset-content "text/css" (utils/as-bytes content'))
        (assoc :dependencies @dependencies)))))

(s/defn wrap-with-css-rewriting :- AssetHandler
  "Wraps the asset handler with the CSS URI rewriting logic needed for the client to be able to properly request the referenced assets.

  Rewriting occurs for individual CSS assets (including those created by compiling a Less source). It does not occur for
  aggregated CSS assets (since the individual assets will already have had URIs rewritten)."
  [handler :- AssetHandler]
  (fn [asset-path context]
    (let [asset (handler asset-path context)]
      (if (and
            (= "text/css" (:content-type asset))
            (-> asset :aggregate-asset-paths empty?))
        (rewrite-css asset context)
        asset))))
