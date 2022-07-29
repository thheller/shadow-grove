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
  (hook-init! [this])
  (hook-ready? [this])
  (hook-value [this])
  ;; true-ish return if component needs further updating
  (hook-deps-update! [this val])
  (hook-update! [this])
  (hook-destroy! [this]))

(defprotocol IHookDomEffect
  (hook-did-update! [this did-render?]))

(defprotocol IBuildHook
  (hook-build [this component-handle]))

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