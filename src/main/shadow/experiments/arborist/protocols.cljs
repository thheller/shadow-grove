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
  (dom-first [this])

  ;; called after all nodes managed by this have been added to the actual document
  ;; might be immediately after dom-insert but may be delayed when tree is constructed
  ;; offscreen by something like suspense
  ;; implementations must properly propagate this to children if needed
  (dom-entered! [this]))

(defprotocol IDestructible
  (destroy! [this]))

;; root user api
(defprotocol IDirectUpdate
  (update! [this next]))

(defprotocol IConstruct
  (as-managed [this env]))
