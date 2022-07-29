(ns shadow.grove.events
  "re-frame style event handling"
  (:require-macros [shadow.grove.events])
  (:require
    [clojure.set :as set]
    [shadow.grove.components :as comp]
    [shadow.grove.runtime :as rt]
    [shadow.grove.protocols :as gp]
    [shadow.grove.db :as db]
    [shadow.grove :as sg]))

(defn queue-fx [env fx-id fx-val]
  (sg/queue-fx env fx-id fx-val))

(defn reg-event [app-ref ev-id handler-fn]
  (sg/reg-event app-ref ev-id handler-fn))

(defn reg-fx [app-ref fx-id handler-fn]
  (sg/reg-fx app-ref fx-id handler-fn))
