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
  (invalidate! [ref] "invalidates slot and causes it to execute again"))

(defprotocol IProvideSlot
  (-invalidate-slot! [this idx])
  (-init-slot-ref [this idx]))

;; (ref name) or (ref name init) in defc
;; init is evaluated once, when the component is constructed. (ref name) uses (volatile! nil),
;; same as sg/ref, for :dom/ref. if the init value implements IRefInit, -ref-init is called
;; with the component env and the name is bound to what it returns instead.
;; values not implementing it are used as is (atoms, volatiles, ...)
(defprotocol IRefInit
  (-ref-init [this env]))

;; ref values implementing this are destroyed with the component
;; called after the component subtree was destroyed, in reverse declaration order
(defprotocol IRefDestroy
  (-ref-destroy! [this]))

;; ref values implementing this are notified around component renders
;; only called when the component actually renders, not when render was skipped
;; after-render is called right after the DOM update, which may still be offscreen (suspense)
;; use effects for things that need the DOM to be in the document
(defprotocol IRefRender
  (-ref-before-render! [this env])
  (-ref-after-render! [this env]))

;; just here so that working on components file doesn't cause hot-reload issues
;; with already constructed components
(deftype ComponentConfig
  [component-name
   min-args
   args-fn
   slots
   ;; array of (fn [comp] init-value) or nil, one per (ref ...) in declaration order
   refs
   slot-init-bits
   opts
   check-args-fn
   render-deps
   render-fn
   events
   debug-info])
