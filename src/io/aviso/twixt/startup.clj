(ns io.aviso.twixt.startup
  "Breaks out the default logic for initializing Twixt's handlers and middleware."
  (:require
    [io.aviso.twixt :as t]
    [io.aviso.twixt
     [coffee-script :as cs]
     [compress :as compress]
     [exceptions :as te]
     [export :as export]
     [jade :as jade]
     [less :as less]
     [ring :as ring]
     [stacks :as stacks]]))

(defn wrap-with-twixt
  "The default way to setup Twixt, with exception reporting.
  This (currently) enables support for CoffeeScript, Less, and Jade, and Stacks.

  The provided Ring request handler is wrapped in the following stack (outermost to innermost):

  - twixt setup (adds `:twixt` key to the request)
  - exception reporting
  - compression analyzer (does the client support GZip encoding?)
  - asset export logic (exports certain assets to file system when changed
  - asset request handling
  - the provided handler

  With just a handler, uses the default Twixt options and production mode.

  The two argument version is used to set development-mode, but use default options.

  Otherwise, provide the handler, alternate options and true or false for development mode.
  The alternate options are merged with defaults and override them."
  ([handler]
   (wrap-with-twixt handler false))
  ([handler development-mode]
   (wrap-with-twixt handler t/default-options development-mode))
  ([handler twixt-options development-mode]
   (let [twixt-options' (-> (merge t/default-options twixt-options)
                            (assoc :development-mode development-mode)
                            te/register-exception-reporting
                            cs/register-coffee-script
                            jade/register-jade
                            less/register-less
                            stacks/register-stacks)
         asset-pipeline (t/default-asset-pipeline twixt-options')]
     (->
       handler
       ring/wrap-with-twixt
       (export/wrap-with-exporter (:exports twixt-options'))
       te/wrap-with-exception-reporting
       compress/wrap-with-compression-analyzer
       (ring/wrap-with-twixt-setup twixt-options' asset-pipeline)))))