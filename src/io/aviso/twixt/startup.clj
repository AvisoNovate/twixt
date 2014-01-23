(ns io.aviso.twixt.startup
  "Breaks out the default logic for initializing Twixt's handlers and middleware."
  (:require [io.aviso.twixt :as t]
            [io.aviso.twixt
             [compress :as compress]
             [exceptions :as te]]))

(defn wrap-with-twixt
  "The default way to setup Twixt, with exception reporting. This wires up
  the following stack:
  - twixt setup (adds :twixt key to the request)
  - exception reporting
  - compression analyzer (does the client support GZip encoding?)
  - asset request handling
  - the provided handler

  With just a handler, uses the default Twixt options and production mode.

  The two argument version is used to set development-mode, but use default options.

  Otherwise, provide the handler, alternate options and true or false for development mode."
  ([handler]
   (wrap-with-twixt handler false))
  ([handler development-mode]
   (wrap-with-twixt handler t/default-options development-mode))
  ([handler twixt-options development-mode]
   (let [asset-pipeline (t/default-asset-pipeline twixt-options development-mode)]
     (->
       handler
       t/wrap-with-twixt
       te/wrap-with-exception-reporting
       compress/wrap-with-compression-analyzer
       (t/wrap-with-twixt-setup twixt-options asset-pipeline)))))