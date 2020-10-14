(ns shadow.experiments.grove.runtime)

;; code in here is shared between the worker and local runtime
;; don't put things here that should only be in one runtime

;; this is mostly for devtools so they can access the environments
;; actual code shouldn't use this anywhere
(defonce known-runtimes-ref (atom {}))

(defn prepare [init-env data-ref app-id]
  (let [rt-ref
        (-> init-env
            (assoc ::app-id app-id
                   ::data-ref data-ref
                   ::event-config {}
                   ::fx-config {}
                   ::active-queries-ref (atom {})
                   ::query-index-ref (atom {}))
            (atom))]

    (when ^boolean js/goog.DEBUG
      (swap! known-runtimes-ref assoc app-id rt-ref))

    rt-ref))