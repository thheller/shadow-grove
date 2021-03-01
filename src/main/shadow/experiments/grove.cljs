(ns shadow.experiments.grove
  "grove - a small wood or forested area (ie. trees)
   a mini re-frame/fulcro hybrid. re-frame event styles + somewhat normalized db"
  (:require-macros [shadow.experiments.grove])
  (:require
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.fragments] ;; util macro references this
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.arborist.collections :as sc]
    [goog.async.nextTick]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.components :as comp]
    [shadow.experiments.grove.ui.util :as util]
    [shadow.experiments.grove.ui.suspense :as suspense]
    [shadow.experiments.grove.ui.atoms :as atoms]
    [shadow.experiments.arborist.attributes :as a]))

(set! *warn-on-infer* false)

;; these are private - should not be accessed from the outside
(defonce active-roots-ref (atom {}))
(defonce active-apps-ref (atom {}))

(defn run-now! [env callback]
  (gp/run-now! (::comp/scheduler env) callback))

(defn dispatch-up! [{::comp/keys [^not-native parent] :as env} ev-map]
  {:pre [(map? env)
         (map? ev-map)
         (qualified-keyword? (:e ev-map))]}
  ;; FIXME: should schedule properly when it isn't in event handler already
  (gp/handle-event! parent ev-map nil))

(deftype QueryInit [ident query config]
  gp/IBuildHook
  (hook-build [this component idx]
    ;; support multiple query engines by allowing queries to supply which key to use
    (let [env (comp/get-env component)
          engine-key (:engine config ::gp/query-engine)
          query-engine (get env engine-key)]

      (assert query-engine (str "no query engine in env for key " engine-key))
      (gp/query-hook-build query-engine env component idx ident query config))))

(defn query-ident
  ;; shortcut for ident lookups that can skip EQL queries
  ([ident]
   {:pre [(vector? ident)]}
   (QueryInit. ident nil {}))

  ;; EQL queries
  ([ident query]
   (query-ident ident query {}))
  ([ident query config]
   {:pre [;; (db/ident? ident) FIXME: can't access db namespace in main, move to protocols?
          (vector? ident)
          (keyword? (first ident))
          (vector? query)
          (map? config)]}
   (QueryInit. ident query config)))

(defn query-root
  ([query]
   (query-root query {}))
  ([query config]
   {:pre [(vector? query)
          (map? config)]}
   (QueryInit. nil query config)))

(defn tx*
  [{::gp/keys [query-engine] :as env} tx with-return?]
  (assert query-engine "missing query-engine in env")
  (assert (map? tx) "expected transaction to be a map")
  (gp/transact! query-engine tx with-return?))

(defn tx [env ev-map e]
  (tx* env ev-map false))

(defn run-tx [env tx]
  (tx* env tx false))

(defn run-tx-with-return [env tx]
  (tx* env tx true))

(deftype RootScheduler [^:mutable update-pending? work-set]
  gp/IScheduleUpdates
  (schedule-update! [this work-task]
    (.add work-set work-task)

    (when-not update-pending?
      (set! update-pending? true)
      (util/next-tick #(.process-work! this))))

  (unschedule! [this work-task]
    (.delete work-set work-task))

  (did-suspend! [this target])
  (did-finish! [this target])

  (run-now! [this action]
    (set! update-pending? true)
    (action)
    ;; work must happen immediately since (action) may need the DOM event that triggered it
    ;; any delaying the work here may result in additional paint calls (making things slower overall)
    ;; if things could have been async the work should have been queued as such and not ended up here
    (.process-work! this))

  Object
  (process-work! [this]
    (let [iter (.values work-set)]
      (loop []
        (let [current (.next iter)]
          (when (not ^boolean (.-done current))
            (gp/work! ^not-native (.-value current))

            ;; should time slice later and only continue work
            ;; until a given time budget is consumed
            (recur)))))

    (set! update-pending? false)))

(defn default-error-handler [component ex]
  ;; FIXME: this would be the only place there component-name is accessed
  ;; without this access closure removes it completely in :advanced which is nice
  ;; ok to access in debug builds though
  (if ^boolean js/goog.DEBUG
    (js/console.error (str "An Error occurred in " (.. component -config -component-name) ", it will not be rendered.") component)
    (js/console.error "An Error occurred in Component, it will not be rendered." component))
  (js/console.error ex))

(defn init* [app-id init-env init-features]
  {:pre [(some? app-id)
         (map? init-env)
         (sequential? init-features)
         (every? fn? init-features)]}

  (let [scheduler (RootScheduler. false (js/Set.))

        env
        (-> init-env
            (assoc
              ::app-id app-id
              ::comp/scheduler scheduler
              ::suspense-keys (atom {}))
            (cond->
              (not (contains? init-env ::comp/error-handler))
              (assoc ::comp/error-handler default-error-handler)))

        env
        (reduce
          (fn [env init-fn]
            (init-fn env))
          env
          init-features)]

    env))

(defn init [app-id init-env init-features]
  {:pre [(some? app-id)
         (map? init-env)
         (sequential? init-features)
         (every? fn? init-features)]}

  (let [env (init* app-id init-env init-features)]
    ;; FIXME: throw if already initialized?
    (swap! active-apps-ref assoc app-id env)
    ;; never expose the env so people don't get ideas about using it
    app-id))

(defn start [app-id root-el root-node]
  (let [env (get @active-apps-ref app-id)]
    (if-not env
      (throw (ex-info "app not initialized" {:app-id app-id}))
      (let [active (get @active-roots-ref root-el)]
        (if-not active
          (let [root (sa/dom-root root-el env)]
            (sa/update! root root-node)
            (swap! active-roots-ref assoc root-el {:env env :root root :root-el root-el})
            ::started)

          (let [{:keys [root]} active]
            (assert (identical? env (:env active)) "can't change env between restarts")
            (when ^boolean js/goog.DEBUG
              (comp/mark-all-dirty!))

            (sa/update! root root-node)
            ::updated
            ))))))

;; FIXME: figure out if stop is ever needed?
#_(defn stop [root-el]
    (when-let [{::keys [app-root] :as env} (get @active-roots-ref root-el)]
      (swap! active-roots-ref dissoc root-el)
      (ap/destroy! app-root)
      (dissoc env ::app-root ::root-el)))

(defn watch
  "hook that watches an atom and triggers an update on change
   accepts an optional path-or-fn arg that can be used for quick diffs

   (watch the-atom [:foo])
   (watch the-atom (fn [old new] ...))"
  ([the-atom]
   (watch the-atom (fn [old new] new)))
  ([the-atom path-or-fn]
   (if (vector? path-or-fn)
     (atoms/AtomWatch. the-atom (fn [old new] (get-in new path-or-fn)) nil nil nil)
     (atoms/AtomWatch. the-atom path-or-fn nil nil nil))))

(defn env-watch
  ([key-to-atom]
   (env-watch key-to-atom [] nil))
  ([key-to-atom path]
   (env-watch key-to-atom path nil))
  ([key-to-atom path default]
   {:pre [(keyword? key-to-atom)
          (vector? path)]}
   (atoms/EnvWatch. key-to-atom path default nil nil nil nil)))

(defn suspense [opts vnode]
  (suspense/SuspenseInit. opts vnode))

(defn simple-seq [coll render-fn]
  (sc/simple-seq coll render-fn))

(defn render-seq [coll key-fn render-fn]
  (sc/keyed-seq coll key-fn render-fn))

(defn keyed-seq [coll key-fn render-fn]
  (sc/keyed-seq coll key-fn render-fn))

(deftype TrackChange [^:mutable val ^:mutable trigger-fn ^:mutable result env component idx]
  gp/IBuildHook
  (hook-build [this c i]
    (TrackChange. val trigger-fn nil (comp/get-env c) c i))

  gp/IHook
  (hook-init! [this]
    (set! result (trigger-fn env nil val)))

  (hook-ready? [this] true)
  (hook-value [this] result)
  (hook-update! [this] false)

  (hook-deps-update! [this ^TrackChange new-track]
    (assert (instance? TrackChange new-track))

    (let [next-val (.-val new-track)
          prev-result result]

      (set! trigger-fn (.-trigger-fn new-track))
      (set! result (trigger-fn env val next-val))
      (set! val next-val)

      (not= result prev-result)))

  (hook-destroy! [this]
    ))

(defn track-change [val trigger-fn]
  (TrackChange. val trigger-fn nil nil nil nil))

(a/add-attr :dom/ref
  (fn [env node oval nval]
    (cond
      (nil? nval)
      (vreset! oval nil)

      (some? nval)
      (vreset! nval node)

      :else
      nil)))

;; using volatile so nobody gets any ideas about add-watch
;; pretty sure that would cause havoc on the entire rendering
;; if sometimes does work immediately on set before render can even complete
(defn ref []
  (volatile! nil))

(defn effect
  "calls (callback env) after render when provided deps argument changes
   callback can return a function which will be called if cleanup is required"
  [deps callback]
  (comp/EffectHook. deps callback nil true nil nil))

(defn render-effect
  "call (callback env) after every render"
  [callback]
  (comp/EffectHook. :render callback nil true nil nil))

(defn mount-effect
  "call (callback env) on mount once"
  [callback]
  (comp/EffectHook. :mount callback nil true nil nil))
