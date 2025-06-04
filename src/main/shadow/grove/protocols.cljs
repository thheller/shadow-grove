(ns shadow.grove.protocols)

(defprotocol IWork
  (work! [this]))

;; anything wanted to update the DOM must go through this
;; schedule queues a microtask that will work from the root
;; this is to ensure child components dont do work if they get unmounted by parent updating
(defprotocol IScheduleWork
  (schedule-work! [this task trigger])
  (unschedule! [this task])

  ;; FIXME: this is purely a UI concern, should most likely be a separate interface
  ;; this ns is meant to be usable in a worker environment which is not concerned with suspense
  ;; for now suspense is a hack anyways so need to sort that out more
  (did-suspend! [this target])
  (did-finish! [this target]))

(defprotocol IHandleEvents
  ;; e and origin can be considered optional and will be ignored by most actual handlers
  (handle-event! [this ev-map e origin]))

(defprotocol IEnvSource
  (get-component-env [this]))

(defprotocol ISchedulerSource
  (get-scheduler [this]))

(defprotocol IInvalidateSlot
  (invalidate! [ref] "invalidates slot and causes it to execute again")
  ;; FIXME: there is a substantial problem with this. providing a value is nice, but the result of the call of the `bind` might not represent the final value
  ;; (bind something (count (sg/query ?some-query)))
  ;; so if query decides to provide a new value it would just return the query result, instead of its count
  ;; maybe need to rethink this whole defc system
  (provide-new-value! [ref new-value] "invalidates slot, but only causes dependents to run again, not itself"))

(defprotocol IProvideSlot
  (-invalidate-slot! [this idx])
  (-provide-new-value! [this idx new-value])
  (-init-slot-ref [this idx]))

;; just here so that working on components file doesn't cause hot-reload issues
;; with already constructed components
(deftype ComponentConfig
  [component-name
   slots
   slot-init-bits
   opts
   check-args-fn
   render-deps
   render-fn
   events
   debug-info])
