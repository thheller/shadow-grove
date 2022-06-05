(ns shadow.arborist.dom-scheduler
  (:refer-clojure :exclude #{read}))

(defmacro read! [& body]
  `(read!!
     (fn []
       ~@body)))

(defmacro write! [& body]
  `(write!!
     (fn []
       ~@body)))

(defmacro after! [& body]
  `(after!!
     (fn []
       ~@body)))

