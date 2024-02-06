(ns shadow.grove.runtime)

(defonce known-runtimes-ref (atom {}))

(defn ref? [x]
  (and (atom x)
       (::rt @x)))

(defonce id-seq (volatile! 0))

(defn next-id []
  (vswap! id-seq inc))

(defonce ticker (js/Promise.resolve nil))

(defn next-tick [callback]
  (.then ticker callback))
