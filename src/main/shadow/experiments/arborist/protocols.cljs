(ns shadow.experiments.arborist.protocols
  (:refer-clojure :exclude #{swap!}))

(defprotocol IManaged
  (supports? [this next])
  (dom-sync! [this next])

  (dom-insert [this parent anchor])
  (dom-first [this])

  ;; called after all nodes managed by this have been added to the actual document
  ;; might be immediately after dom-insert but may be delayed when tree is constructed
  ;; offscreen by something like suspense
  ;; implementations must properly propagate this to children if needed
  (dom-entered! [this])

  (destroy! [this]))

;; root user api
(defprotocol IDirectUpdate
  (update! [this next]))

(defprotocol IConstruct
  (as-managed [this env]))
