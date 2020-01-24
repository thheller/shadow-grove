(ns shadow.experiments.grove.protocols)


;; not using record since they shouldn't act as maps
;; also does a bunch of other stuff I don't want
(deftype Ident [entity-type id ^:mutable _hash]
  ILookup
  (-lookup [this key]
    (case key
      :entity-type entity-type
      :id id
      nil))

  IHash
  (-hash [this]
    (if (some? _hash)
      _hash
      (let [x (bit-or 123 (hash id) (hash id))]
          (set! _hash x)
          x)))
  IEquiv
  (-equiv [this ^Ident other]
    (and (instance? Ident other)
         (keyword-identical? entity-type (.-entity-type other))
         (= id (.-id other)))))


(defprotocol IWork
  (work-priority [this] "number, higher number = higher priority")
  (work-depth [this] "number, tree depth, higher = lower priority")
  (work-id [this] "string")
  (work! [this])
  (work-pending? [this]))

(defprotocol IHandleEvents
  (handle-event! [this ev-id e ev-args]))

(defprotocol IScheduleUpdates
  (schedule-update! [this target])
  (unschedule! [this target])
  (run-now! [this action])

  (did-suspend! [this target])
  (did-finish! [this target])

  (run-asap! [this action])
  (run-whenever! [this action]))

(defprotocol IBuildHook
  (hook-build [node component idx]))

(defprotocol IProfile
  (perf-count! [this counter-id])
  (perf-start! [this])
  (perf-destroy! [this]))

(defprotocol IHook
  (hook-init! [node])
  (hook-ready? [node])
  (hook-value [node])
  (hook-deps-update! [node val])
  (hook-update! [node])
  ;; (node-did-mount [node] "do stuff after the initial mount only")
  ;; (node-did-update [node] "do stuff after dom update")
  (hook-destroy! [node]))

;; just here so that working on components file doesn't cause hot-reload issues
;; with already constructed components
(deftype ComponentConfig
  [component-name
   hooks
   opts
   check-args-fn
   render-deps
   render-fn])

(defprotocol IQueryEngine
  (query-init [this key query config callback])
  (query-destroy [this key])
  ;; FIXME: one shot query that can't be updated later?
  ;; can be done by helper method over init/destroy but engine
  ;; would still do a bunch of needless work
  ;; only had one case where this might have been useful, maybe it isn't worth adding?
  ;; (query-once [this query config callback])
  (transact! [this tx]))

(defprotocol IStreamEngine
  (stream-init [this env stream-id stream-key opts callback])
  (stream-destroy [this stream-id]))