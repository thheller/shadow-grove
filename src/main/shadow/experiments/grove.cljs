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
    [shadow.experiments.grove.main.util :as util]
    [shadow.experiments.grove.main.suspense :as suspense]
    [shadow.experiments.grove.main.streams :as streams]
    [shadow.experiments.grove.main.atoms :as atoms]
    ))

(defonce active-roots-ref (atom {}))

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
      (util/next-tick #(.process-pending! this))))

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
    (let [start (util/now)
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
                (let [diff (- (util/now) start)]
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
          engine-key (:engine config ::gp/query-engine)
          query-engine (get env engine-key)]
      (assert query-engine (str "no query engine in env for key " engine-key))
      (QueryHook. ident query config c i env query-engine (util/next-id) false nil)))

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

(defn watch [the-atom]
  (atoms/->AtomWatch the-atom nil nil nil))

(defn env-watch
  ([key-to-atom]
   (env-watch key-to-atom [] nil))
  ([key-to-atom path]
   (env-watch key-to-atom path nil))
  ([key-to-atom path default]
   {:pre [(keyword? key-to-atom)
          (vector? path)]}
   (atoms/->EnvWatch key-to-atom path default nil nil nil nil)))

(defn suspense [opts vnode]
  (suspense/->SuspenseRootNode opts vnode))

(defn stream [stream-key opts item-fn]
  (streams/->StreamNode. stream-key opts item-fn))

(defn render-seq [coll key-fn render-fn]
  (sa/render-seq coll key-fn render-fn))