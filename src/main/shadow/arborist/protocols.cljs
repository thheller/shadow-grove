(ns shadow.arborist.protocols)

(def svg-ns "http://www.w3.org/2000/svg")

(defprotocol IManaged
  (^boolean supports? [this next])
  (dom-sync! [this next])

  (dom-insert [this parent anchor])
  (dom-first [this])

  ;; called after all nodes managed by this have been added to the actual document
  ;; might be immediately after dom-insert but may be delayed when tree is constructed
  ;; offscreen by something like suspense
  ;; implementations must properly propagate this to children if needed
  (dom-entered! [this])

  ;; if parent node was already removed from DOM the children
  ;; don't need to bother removing themselves again
  (destroy! [this ^boolean dom-remove?]))

;; root user api
(defprotocol IDirectUpdate
  (update! [this next]))

(defprotocol IConstruct
  :extend-via-metadata true
  (as-managed [this env]))

(defn identical-creator? [a b]
  (let [am (get (meta a) `as-managed)
        bm (get (meta b) `as-managed)]
    (and am bm (identical? am bm))))

(defprotocol IHandleDOMEvents
  (validate-dom-event-value! [this env event value])
  (handle-dom-event! [this env event ev-value dom-event]))