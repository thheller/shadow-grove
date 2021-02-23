(ns shadow.experiments.grove.collections-test
  (:require [clojure.test :as ct :refer (deftest is)]))

;; original collection vector of items
;; vector or array of key-fn(item)

(def old [1 2 3 4 5])
(def new [1 2 4 5]) ;; remove 3 in middle

(deftest dummy
  "foo")