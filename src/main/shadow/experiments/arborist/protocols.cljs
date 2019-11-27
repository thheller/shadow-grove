(ns shadow.experiments.arborist.protocols
  (:refer-clojure :exclude #{swap!}))

;; FIXME: these 3 should be one protocol and IDirectUpdate removed
;; this is needlessly complex and all impls need to support all anyways
;; the only node that is a special case is the TreeRoot ... but that is special
;; enough to use no protocols at all since there is only one impl
(defprotocol IUpdatable
  (supports? [this next])
  (dom-sync! [this next]))

(defprotocol IManageNodes
  (dom-insert [this parent anchor])
  (dom-first [this]))

(defprotocol IDestructible
  (destroyed? [this])
  (destroy! [this]))

;; root user api
(defprotocol IDirectUpdate
  (update! [this next]))

(defprotocol IConstruct
  (as-managed [this env]))

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
   props-affects-render
   props-affects
   state-affects-render
   state-affects
   render-deps
   render-fn])