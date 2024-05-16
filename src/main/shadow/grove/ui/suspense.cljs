(ns shadow.grove.ui.suspense
  (:require
    [shadow.arborist.common :as common]
    [shadow.arborist.protocols :as ap]
    [shadow.grove :as-alias sg]
    [shadow.grove.runtime :as rt]
    [shadow.grove.protocols :as gp]
    [shadow.grove.components :as comp]
    [shadow.grove.ui.util :as util]))

(set! *warn-on-infer* false)

(declare SuspenseInit)

(deftype SuspenseScheduler
  [parent-scheduler
   ^SuspenseRoot root
   ^:mutable should-trigger?
   ^:mutable suspend-set]

  gp/IScheduleWork
  (schedule-work! [this target trigger]
    (gp/schedule-work! parent-scheduler target trigger))

  (unschedule! [this target]
    (gp/unschedule! parent-scheduler target))

  (run-now! [this action trigger]
    (gp/run-now! parent-scheduler action trigger))

  (did-suspend! [this target]
    ;; (js/console.log "did-suspend!" suspend-set target)
    ;; FIXME: suspend parent scheduler when going offscreen?
    (set! suspend-set (conj suspend-set target)))

  (did-finish! [this target]
    ;; (js/console.log "did-finish!" suspend-set target)
    (set! suspend-set (disj suspend-set target))
    (when (and should-trigger? (empty? suspend-set))
      (set! should-trigger? false)
      (.tree-did-finish! root)))

  Object
  (set-should-trigger! [this]
    (set! should-trigger? true))

  (cancel! [this]
    (set! should-trigger? false))

  (did-suspend? [this]
    (pos? (count suspend-set))))

(deftype SuspenseRoot
  [^:mutable opts
   ^:mutable vnode
   marker
   parent-env
   parent-scheduler
   ^not-native ^:mutable display
   ^not-native ^:mutable offscreen
   ^SuspenseScheduler ^:mutable offscreen-scheduler
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
      (do (ap/destroy! offscreen true)
          (.cancel! offscreen-scheduler)

          (let [scheduler
                (SuspenseScheduler. parent-scheduler this false #{})

                offscreen-env
                (assoc parent-env ::sg/scheduler scheduler)

                next-managed
                (ap/as-managed vnode offscreen-env)]

            (if-not (.did-suspend? scheduler)
              (do (set! offscreen next-managed)
                  (.tree-did-finish! this))
              (do (set! offscreen next-managed)
                  (set! offscreen-scheduler scheduler)
                  (.set-should-trigger! scheduler)
                  (.start-offscreen! this)))))

      ;; display supports updating, just update
      (ap/supports? display vnode)
      (ap/dom-sync! display vnode)

      ;; replace display and maybe start offscreen again
      :else
      (let [scheduler
            (SuspenseScheduler. parent-scheduler this false #{})

            offscreen-env
            (assoc parent-env ::sg/scheduler scheduler)

            next-managed
            (ap/as-managed vnode offscreen-env)]

        (if-not (.did-suspend? scheduler)
          (do (common/fragment-replace display next-managed)
              (set! display next-managed)
              (when dom-entered?
                (ap/dom-entered! next-managed)))

          ;; display might not be the fallback
          ;; keep showing it until timeout
          (do (set! offscreen next-managed)
              (set! offscreen-scheduler scheduler)
              (.set-should-trigger! scheduler)
              (.schedule-timeout! this)
              (.start-offscreen! this))))))

  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor)
    (ap/dom-insert display parent anchor))

  (dom-first [this]
    marker)

  (dom-entered! [this]
    (set! dom-entered? true)
    (ap/dom-entered! display))

  (destroy! [this ^boolean dom-remove?]
    (when timeout
      (js/clearTimeout timeout))
    (when dom-remove?
      (.remove marker))
    (when display
      (ap/destroy! display dom-remove?))
    (when offscreen
      (.cancel! offscreen-scheduler)
      (ap/destroy! offscreen false)))

  Object
  (init! [this]
    ;; can't be done in as-managed since it needs the this pointer
    (let [scheduler
          (SuspenseScheduler. parent-scheduler this false #{})

          offscreen-env
          (assoc parent-env ::sg/scheduler scheduler)

          next-managed
          (ap/as-managed vnode offscreen-env)]

      (if-not (.did-suspend? scheduler)
        (set! display next-managed)
        (do (set! offscreen next-managed)
            (set! offscreen-scheduler scheduler)
            (set! display (ap/as-managed (:fallback opts) parent-env))
            (.set-should-trigger! scheduler)
            (.start-offscreen! this)))))

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
      (let [fallback (ap/as-managed (:fallback opts) parent-env)
            old-display display]
        ;; (js/console.log "using fallback after timeout")
        (set! display (common/fragment-replace old-display fallback))
        (when dom-entered?
          (ap/dom-entered! display)
          ))))

  (tree-did-finish! [this]
    (ap/dom-insert offscreen (.-parentElement marker) marker)
    (ap/destroy! display true)
    (set! display offscreen)
    (set! offscreen nil)
    (set! offscreen-scheduler nil)

    (when dom-entered?
      (ap/dom-entered! display))

    (when-some [key (:key opts)]
      (swap! (::suspense-keys parent-env) dissoc key))

    (when timeout
      (js/clearTimeout timeout)
      (set! timeout nil)
      )))

(deftype SuspenseInit [opts vnode]
  ap/IConstruct
  (as-managed [this env]
    (doto (SuspenseRoot.
            opts
            vnode
            (common/dom-marker env)
            env
            (::sg/scheduler env)
            nil ;; display
            nil ;; offscreen
            nil ;; offscreen-scheduler
            nil ;; timeout
            false ;; dom-entered?
            )
      (.init!))))
