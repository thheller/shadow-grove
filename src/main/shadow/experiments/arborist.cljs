(ns shadow.experiments.arborist
  {:doc "Arborists generally focus on the health and safety of individual plants and trees."
   :definition "https://en.wikipedia.org/wiki/Arborist"}
  (:require-macros
    [shadow.experiments.arborist]
    [shadow.experiments.arborist.fragments])
  (:require
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.arborist.fragments :as frag]
    [shadow.experiments.arborist.attributes :as attr]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.collections :as coll]

    ;; FIXME: move this out of here
    [shadow.experiments.grove.components :as comp]
    [goog.async.nextTick]))

(def now
  (if (exists? js/performance)
    #(js/performance.now)
    #(js/Date.now)))

(deftype TreeScheduler [^:mutable work-arr ^:mutable update-pending?]
  p/IScheduleUpdates
  (did-suspend! [this work-task])
  (did-finish! [this work-task])

  (schedule-update! [this work-task]
    ;; FIXME: now possible a task is scheduled multiple times
    ;; but the assumption is that task will only schedule themselves once
    ;; doesn't matter if its in the arr multiple times too much
    (.push work-arr work-task)

    ;; schedule was added in some async work
    (when-not update-pending?
      (set! update-pending? true)
      (js/goog.async.nextTick #(.process-pending! this))))

  (unschedule! [this work-task]
    ;; FIXME: might be better to track this in the task itself and just check when processing
    ;; and just remove it then. the array might get long?
    (set! work-arr (.filter work-arr (fn [x] (not (identical? x work-task))))))

  (run-now! [this callback]
    (set! update-pending? true)
    (callback)
    (.process-pending! this))

  Object
  (process-pending! [this]
    ;; FIXME: this now processes in FCFS order
    ;; should be more intelligent about prioritizing
    ;; should use requestIdleCallback or something to schedule in batch
    (let [start (now)
          done
          (loop []
            (if-not (pos? (alength work-arr))
              true
              (let [next (aget work-arr 0)]
                (when-not (p/work-pending? next)
                  (throw (ex-info "work was scheduled but isn't pending?" {:next next})))
                (p/work! next)

                ;; FIXME: using this causes a lot of intermediate paints
                ;; which means things take way longer especially when rendering collections
                ;; so there really needs to be a Suspense style node that can at least delay
                ;; inserting nodes into the actual DOM until they are actually ready
                (let [diff (- (now) start)]
                  ;; FIXME: more logical timeouts
                  ;; something like IdleTimeout from requestIdleCallback?
                  ;; dunno if there is a polyfill for that?
                  ;; not 16 to let the runtime do other stuff
                  (when (< diff 10)
                    (recur))))))]

      (if done
        (set! update-pending? false)
        (js/goog.async.nextTick #(.process-pending! this))))

    ;; FIXME: dom effects
    ))

(deftype TreeRoot [container ^:mutable env ^:mutable root]
  p/IDirectUpdate
  (update! [this next]
    (if root
      (p/update! root next)
      (let [new-root (common/managed-root env nil nil)]
        (set! root new-root)
        (p/update! root next)
        (p/dom-insert root container nil)
        )))

  p/IDestructible
  (destroy! [this]
    (when root
      (p/destroy! root))))

(defn init [env]
  (assoc env ::comp/scheduler (TreeScheduler. (array) false)))

(defn run-now! [env callback]
  (p/run-now! (::comp/scheduler env) callback))

(defn dom-root
  ([container env]
   (let [root (TreeRoot. container nil nil)
         root-env (assoc env ::root root)]
     (set! (.-env root) root-env)
     root))
  ([container env init]
   (doto (dom-root container env)
     (p/update! init))))

(defn << [& body]
  (throw (ex-info "<< can only be used a macro" {})))

(defn <> [& body]
  (throw (ex-info "<> can only be used a macro" {})))

(defn fragment [& body]
  (throw (ex-info "fragment can only be used a macro" {})))

(defn render-seq [coll key-fn render-fn]
  (when (some? coll)
    (coll/node coll key-fn render-fn)))

(defn update! [x next]
  (p/update! x next))

(defn destroy! [root]
  (p/destroy! root))

(deftype AtomWatch [the-atom ^:mutable val component idx]
  p/IBuildHook
  (hook-build [this c i]
    (AtomWatch. the-atom nil c i))

  p/IHook
  (hook-init! [this]
    (set! val @the-atom)
    (add-watch the-atom this
      (fn [_ _ _ _]
        ;; don't take new-val just yet, it may change again in the time before
        ;; we actually get to an update. deref'ing when the actual update occurs
        ;; which will also reset the dirty flag
        (comp/hook-invalidate! component idx))))

  (hook-ready? [this] true) ;; born ready
  (hook-value [this] val)
  (hook-update! [this]
    ;; time to actually deref, any change after this will invalidate and trigger
    ;; an update again. this doesn't mean the value will actually get to render.
    (set! val @the-atom)
    true)
  (hook-deps-update! [this new-val]
    (throw (ex-info "shouldn't have changing deps?" {})))
  (hook-destroy! [this]
    (remove-watch the-atom this)))

(defn watch [the-atom]
  (AtomWatch. the-atom nil nil nil))

(declare SuspenseRootNode)

(deftype SuspenseRoot
  [^:mutable vnode
   opts
   marker
   parent-env
   parent-scheduler
   ^:mutable child-env
   ^:mutable display
   ^:mutable offscreen
   ^:mutable suspend-set
   ^:mutable timeout]

  p/IUpdatable
  (supports? [this next]
    (instance? SuspenseRootNode next))

  (dom-sync! [this ^SuspenseRootNode next]
    ;; FIXME: figure out strategy for this?
    ;; if displaying fallback start rendering in background
    ;; if displaying managed and supported, just sync
    ;; if displaying managed and not supported, start rendering in background and swap when ready
    ;; when rendering in background display fallback after timeout?
    (when (or offscreen timeout)
      (throw (ex-info "syncing while not even finished yet" {:this this :offscreen offscreen :timeout timeout})))

    (let [next (.-vnode next)]
      ;; FIXME: what about changed opts?

      (if (p/supports? display next)
        (p/dom-sync! display next)
        (let [new (p/as-managed next child-env)]
          (if (empty? suspend-set)
            (do (common/fragment-replace display new)
                (set! display new))
            (do (set! offscreen new)
                (.schedule-timeout! this)
                ))))))

  p/IManageNodes
  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor)
    (p/dom-insert display parent anchor))

  (dom-first [this]
    marker)

  p/IDestructible
  (destroyed? [this])
  (destroy! [this]
    (when timeout
      (js/clearTimeout timeout))
    (.remove marker)
    (when display
      (p/destroy! display))
    (when offscreen
      (p/destroy! offscreen)))

  p/IScheduleUpdates
  (schedule-update! [this target]
    (p/schedule-update! parent-scheduler target))

  (unschedule! [this target]
    (p/unschedule! parent-scheduler target))

  (run-now! [this action]
    (p/run-now! parent-scheduler action))

  (did-suspend! [this target]
    ;; (js/console.log "did-suspend!" suspend-set target)
    (set! suspend-set (conj suspend-set target)))

  (did-finish! [this target]
    ;; (js/console.log "did-finish!" suspend-set target)
    (set! suspend-set (disj suspend-set target))
    (when (and offscreen (empty? suspend-set))
      (js/goog.async.nextTick #(.maybe-swap! this))))

  Object
  (init! [this]
    ;; can't be done in as-managed since it needs the this pointer
    (let [next-env (assoc parent-env ::comp/scheduler this)
          next-managed (p/as-managed vnode next-env)]
      (set! child-env next-env)
      (if (empty? suspend-set)
        (set! display next-managed)
        (do (set! offscreen next-managed)
            (set! display (p/as-managed (:fallback opts) parent-env))))))

  (schedule-timeout! [this]
    (when-not timeout
      (let [timeout-ms (:timeout opts 500)]
        (set! timeout (js/setTimeout #(.did-timeout! this) timeout-ms)))))

  (did-timeout! [this]
    (set! timeout nil)
    (when offscreen
      (let [fallback (p/as-managed (:fallback opts) child-env)
            old-display display]
        ;; (js/console.log "using fallback after timeout")
        (set! display (common/fragment-replace old-display fallback))
        )))

  (maybe-swap! [this]
    (when (and offscreen (empty? suspend-set))
      (p/dom-insert offscreen (.-parentElement marker) marker)
      (p/destroy! display)
      (set! display offscreen)
      (set! offscreen nil)

      (when timeout
        (js/clearTimeout timeout)
        (set! timeout nil)
        ))))

(deftype SuspenseRootNode [vnode opts]
  p/IConstruct
  (as-managed [this env]
    (doto (SuspenseRoot. vnode opts (common/dom-marker env) env (::comp/scheduler env) nil nil nil #{} nil)
      (.init!))))

(defn suspense [vnode opts]
  (SuspenseRootNode. vnode opts))