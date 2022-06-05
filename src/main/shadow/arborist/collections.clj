(ns shadow.arborist.collections
  (:require [clojure.spec.alpha :as s]))

;; FIXME: unused for now but at some point fragments should have native support for collections
;; render-seq is fine but inefficient due to the often inline render-fn creation which means
;; it can never short circuit rendering because it may have captured some other locals that are
;; relevant to render besides the actual coll item

(comment

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
           ~body)))))