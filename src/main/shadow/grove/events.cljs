(ns shadow.grove.events
  "re-frame style event handling"
  (:require-macros [shadow.grove.events])
  (:require
    [shadow.grove :as sg]))

;; moved into the shadow.grove main ns
;; FIXME: figure out what to do with the macro stuff

(defn queue-fx [env fx-id fx-val]
  (sg/queue-fx env fx-id fx-val))

(defn reg-event [app-ref ev-id handler-fn]
  (sg/reg-event app-ref ev-id handler-fn))

(defn reg-fx [app-ref fx-id handler-fn]
  (sg/reg-fx app-ref fx-id handler-fn))
