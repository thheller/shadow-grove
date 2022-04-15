(ns shadow.experiments.grove.runtime
  (:require
    [goog.async.nextTick]
    [shadow.experiments.grove.protocols :as gp]))

;; code in here is shared between the worker and local runtime
;; don't put things here that should only be in one runtime

;; this is mostly for devtools so they can access the environments
;; actual code shouldn't use this anywhere
(defonce known-runtimes-ref (atom {}))

(defn ref? [x]
  (and (atom x)
       (::rt @x)))

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

(defn next-tick [callback]
  ;; FIXME: should be smarter about when/where to schedule
  (js/goog.async.nextTick callback))

(deftype RootScheduler [^:mutable update-pending? work-set]
  IScheduleWork
  (schedule-work! [this work-task trigger]
    (.add work-set work-task)

    (when-not update-pending?
      (set! update-pending? true)
      (next-tick #(.process-work! this))))

  (unschedule! [this work-task]
    (.delete work-set work-task))

  (did-suspend! [this target])
  (did-finish! [this target])

  (run-now! [this action trigger]
    (set! update-pending? true)
    (action)
    ;; work must happen immediately since (action) may need the DOM event that triggered it
    ;; any delaying the work here may result in additional paint calls (making things slower overall)
    ;; if things could have been async the work should have been queued as such and not ended up here
    (.process-work! this))

  Object
  (process-work! [this]
    (try
      (let [iter (.values work-set)]
        (loop []
          (let [current (.next iter)]
            (when (not ^boolean (.-done current))
              (gp/work! ^not-native (.-value current))

              ;; should time slice later and only continue work
              ;; until a given time budget is consumed
              (recur)))))

      (finally
        (set! update-pending? false)))))

;; FIXME: make this delegate to the above, don't duplicate the code
(deftype TracingRootScheduler [^:mutable update-pending? work-set]
  IScheduleWork
  (schedule-work! [this work-task trigger]
    (.add work-set work-task)

    (when-not update-pending?
      (set! update-pending? true)
      (next-tick
        (fn []
          (js/console.group (str trigger))
          (try
            (.process-work! this)
            (finally
              (js/console.groupEnd)))
          ))))

  (unschedule! [this work-task]
    (.delete work-set work-task))

  (did-suspend! [this target])
  (did-finish! [this target])

  (run-now! [this action trigger]
    (js/console.group (str trigger))
    (try
      (set! update-pending? true)
      (action)
      ;; work must happen immediately since (action) may need the DOM event that triggered it
      ;; any delaying the work here may result in additional paint calls (making things slower overall)
      ;; if things could have been async the work should have been queued as such and not ended up here
      (.process-work! this)

      (finally
        (js/console.groupEnd))
      ))

  Object
  (process-work! [this]
    (try
      (let [iter (.values work-set)]
        (loop []
          (let [current (.next iter)]
            (when (not ^boolean (.-done current))
              (gp/work! ^not-native (.-value current))

              ;; should time slice later and only continue work
              ;; until a given time budget is consumed
              (recur)))))

      (finally
        (set! update-pending? false)))))

(goog-define TRACE false)

(defn prepare [init data-ref runtime-id]
  (let [root-scheduler
        (if ^boolean TRACE
          (TracingRootScheduler. false (js/Set.))
          (RootScheduler. false (js/Set.)))

        rt-ref
        (-> init
            (assoc ::rt true
                   ::scheduler root-scheduler
                   ::runtime-id runtime-id
                   ::data-ref data-ref
                   ::event-config {}
                   ::fx-config {}
                   ::active-queries-map (js/Map.)
                   ::key-index-seq (atom 0)
                   ::key-index-ref (atom {})
                   ::query-index-map (js/Map.)
                   ::query-index-ref (atom {})
                   ::env-init [])
            (atom))]

    (when ^boolean js/goog.DEBUG
      (swap! known-runtimes-ref assoc runtime-id rt-ref))

    rt-ref))