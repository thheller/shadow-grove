(ns shadow.grove.devtools.env
  (:require
    [shadow.grove :as sg]
    [shadow.grove.db :as db]
    [shadow.grove.devtools :as-alias m]))

(def schema
  {::m/runtime
   {:type :entity
    :primary-key :client-id
    :attrs {}}})

(defonce data-ref
  (-> {::m/selected #{}}
      (db/configure schema)
      (atom)))

(defonce rt-ref
  (-> {}
      (sg/prepare data-ref ::ui)))

