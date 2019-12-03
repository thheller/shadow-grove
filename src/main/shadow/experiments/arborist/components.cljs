(ns shadow.experiments.arborist.components
  (:require-macros [shadow.experiments.arborist.components])
  (:require
    [goog.object :as gobj]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.arborist.attributes :as a]
    ))

;; this file is an exercise in writing the least idiomatic clojure code possible
;; shield your eyes and beware!

(defonce components-ref (atom {}))

(declare ^{:arglists '([x])} component-node?)
(declare ^{:arglists '([component args])} ->ComponentNode)

(defn- make-component-node [component args]
  ;; FIXME: maybe use array, never directly accessible anyways
  {:pre [(vector? args)]}
  (->ComponentNode component args))

(extend-type p/ComponentConfig
  cljs.core/IFn
  (-invoke
    ([this]
     (make-component-node this []))
    ([this a1]
     (make-component-node this [a1]))
    ([this a1 a2]
     (make-component-node this [a1 a2]))
    ([this a1 a2 a3]
     (make-component-node this [a1 a2 a3]))
    ([this a1 a2 a3 a4]
     (make-component-node this [a1 a2 a3 a4]))
    ([this a1 a2 a3 a4 a5]
     (make-component-node this [a1 a2 a3 a4 a5]))
    ([this a1 a2 a3 a4 a5 a6]
     (make-component-node this [a1 a2 a3 a4 a5 a6]))
    ;; FIXME: add more, user should really use maps at this point
    ))

(defn component-config? [x]
  (instance? p/ComponentConfig x))

(defn safe-inc [x]
  (if (nil? x)
    0
    (inc x)))

(defonce component-id-seq (atom 0))

(defn next-component-id []
  (swap! component-id-seq inc))

(set! *warn-on-infer* false)

(defn sort-fn [^ManagedComponent a ^ManagedComponent b]
  (compare
    (-> a (.-component-env) (::depth))
    (-> b (.-component-env) (::depth))
    ))

;; FIXME: there are likely faster ways to do this
(defn find-first-set-bit-idx [search]
  {:pre [(not (zero? search))]}
  (loop [search search
         idx 0]
    (if (identical? 1 (bit-and search 1))
      idx
      (recur (bit-shift-right search 1) (inc idx)))))

(deftype ManagedComponent
  [^not-native scheduler ;; called often, need to avoid map lookup
   ^not-native ^:mutable parent-env
   ^not-native ^:mutable component-env
   ^:mutable args
   ^:mutable rendered-args
   ^ComponentConfig ^:mutable config
   ^not-native ^:mutable events
   ^:mutable root
   ^:mutable slots
   ^number ^:mutable current-idx
   ^array ^:mutable hooks
   ^number ^:mutable dirty-from-args
   ^number ^:mutable dirty-hooks
   ^number ^:mutable updated-hooks
   ^boolean ^:mutable needs-render?
   ^boolean ^:mutable suspended?
   ^boolean ^:mutable destroyed?]

  p/IManageNodes
  (dom-first [this]
    (p/dom-first root))

  (dom-insert [this parent anchor]
    (p/dom-insert root parent anchor))

  p/IUpdatable
  (supports? [this next]
    (and (component-node? next)
         (let [other (.-component ^ComponentNode next)]
           (identical? config other))))

  (dom-sync! [this ^ComponentNode next]
    (. config (check-args-fn this args (.-args next)))
    (set! args (.-args next))
    (when (p/work-pending? this)
      (.schedule! this)))

  ;; FIXME: figure out default event handler
  ;; don't want to declare all events all the time
  p/IHandleEvents
  (handle-event! [this ev-id e ev-args]
    (let [handler
          (cond
            (fn? ev-id)
            ev-id

            (keyword? ev-id)
            (or (get events ev-id)
                (get (.-opts config) ev-id))

            :else
            (throw (ex-info "unknown event" {:ev-id ev-id :e e :ev-args ev-args})))]

      (when handler
        (let [ev-env
              (assoc component-env
                ::e e
                ::ev-id ev-id)]

          ;; abuse fact that e is mutable anyways
          ;; just need to keep track if something at least attempted to handle the event
          (gobj/set e "shadow$handled" true)

          (apply handler ev-env e ev-args))))

    ;; FIXME: should all keyword events bubble up by default?
    ;; fn events always run directly in the component that triggered it
    (when (keyword? ev-id)
      (if-let [parent (::component parent-env)]
        (p/handle-event! parent ev-id e ev-args)
        (when-not (gobj/get e "shadow$handled")
          (js/console.warn "event not handled" ev-id e ev-args)))))

  p/IDestructible
  (destroyed? [this]
    destroyed?)

  (destroy! [this]
    (.unschedule! this)
    (set! destroyed? true)
    (run!
      (fn [hook]
        (when hook
          (p/hook-destroy! hook)))
      hooks)
    (p/destroy! root)

    (p/perf-destroy! this))

  p/IWork
  (work-priority [this] 10) ;; FIXME: could allow setting this via config
  (work-depth [this] (::depth component-env))
  (work-id [this] (::component-id component-env))

  (work-pending? [this]
    (and (not destroyed?)
         (or (pos? dirty-hooks) needs-render?)))

  (work! [this]
    (.run-next! this)

    (when-not (p/work-pending? this)
      (p/unschedule! scheduler this)))

  p/IProfile
  (perf-count! [this counter-id])
  (perf-start! [this])
  (perf-destroy! [this])

  ;; FIXME: should have an easier way to tell shadow-cljs not to create externs for these
  Object
  ;; can't do this in the ComponentNode.as-managed since we need the this pointer
  (^clj component-init! [^ComponentInstance this]
    (let [child-env
          (-> parent-env
              (update ::depth safe-inc)
              (assoc ::parent (::component parent-env))
              (assoc ::dom-refs (atom {}))
              ;; (assoc ::component-id (str (.-component-name config) "@" (next-component-id)))
              (assoc ::component this))]

      (p/perf-start! this)

      (set! component-env child-env)
      (set! root (common/managed-root child-env nil nil))
      (set! current-idx 0)
      (set! hooks (js/Array. (alength (.-hooks config))))

      (.schedule! this)
      ;; FIXME: should this run everything on init or hand it off to the scheduler?
      #_(while (and (not suspended?) (p/work-pending? this))
          (p/work! this))))

  (^clj get-hook-value [this idx]
    (p/hook-value (aget hooks idx)))

  (^clj invalidate-hook! [this idx]
    ;; (js/console.log "invalidate-hook!" idx (:component-name config) this)

    (set! dirty-hooks (bit-set dirty-hooks idx))
    (when (< idx current-idx)
      (set! current-idx idx))

    (.schedule! this))

  (^clj ready-hook! [this idx]
    ;; (js/console.log "invalidate-hook!" idx (:component-name config) this)

    (when (not= current-idx idx)
      (js/console.warn "hook become ready while not being the current?" current-idx idx this))

    (set! suspended? false)
    (.schedule! this))


  (^clj mark-hooks-dirty! [this dirty-bits]
    (set! dirty-hooks (bit-or dirty-hooks dirty-bits))
    (set! current-idx (find-first-set-bit-idx dirty-hooks)))

  (^clj mark-dirty-from-args! [this dirty-bits]
    (.mark-hooks-dirty! this dirty-bits)
    )

  (^clj set-render-required! [this]
    (set! needs-render? true))

  (^clj run-next! [^not-native this]
    ;; (js/console.log "Component:run-next!" (:component-name config) current-idx)
    (if (identical? current-idx (alength (.-hooks config)))
      ;; all hooks done
      (.component-render! this)

      ;; process hooks in order, starting at the lowest index invalidated
      (let [hook (aget hooks current-idx)]
        ;; (js/console.log "Component:run-next!" current-idx (:component-name config) (pr-str (type hook)) this)

        (cond
          ;; doesn't exist, create it
          (not hook)
          (let [^function run-fn (-> (.-hooks config) (aget current-idx) (.-run))
                val (run-fn this)
                hook (p/hook-build val this current-idx)]

            ;; (js/console.log "Component:init-hook!" (:component-name config) current-idx val hook)

            (aset hooks current-idx hook)

            (p/hook-init! hook)
            (p/perf-count! this [::hook-init! current-idx])

            (set! updated-hooks (bit-set updated-hooks current-idx))

            (when (bit-test (.-render-deps config) current-idx)
              (set! needs-render? true))

            (if (p/hook-ready? hook)
              (set! current-idx (inc current-idx))
              (.suspend! this)))

          ;; marked dirty, update it
          ;; make others dirty if actually updated
          (bit-test dirty-hooks current-idx)
          (let [hook-config (aget (.-hooks config) current-idx)

                deps-updated?
                ;; dirty hooks this depends-on should trigger an update
                ;; or changed args used by this should trigger
                (or (pos? (bit-and (.-depends-on hook-config) updated-hooks))
                    (bit-test dirty-from-args current-idx))

                ^function run (.-run hook-config)

                did-update? ;; checks if hook deps changed as well, calling init again
                (if deps-updated?
                  (p/hook-deps-update! hook (run this))
                  (p/hook-update! hook))]

            (if deps-updated?
              (p/perf-count! this [::hook-deps-update! current-idx])
              (p/perf-count! this [::hook-update! current-idx]))

            (when did-update?
              (p/perf-count! this [::hook-did-update! current-idx]))

            #_(js/console.log "Component:hook-update!"
                (:component-name config)
                current-idx
                deps-updated?
                did-update?
                hook)

            (set! dirty-hooks (bit-clear dirty-hooks current-idx))

            (when did-update?
              (set! updated-hooks (bit-set updated-hooks current-idx))
              (set! dirty-hooks (bit-or dirty-hooks (.-affects hook-config)))

              (when (bit-test (.-render-deps config) current-idx)
                (set! needs-render? true)))

            (if (p/hook-ready? hook)
              (set! current-idx (inc current-idx))
              (.suspend! this)))

          :else
          (set! current-idx (inc current-idx))))))

  (^clj unschedule! [this]
    (p/unschedule! scheduler this))

  (^clj suspend! [this hook-causing-suspend]
    ;; just in case we were already scheduled. should really track this more efficiently
    (.unschedule! this)
    (set! suspended? true))

  (^clj schedule! [this]
    (p/schedule-update! scheduler this))

  (^clj component-render! [^ComponentInstance this]
    (assert (zero? dirty-hooks) "Got to render while hooks are dirty")
    ;; (js/console.log "Component:render!" (:component-name config) updated-hooks)
    (set! updated-hooks 0)
    (set! dirty-from-args 0)
    (if-not needs-render?
      (p/perf-count! this [::render-skip])

      (let [render-fn (.-render-fn config)
            frag (render-fn this)]

        (p/perf-count! this [::render])

        (set! rendered-args args)
        (set! needs-render? false)

        ;; FIXME: let scheduler decide if frag should be applied
        (p/update! root frag)

        ;; FIXME: run dom after effects
        )))

  (^clj register-event! [this event-id callback]
    (set! events (assoc events event-id callback)))

  (^clj unregister-event! [this event-id callback]
    (set! events (dissoc events event-id)))

  (^clj get-slot [this slot-id]
    (get slots slot-id))

  (^clj set-slot! [this slot-id slot]
    (set! slots (assoc slots slot-id slot))))

(set! *warn-on-infer* true)

(defn component-create [env config args]
  (when ^boolean js/goog.DEBUG
    (when-not (instance? p/ComponentConfig config)
      (throw (ex-info "not a component definition" {:config config :props args}))))

  (doto (ManagedComponent. (::scheduler env) env nil args args config {} nil {} 0 nil 0 0 0 true false false)
    (.component-init!)))

(deftype ComponentNode [component args]
  p/IConstruct
  (as-managed [this env]
    (component-create env component args))

  IEquiv
  (-equiv [this ^ComponentNode other]
    (and (instance? ComponentNode other)
         (identical? component (.-component other))
         (= args (.-args other)))))

(defn component-node? [x]
  (instance? ComponentNode x))

(defn call-event-fn [{::keys [^ManagedComponent component] :as env} ev-id e ev-args]
  (when-not component
    (throw (ex-info "event handlers can only be used in components" {:env env :ev-id ev-id :e e :ev-args ev-args})))

  (p/run-now! (.-scheduler component) #(p/handle-event! component ev-id e ev-args)))

(defn event-attr [env node event oval [ev-id & ev-args :as nval]]

  (when ^boolean js/goog.DEBUG
    (when-not (vector? nval)
      (throw (ex-info "event handler expects a vector arg" {:event event :node node :nval nval}))))

  (let [ev-key (str "__shadow$" (name event))]
    (when-let [ev-fn (gobj/get node ev-key)]
      (.removeEventListener node (name event) ev-fn))

    ;(js/console.log "adding ev fn" val)

    (let [ev-fn #(call-event-fn env ev-id % ev-args)
          ev-opts #js {}]

      ;; FIXME: need to track if once already happened. otherwise may re-attach and actually fire more than once
      ;; but it should be unlikely to have a changing val with ^:once?
      (when-let [m (meta nval)]
        (when (:once m)
          (gobj/set ev-opts "once" true))

        (when (:passive m)
          (gobj/set ev-opts "passive" true)))

      ;; FIXME: ev-opts are not supported by all browsers
      ;; closure lib probably has something to handle that
      (.addEventListener node (name event) ev-fn ev-opts)

      (gobj/set node ev-key ev-fn))))

(a/add-attr :on-click
  (fn [env node oval nval]
    (event-attr env node :click oval nval)))

(a/add-attr :on-dblclick
  (fn [env node oval nval]
    (event-attr env node :dblclick oval nval)))

(a/add-attr :on-keydown
  (fn [env node oval nval]
    (event-attr env node :keydown oval nval)))

(a/add-attr :on-change
  (fn [env node oval nval]
    (event-attr env node :change oval nval)))

(a/add-attr :on-blur
  (fn [env node oval nval]
    (event-attr env node :blur oval nval)))

(a/add-attr :shadow.experiments.arborist/ref
  (fn [{::keys [dom-refs] :as env} node oval nval]
    (when-not dom-refs
      (throw (ex-info "ref used outside component" {:val nval :env env})))
    (when (and oval (not= oval nval))
      (swap! dom-refs dissoc oval))
    (swap! dom-refs assoc nval node)))

(deftype HookConfig [depends-on affects run])

(defn make-hook-config
  "used by defc macro, do not use directly"
  [depends-on affects run]
  {:pre [(nat-int? depends-on)
         (nat-int? affects)
         (fn? run)]}
  (HookConfig. depends-on affects run))

(defn make-component-config
  "used by defc macro, do not use directly"
  [component-name
   hooks
   opts
   check-args-fn
   render-deps
   render-fn]
  {:pre [(string? component-name)
         (array? hooks)
         (every? #(instance? HookConfig %) hooks)
         (map? opts)
         (fn? check-args-fn)
         (nat-int? render-deps)
         (fn? render-fn)]}

  (let [cfg
        (p/ComponentConfig.
          component-name
          hooks
          opts
          check-args-fn
          render-deps
          render-fn)]

    (when ^boolean js/goog.DEBUG
      (swap! components-ref assoc component-name cfg))

    cfg))

(defn get-arg ^not-native [^ManagedComponent comp idx]
  (-nth ^not-native (.-args comp) idx))

(defn check-args! [^ManagedComponent comp new-args expected]
  (assert (>= (count new-args) expected) (str "component expected at least " expected " arguments")))

(defn arg-triggers-hooks! [^ManagedComponent comp idx dirty-bits]
  (.mark-dirty-from-args! comp dirty-bits))

(defn arg-triggers-render! [^ManagedComponent comp idx]
  (.set-render-required! comp))

(defn get-dom-ref [{::keys [dom-refs] :as env} ref-id]
  (get @dom-refs ref-id))

(defn get-slot [^ManagedComponent comp slot-id]
  {:pre [(keyword? slot-id)]}
  (.get-slot comp slot-id))

(defn get-env [^ManagedComponent comp]
  (.-component-env comp))

(defn get-scheduler [^ManagedComponent comp]
  (. comp -scheduler))

(defn get-hook-value [^ManageComponent comp idx]
  (.get-hook-value comp idx))

(defn hook-invalidate! [^ManagedComponent comp idx]
  (.invalidate-hook! comp idx))

(defn hook-ready! [^ManagedComponent comp idx]
  (.ready-hook! comp idx))


(deftype EventHook
  [event-id
   ^ManagedComponent component
   idx
   ^:mutable callback]
  p/IBuildHook
  (hook-build [this c i]
    (EventHook. event-id c i callback))

  p/IHook
  (hook-init! [this]
    (.register-event! component event-id callback))
  (hook-ready? [this]
    true)
  (hook-value [this]
    (throw (ex-info "not supposed to access this directly?" {})))
  (hook-update! [this]
    ;; can't affect anything else since nothing can refer to it directly
    false)
  (hook-deps-update! [this new-val]
    (set! callback new-val)
    (.register-event! component event-id new-val))
  (hook-destroy! [this]
    (.unregister-event! component event-id)))

;; called from macro for ::event-id ...
(defn event-hook [ev-id callback]
  {:pre [(fn? callback)]}
  (EventHook. ev-id nil nil callback))

(deftype SimpleVal [^:mutable val]
  p/IHook
  (hook-init! [this])
  (hook-ready? [this] true)
  (hook-value [this] val)
  (hook-update! [this])
  (hook-deps-update! [this new-val]
    (let [updated? (not= new-val val)]
      (set! val new-val)
      updated?))
  (hook-destroy! [this]))

(extend-protocol p/IBuildHook
  default
  (hook-build [val component idx]
    (SimpleVal. val)))

(deftype SlotHook
  [slot-id ^ManagedComponent component idx node]
  p/IBuildHook
  (hook-build [this c i]
    (SlotHook. slot-id c i (common/dom-marker (get-env c))))

  p/IConstruct
  (as-managed [this env]
    ;; FIXME: throw if env is not identical to our component env?
    this)

  p/IManageNodes
  (dom-first [this] node)
  (dom-insert [this parent anchor]
    (when (.-parentNode node)
      (throw (ex-info "slot already in document" {})))

    (.insertBefore parent node anchor))

  p/IUpdatable
  (supports? [this ^SlotHook other]
    (identical? this other))

  (dom-sync! [this other])

  p/IDestructible
  (destroy! [this]
    (.remove node))

  p/IHook
  (hook-init! [this]
    (.set-slot! component slot-id this))
  (hook-ready? [this] true)
  (hook-value [this] this)
  (hook-update! [this]
    (throw (ex-info "slot can't update?" {})))
  (hook-deps-update! [this new-val]
    (throw (ex-info "slot can't update?" {})))
  (hook-destroy! [this]))

(defn slot
  ([]
   (slot :default))
  ([slot-id]
   {:pre [(keyword? slot-id)]}
   (SlotHook. slot-id nil nil nil)))

