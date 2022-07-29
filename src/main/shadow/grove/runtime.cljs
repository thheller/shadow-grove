(ns shadow.grove.runtime
  (:require
    [goog.async.nextTick]
    [shadow.grove.protocols :as gp]))

(defonce known-runtimes-ref (atom {}))

(defn ref? [x]
  (and (atom x)
       (::rt @x)))

(defonce id-seq (atom 0))

(defn next-id []
  (swap! id-seq inc))

(defn next-tick [callback]
  ;; FIXME: should be smarter about when/where to schedule
  (js/goog.async.nextTick callback))
