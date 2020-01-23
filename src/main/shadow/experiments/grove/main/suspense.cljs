(ns shadow.experiments.grove.main.suspense
  (:require
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.main.util :as util]))

(util/assert-not-in-worker!)

(declare SuspenseInit)

(deftype SuspenseRoot
  [^:mutable opts
   ^:mutable vnode
   marker
   parent-env
   parent-scheduler
   ^:mutable child-env
   ^:mutable display
   ^:mutable offscreen
   ^:mutable suspend-set
   ^:mutable timeout
   ^boolean ^:mutable dom-entered?]

  ap/IManaged
  (supports? [this next]
    (instance? SuspenseInit next))

  (dom-sync! [this ^SuspenseInit next]
    ;; FIXME: figure out strategy for this?
    ;; if displaying fallback start rendering in background
    ;; if displaying managed and supported, just sync
    ;; if displaying managed and not supported, start rendering in background and swap when ready
    ;; when rendering in background display fallback after timeout?

    (set! vnode (.-vnode next))
    (set! opts (.-opts next))

    (cond
      ;; offscreen update
      (and offscreen (ap/supports? offscreen vnode))
      (ap/dom-sync! offscreen vnode)

      ;; offscreen swap
      ;; if new offscreen does not suspend immediately replace display placeholder
      ;; otherwise keep offscreen
      offscreen
      (do (set! suspend-set #{})
          (let [next-managed (ap/as-managed vnode child-env)]
            ;; destroy current offscreen immediately
            (ap/destroy! offscreen)
            (set! offscreen next-managed)

            ;; if not immediately suspended immediately swap
            ;; otherwise continue offscreen
            ;; naming of these helper fns doesn't quite match their intent
            ;; but saves duplicating code
            ;; FIXME: maybe clean up a bit
            (if (empty? suspend-set)
              (.maybe-swap! this)
              (.start-offscreen! this))))

      ;; display supports updating, just update
      (ap/supports? display vnode)
      (ap/dom-sync! display vnode)

      :else ;; replace display and maybe start offscreen again
      (let [new (ap/as-managed vnode child-env)]
        (if (empty? suspend-set)
          (do (common/fragment-replace display new)
              (set! display new)
              (when dom-entered?
                (ap/dom-entered! new)))
          (do (set! offscreen new)
              (.start-offscreen! this)
              (.schedule-timeout! this)
              )))))

  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor)
    (ap/dom-insert display parent anchor))

  (dom-first [this]
    marker)

  (dom-entered! [this]
    (set! dom-entered? true)
    (ap/dom-entered! display))

  (destroy! [this]
    (when timeout
      (js/clearTimeout timeout))
    (.remove marker)
    (when display
      (ap/destroy! display))
    (when offscreen
      (ap/destroy! offscreen)))

  gp/IScheduleUpdates
  (schedule-update! [this target]
    (gp/schedule-update! parent-scheduler target))

  (unschedule! [this target]
    (gp/unschedule! parent-scheduler target))

  (run-now! [this action]
    (gp/run-now! parent-scheduler action))

  (did-suspend! [this target]
    ;; (js/console.log "did-suspend!" suspend-set target)
    ;; FIXME: suspend parent scheduler when going offscreen?
    (set! suspend-set (conj suspend-set target)))

  (did-finish! [this target]
    ;; (js/console.log "did-finish!" suspend-set target)
    (set! suspend-set (disj suspend-set target))
    (when (and offscreen (empty? suspend-set))
      (js/goog.async.nextTick #(.maybe-swap! this))))

  Object
  (init! [this]
    ;; can't be done in as-managed since it needs the this pointer
    (let [next-env (assoc parent-env ::gp/scheduler this)
          next-managed (ap/as-managed vnode next-env)]
      (set! child-env next-env)
      (if (empty? suspend-set)
        (set! display next-managed)
        (do (set! offscreen next-managed)
            (.start-offscreen! this)
            (set! display (ap/as-managed (:fallback opts) parent-env))))))

  (schedule-timeout! [this]
    (when-not timeout
      (let [timeout-ms (:timeout opts 500)]
        (set! timeout (js/setTimeout #(.did-timeout! this) timeout-ms)))))

  (start-offscreen! [this]
    (when-some [key (:key opts)]
      (swap! (::suspense-keys parent-env) assoc key (js/Date.now))))

  (did-timeout! [this]
    (set! timeout nil)
    (when offscreen
      (let [fallback (ap/as-managed (:fallback opts) child-env)
            old-display display]
        ;; (js/console.log "using fallback after timeout")
        (set! display (common/fragment-replace old-display fallback))
        (when dom-entered?
          (ap/dom-entered! display)
          ))))

  (maybe-swap! [this]
    (when (and offscreen (empty? suspend-set))
      (ap/dom-insert offscreen (.-parentElement marker) marker)
      (ap/destroy! display)
      (set! display offscreen)
      (set! offscreen nil)

      (when dom-entered?
        (ap/dom-entered! display))

      (when-some [key (:key opts)]
        (swap! (::suspense-keys parent-env) dissoc key))

      (when timeout
        (js/clearTimeout timeout)
        (set! timeout nil)
        ))))

(deftype SuspenseInit [opts vnode]
  ap/IConstruct
  (as-managed [this env]
    (doto (SuspenseRoot. opts vnode (common/dom-marker env) env (::gp/scheduler env) nil nil nil #{} nil false)
      (.init!))))

