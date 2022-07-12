(ns shadow.grove.protocols)



(defprotocol IWork
  (work! [this]))

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

(defprotocol IQueryEngine
  ;; each engine may have different requirements regarding interop with the components
  ;; websocket engine can only do async queries
  ;; local engine can do sync queries but might have async results
  ;; instead of trying to write a generic one they should be able to specialize
  (query-hook-build [this env component-handle ident query config])

  ;; hooks may use these but they may also interface with the engine directly
  (query-init [this key query config callback])
  (query-destroy [this key])

  ;; FIXME: one shot query that can't be updated later?
  ;; can be done by helper method over init/destroy but engine
  ;; would still do a bunch of needless work
  ;; only had one case where this might have been useful, maybe it isn't worth adding?
  ;; (query-once [this query config callback])

  ;; returns a promise, tx might need to go async
  (transact! [this tx origin]))

