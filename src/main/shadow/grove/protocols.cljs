(ns shadow.grove.protocols)

(defprotocol IWork
  (work! [this]))

(defprotocol IScheduleWork
  (schedule-work! [this task trigger])
  (unschedule! [this task])
  (run-now! [this action trigger])

  ;; FIXME: this is purely a UI concern, should most likely be a separate interface
  ;; this ns is meant to be usable in a worker environment which is not concerned with suspense
  ;; for now suspense is a hack anyways so need to sort that out more
  (did-suspend! [this target])
  (did-finish! [this target])

  ;; need actual scheduler support in browser for these
  ;; (run-asap! [this action])
  ;; (run-whenever! [this action])
  )

(defprotocol IHandleEvents
  ;; e and origin can be considered optional and will be ignored by most actual handlers
  (handle-event! [this ev-map e origin]))


(defprotocol IHook
  (hook-init! [this component-handle]
    "used to create a hook managed by `component`. called on component mount.
     
     example:
     `(bind foo (+ bar 1))` will create a hook named `foo`, whose value
     is initialised to `(+ bar 1)` on component mount.

     specifically, `foo` will be (SimpleVal. (+ bar 1)).
     (this is the default implementation of this protocol,
     found in grove.components.)")
  (hook-ready? [this]
    "called once on mount. used for suspense/async. when false the component will
     stop and wait until the hook signals ready. then it will continue mounting
     and call the remaining hooks and render.")
  (hook-value [this]
    "return the value of the hook")
  ;; true-ish return if component needs further updating
  (hook-deps-update! [this val]
    "called when the deps of the hook change.
     example: `(bind foo (+ bar))`, this method is called when `bar` changes.

     if true-ish value returned, hooks depending on this hook will update.
     
     `val` corresponds to the result of evaluating the body of the hook
     (with updated deps). e.g. result of `(+ bar)` in example above.
      If bind body returns a hook, val will be that hook (a custom type).")
  (hook-update! [this]
    "called after the hook's value becomes invalidated.
     (e.g. with `comp/hook-invalidate!`)
     
     if true-ish value returned, hooks depending on this hook will update.

     hook-invalidate! marks the hook as dirty and will add the component to the work set.
     then the work set bubbles up to the root and starts to work there,
     working off all pending work and calling hook-update! for all 'dirty' hooks.
     if that returns true it'll make all hooks dirty that depend on the hook-value,
     eventually reaching render if anything in render was dirty, then proceeding
     down the tree.")
  (hook-destroy! [this]
    "called on component unmount"))

(defprotocol IHookDomEffect
  (hook-did-update! [this did-render?]
    "called after component render"))


(defprotocol IComponentHookHandle
  (hook-invalidate! [this] "called when a hook wants the component to refresh"))

(defprotocol IEnvSource
  (get-component-env [this]))

(defprotocol ISchedulerSource
  (get-scheduler [this]))

;; just here so that working on components file doesn't cause hot-reload issues
;; with already constructed components
(deftype ComponentConfig
  [component-name
   hooks
   opts
   check-args-fn
   render-deps
   render-fn
   events])

(defprotocol IQuery
  (query-refresh! [this]))