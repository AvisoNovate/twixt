(ns user
  (:use speclj.config
        clojure.pprint)
  (:require [schema.core :as s]))

(alter-var-root #'default-config assoc :color true :reporters ["documentation"])

(s/set-fn-validation! true)