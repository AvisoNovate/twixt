(ns io.aviso.twixt.asset
  "Utilities for dealing with a Twixt asset map.")

(defn asset->request-path
  "Computes the complete asset path that can be referenced by a client in order to obtain
  the asset content. This includes the path prefix, the checksum, and the asset path itself. Compressed assets
  have their checksum prefixed with 'z'."
  [path-prefix asset]
  (str path-prefix
       (if (:compressed asset) "z" "")
       (:checksum asset)
       "/"
       (:asset-path asset)))

