(ns shadow.experiments.grove
  "grove - a small wood or forested area (ie. trees)
   a mini re-frame/fulcro hybrid. re-frame event styles + somewhat normalized db"
  (:require-macros [shadow.experiments.grove])
  (:require
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.fragments] ;; util macro references this
    [shadow.experiments.arborist :as sa]
    [goog.async.nextTick]
    [cognitect.transit :as transit]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.components :as comp]
    ))

(defonce active-roots-ref (atom {}))

(defn next-tick [callback]
  ;; FIXME: should be smarter about when/where to schedule
  (js/goog.async.nextTick callback))

(def now
  (if (exists? js/performance)
    #(js/performance.now)
    #(js/Date.now)))

(deftype TreeScheduler [^:mutable work-arr ^:mutable update-pending?]
  gp/IScheduleUpdates
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
      (next-tick #(.process-pending! this))))

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
                (when-not (gp/work-pending? next)
                  (throw (ex-info "work was scheduled but isn't pending?" {:next next})))
                (gp/work! next)

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

(defn run-now! [env callback]
  (gp/run-now! (::gp/scheduler env) callback))

(defonce query-id-seq (atom 0))

(defn make-query-id []
  (swap! query-id-seq inc))

(deftype QueryHook
  [^:mutable ident
   ^:mutable query
   ^:mutable config
   component
   idx
   env
   query-engine
   query-id
   ^:mutable ready?
   ^:mutable read-result]

  gp/IBuildHook
  (hook-build [this c i]
    ;; support multiple query engines by allowing queries to supply which key to use
    (let [env (comp/get-env c)
          engine-key  (:engine config ::gp/query-engine)
          query-engine (get env engine-key)]
      (assert query-engine (str "no query engine in env for key " engine-key))
      (QueryHook. ident query config c i env query-engine (make-query-id) false nil)))

  gp/IHook
  (hook-init! [this]
    (.set-loading! this)
    (.register-query! this))

  (hook-ready? [this]
    (or (false? (:suspend config)) ready?))

  (hook-value [this]
    read-result)

  ;; node deps changed, check if query changed
  (hook-deps-update! [this ^QueryHook val]
    (if (and (= ident (.-ident val))
             (= query (.-query val))
             (= config (.-config val)))
      false
      ;; query changed, remove it entirely and wait for new one
      (do (.unregister-query! this)
          (set! ident (.-ident val))
          (set! query (.-query val))
          (set! config (.-config val))
          (.set-loading! this)
          (.register-query! this)
          true)))

  ;; node was invalidated and needs update, but its dependencies didn't change
  (hook-update! [this]
    true)

  (hook-destroy! [this]
    (.unregister-query! this))

  Object
  (register-query! [this]
    (gp/register-query query-engine env query-id (if ident [{ident query}] query) config
      (fn [result]
        (.set-data! this result))))

  (unregister-query! [this]
    (gp/unregister-query query-engine query-id))

  (set-loading! [this]
    (set! ready? (false? (:suspend config)))
    (set! read-result (assoc (:default config {}) ::loading-state :loading)))

  (set-data! [this data]
    (let [data (if ident (get data ident) data)]
      (set! read-result (assoc data ::loading-state :ready))
      ;; on query update just invalidate. might be useful to bypass certain suspend logic?
      (if ready?
        (comp/hook-invalidate! component idx)
        (do (comp/hook-ready! component idx)
            (set! ready? true))))))

(defn query-ident
  ([ident query]
   {:pre [;; (db/ident? ident) FIXME: can't access db namespace in main, move to protocols?
          (vector? query)]}
   (QueryHook. ident query {} nil nil nil nil nil false nil))
  ([ident query config]
   {:pre [;; (db/ident? ident) FIXME: can't access db namespace in main, move to protocols?
          (vector? query)
          (map? config)]}
   (QueryHook. ident query config nil nil nil nil nil false nil)))

(defn query-root
  ([query]
   {:pre [(vector? query)]}
   (QueryHook. nil query {} nil nil nil nil nil false nil))
  ([query config]
   {:pre [(vector? query)
          (map? config)]}
   (QueryHook. nil query config nil nil nil nil nil false nil)))

(defn tx*
  [{::gp/keys [query-engine] :as env} tx]
  (assert query-engine "missing query-engine in env")
  (gp/transact! query-engine env tx))

(defn tx [env e & params]
  (tx* env (into [(::comp/ev-id env)] params)))

(defn run-tx [env tx]
  (tx* env tx))

(defn form [defaults])

(defn form-values [form])

(defn form-reset! [form])

(defn init
  [env app-id]
  (let [scheduler (TreeScheduler. (array) false)]
    (assoc env
      ::app-id app-id
      ::gp/scheduler scheduler
      ::suspense-keys (atom {}))))

(defn start [env root-el root-node]
  (let [active (get @active-roots-ref root-el)]
    (if-not active
      (let [root (sa/dom-root root-el env)]
        (sa/update! root root-node)
        (swap! active-roots-ref assoc root-el {:env env :root root :root-el root-el})
        ::started)

      (let [{:keys [root]} active]
        (assert (identical? env (:env active)) "can't change env between restarts")
        (sa/update! root root-node)
        ::updated
        ))))

(defn stop [root-el]
  (when-let [{::keys [app-root] :as env} (get @active-roots-ref root-el)]
    (swap! active-roots-ref dissoc root-el)
    (ap/destroy! app-root)
    (dissoc env ::app-root ::root-el)))

(deftype AtomWatch [the-atom ^:mutable val component idx]
  gp/IBuildHook
  (hook-build [this c i]
    (AtomWatch. the-atom nil c i))

  gp/IHook
  (hook-init! [this]
    (set! val @the-atom)
    (add-watch the-atom this
      (fn [_ _ _ next-val]
        ;; check immediately and only invalidate if actually changed
        ;; avoids kicking off too much work
        ;; FIXME: maybe shouldn't check equiv? only identical?
        ;; pretty likely that something changed after all
        (when (not= val next-val)
          (set! val next-val)
          (comp/hook-invalidate! component idx)))))

  (hook-ready? [this] true) ;; born ready
  (hook-value [this] val)
  (hook-update! [this]
    ;; only gets here if value changed
    true)
  (hook-deps-update! [this new-val]
    (throw (ex-info "shouldn't have changing deps?" {})))
  (hook-destroy! [this]
    (remove-watch the-atom this)))

(defn watch [the-atom]
  (AtomWatch. the-atom nil nil nil))

(deftype EnvWatch [key-to-atom path default the-atom ^:mutable val component idx]
  gp/IBuildHook
  (hook-build [this c i]
    (let [atom (get (comp/get-env c) key-to-atom)]
      (when-not atom
        (throw (ex-info "no atom found under key" {:key key-to-atom :path path})))
      (EnvWatch. key-to-atom path default atom nil c i)))

  gp/IHook
  (hook-init! [this]
    (set! val (get-in @the-atom path default))
    (add-watch the-atom this
      (fn [_ _ _ new-value]
        ;; check immediately and only invalidate if actually changed
        ;; avoids kicking off too much work
        (let [next-val (get-in new-value path default)]
          (when (not= val next-val)
            (set! val next-val)
            (comp/hook-invalidate! component idx))))))

  (hook-ready? [this] true) ;; born ready
  (hook-value [this] val)
  (hook-update! [this]
    ;; only gets here if val actually changed
    true)

  (hook-deps-update! [this new-val]
    (throw (ex-info "shouldn't have changing deps?" {})))
  (hook-destroy! [this]
    (remove-watch the-atom this)))

(defn env-watch
  ([key-to-atom]
   (env-watch key-to-atom [] nil))
  ([key-to-atom path]
   (env-watch key-to-atom path nil))
  ([key-to-atom path default]
   {:pre [(keyword? key-to-atom)
          (vector? path)]}
   (EnvWatch. key-to-atom path default nil nil nil nil)))

(declare SuspenseRootNode)

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
   ^:mutable timeout]

  ap/IUpdatable
  (supports? [this next]
    (instance? SuspenseRootNode next))

  (dom-sync! [this ^SuspenseRootNode next]
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
              (set! display new))
          (do (set! offscreen new)
              (.start-offscreen! this)
              (.schedule-timeout! this)
              )))))

  ap/IManageNodes
  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor)
    (ap/dom-insert display parent anchor))

  (dom-first [this]
    marker)

  ap/IDestructible
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
        )))

  (maybe-swap! [this]
    (when (and offscreen (empty? suspend-set))
      (ap/dom-insert offscreen (.-parentElement marker) marker)
      (ap/destroy! display)
      (set! display offscreen)
      (set! offscreen nil)

      (when-some [key (:key opts)]
        (swap! (::suspense-keys parent-env) dissoc key))

      (when timeout
        (js/clearTimeout timeout)
        (set! timeout nil)
        ))))

(deftype SuspenseRootNode [opts vnode]
  ap/IConstruct
  (as-managed [this env]
    (doto (SuspenseRoot. opts vnode (common/dom-marker env) env (::gp/scheduler env) nil nil nil #{} nil)
      (.init!))))

(defn suspense [opts vnode]
  (SuspenseRootNode. opts vnode))

(defn stream [stream-id opts item-fn])
