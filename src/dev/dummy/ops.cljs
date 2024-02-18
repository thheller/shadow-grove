(ns dummy.ops
  (:require [shadow.grove.operator :as op]))


(defn &foo [op opts]

  (op/handle op :foo-event!
    (fn [ev]
      (js/console.log "foo got event" op ev)
      ))

  (reset! op {:foo 0}))