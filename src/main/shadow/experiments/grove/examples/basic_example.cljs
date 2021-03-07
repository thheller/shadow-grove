(ns shadow.experiments.grove.examples.basic-example
  (:require
    [shadow.experiments.grove :as sg :refer (defc <<)]))

(defc dummy-example [foo]
  (render
    (<< [:div "hello " foo])))

(defn init []
  (dummy-example "world"))

