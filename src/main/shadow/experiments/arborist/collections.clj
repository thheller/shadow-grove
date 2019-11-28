(ns shadow.experiments.arborist.collections
  (:require [clojure.spec.alpha :as s]))

(s/def ::coll-args
  (s/cat
    :items any?
    :key-fn any?
    :bindings vector? ;; FIXME: core.specs for destructure help
    :body any?))

(s/fdef defc :coll ::defc-args)

(defmacro coll [& args]
  (let [{:keys [items key-fn bindings body]}
        (s/conform ::coll-args args)]

    `(node
       ~items
       ~key-fn
       ;; FIXME: is it necessary 
       (fn ~bindings
         ~body))))