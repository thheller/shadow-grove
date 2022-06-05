(ns shadow.grove.others.react
  (:require [shadow.grove.builder :as build])
  (:import [cljs.tagged_literals JSValue]))

(defmacro << [& body]
  `(build/with (shadow.grove.react/ElementBuilder. nil ~(not= 1 (count body)))
     ~(build/compile {:skip-check true} &env body)))

;; only a macro to optimize the props handling (avoid allocating clojure maps)
;; FIXME: should probably validate that attrs are only simple keywords
(defmacro js
  ([type {:keys [ref key] :as attrs}]
   `(js-el*
      ~type
      ~(-> attrs
           (dissoc :key :ref)
           (JSValue.))
      ~key
      ~ref))
  ([type {:keys [ref key] :as attrs} child-expr]
   (when (vector? child-expr)
     (throw (ex-info "invalid child-expr, elements need to be wrapped in $" {:child-expr child-expr})))

   `(shadow.grove.react/js-el*
      ~type
      ~(-> attrs
           (dissoc :key :ref)
           (assoc :children `(shadow.grove.react/unwrap-fragment ~child-expr))
           (JSValue.))
      ~key
      ~ref)))