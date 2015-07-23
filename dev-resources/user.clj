(ns user
  (:use speclj.config
        io.aviso.repl
        clojure.pprint
        io.aviso.exception))

(install-pretty-exceptions)

(alter-var-root #'*default-frame-rules*
                conj
                [:package "speclj" :terminate])

(alter-var-root #'default-config assoc :color true :reporters ["documentation"])

