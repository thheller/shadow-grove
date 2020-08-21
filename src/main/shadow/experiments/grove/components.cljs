(ns shadow.experiments.grove.components
  (:require-macros [shadow.experiments.grove.components])
  (:require
    [goog.object :as gobj]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.arborist.attributes :as a]
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.grove.protocols :as gp]))

(def ^{:tag boolean
       :jsdoc ["@define {boolean}"]}
  DEBUG
  (js/goog.define "shadow.experiments.grove.components.DEBUG" js/goog.DEBUG))

;; this file is an exercise in writing the least idiomatic clojure code possible
;; shield your eyes and beware!

(defonce components-ref (atom {}))
(defonce instances-ref (atom #{}))

(defn get-component [env]
  (::component env))

;; called on start for hot-reload purposes
;; otherwise components may decide to skip rendering and preventing nested UI updates
;; will be stripped in release builds
(defn mark-all-dirty! []
  (doseq [^ManagedComponent comp @instances-ref]
    (set! comp -needs-render? true)))

(declare ^{:arglists '([x])} component-init?)
(declare ComponentInit)

(defn- make-component-init [component args]
  ;; FIXME: maybe use array, never directly accessible anyways
  {:pre [(vector? args)]}
  (ComponentInit. component args))

(extend-type gp/ComponentConfig
  cljs.core/IFn
  (-invoke
    ([this]
     (make-component-init this []))
    ([this a1]
     (make-component-init this [a1]))
    ([this a1 a2]
     (make-component-init this [a1 a2]))
    ([this a1 a2 a3]
     (make-component-init this [a1 a2 a3]))
    ([this a1 a2 a3 a4]
     (make-component-init this [a1 a2 a3 a4]))
    ([this a1 a2 a3 a4 a5]
     (make-component-init this [a1 a2 a3 a4 a5]))
    ([this a1 a2 a3 a4 a5 a6]
     (make-component-init this [a1 a2 a3 a4 a5 a6]))
    ;; FIXME: add more, user should really use maps at this point
    ))

(defn component-config? [x]
  (instance? gp/ComponentConfig x))

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

(declare get-env)

(deftype EffectHook
  [^:mutable deps
   ^function ^:mutable callback
   ^:mutable callback-result
   ^boolean ^:mutable should-call?
   ^not-native ^:mutable component
   idx]

  gp/IBuildHook
  (hook-build [this c i]
    (EffectHook. deps callback callback-result should-call? c i))

  gp/IHook
  (hook-init! [this])
  (hook-ready? [this] true)
  (hook-value [this] ::effect-hook)
  (hook-update! [this] false)

  (hook-deps-update! [this ^EffectHook new]
    (assert (instance? EffectHook new))
    ;; comp-did-update! will call it
    ;; FIXME: (sg/effect :mount (fn [] ...)) is only called once ever
    ;; should it be called in case it uses other hook data?
    (set! callback (.-callback new))

    ;; run after each render
    ;; (sg/effect :render (fn [env] ...))

    ;; run once on mount, any constant really works
    ;; (sg/effect :mount (fn [env] ...))

    ;; when when [a b] changes
    ;; (sg/effect [a b] (fn [env] ....))

    (let [new-deps (.-deps new)]
      (when (not= new-deps :render)
        (set! should-call? (not= deps new-deps))
        (set! deps new-deps)))

    ;; doesn't have a usable output
    false)

  (hook-destroy! [this]
    (when (fn? callback-result)
      (callback-result)))

  gp/IHookDomEffect
  (hook-did-update! [this ^boolean did-render?]
    (when (and did-render? should-call?)
      (when (fn? callback-result)
        (callback-result))

      (set! callback-result (callback (get-env component)))

      (when (not= deps :render)
        (set! should-call? false))
      )))

(deftype ManagedComponent
  [^not-native scheduler ;; called often, need to avoid map lookup
   ^not-native ^:mutable parent-env
   ^not-native ^:mutable component-env
   ^:mutable args
   ^:mutable rendered-args
   ^ComponentConfig ^:mutable config
   ^:mutable root
   ^:mutable slots
   ^number ^:mutable current-idx
   ^array ^:mutable hooks
   ^array ^:mutable hooks-with-effects
   ^number ^:mutable dirty-from-args
   ^number ^:mutable dirty-hooks
   ^number ^:mutable updated-hooks
   ^boolean ^:mutable needs-render?
   ^boolean ^:mutable suspended?
   ^boolean ^:mutable destroyed?
   ^boolean ^:mutable dom-entered?]

  cljs.core/IHash
  (-hash [this]
    (goog/getUid this))

  p/IManaged
  (dom-first [this]
    (if DEBUG
      (.-marker-before this)
      (p/dom-first root)))

  (dom-insert [this parent anchor]
    (when DEBUG
      (.insertBefore parent (.-marker-before this) anchor))
    (p/dom-insert root parent anchor)
    (when DEBUG
      (.insertBefore parent (.-marker-after this) anchor)))

  (dom-entered! [this]
    (set! dom-entered? true)
    (p/dom-entered! root))

  (supports? [this next]
    (and (component-init? next)
         (let [other (.-component ^ComponentInit next)]
           (identical? config other))))

  (dom-sync! [this ^ComponentInit next]
    (. config (check-args-fn this args (.-args next)))
    (set! args (.-args next))
    (when (gp/work-pending? this)
      (.schedule! this)))

  (destroy! [this]
    (.unschedule! this)
    (when DEBUG
      (swap! instances-ref disj this)
      (.remove (.-marker-before this))
      (.remove (.-marker-after this)))
    (set! destroyed? true)
    (run!
      (fn [hook]
        (when hook
          (gp/hook-destroy! hook)))
      hooks)
    (p/destroy! root))

  ;; FIXME: figure out default event handler
  ;; don't want to declare all events all the time
  gp/IHandleEvents
  (handle-dom-event! [this ev-id e ev-args]
    (let [handler
          (cond
            (fn? ev-id)
            ev-id

            (keyword? ev-id)
            (or (get (.-events config) ev-id)
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
        (gp/handle-dom-event! parent ev-id e ev-args)
        (when-not (gobj/get e "shadow$handled")
          (js/console.warn "event not handled" ev-id e ev-args)))))

  (handle-event! [this ev-id ev-args]
    (let [handler
          (cond
            (keyword? ev-id)
            (or (get (.-events config) ev-id)
                (get (.-opts config) ev-id))

            :else
            (throw (ex-info "unknown event" {:ev-id ev-id :ev-args ev-args})))]

      (if handler
        (apply handler component-env ev-args)
        ;; FIXME: only bubble up until handled?
        (if-let [parent (::component parent-env)]
          (gp/handle-event! parent ev-id ev-args)
          (js/console.warn "event not handled" ev-id ev-args)))))

  gp/IWork
  (work-priority [this] 10) ;; FIXME: could allow setting this via config
  (work-depth [this] (::depth component-env))
  (work-id [this] (::component-id component-env))

  (work-pending? [this]
    (and (not destroyed?)
         (not suspended?)
         (or (pos? dirty-hooks) needs-render?)))

  (work! [this]
    (.run-next! this)

    (when-not (gp/work-pending? this)
      (gp/unschedule! scheduler this)))

  ;; FIXME: should have an easier way to tell shadow-cljs not to create externs for these
  Object
  ;; can't do this in the ComponentInit.as-managed since we need the this pointer
  (component-init! [^ComponentInstance this]
    (let [child-env
          (-> parent-env
              (update ::depth safe-inc)
              (assoc ::parent (::component parent-env))
              ;; (assoc ::component-id (str (.-component-name config) "@" (next-component-id)))
              (assoc ::component this))]

      (set! component-env child-env)

      ;; marks component boundaries in dev mode for easier inspect
      (when DEBUG
        (swap! instances-ref conj this)
        (set! (.-marker-before this)
          (doto (js/document.createComment (str "component: " (.-component-name config)))
            (set! -shadow$instance this)))
        (set! (.-marker-after this)
          (doto (js/document.createComment (str "/component: " (.-component-name config)))
            (set! -shadow$instance this))))

      (set! root (common/managed-root child-env))
      (set! current-idx (int 0))
      (set! hooks (js/Array. (alength (.-hooks config))))

      ;; FIXME: should this schedule instead?
      ;; doing as much work as possible in as-managed removes a bunch of overhead though
      (while (and (false? suspended?) (gp/work-pending? this))
        (gp/work! this))

      true))

  (get-hook-value [this idx]
    (gp/hook-value (aget hooks idx)))

  (invalidate-hook! [this idx]
    ;; (js/console.log "invalidate-hook!" idx (:component-name config) this)

    (set! dirty-hooks (bit-set dirty-hooks idx))
    (when (< idx current-idx)
      (set! current-idx idx))

    (.schedule! this))

  (ready-hook! [this idx]
    ;; (js/console.log "invalidate-hook!" idx (:component-name config) this)

    (when (not= current-idx idx)
      (js/console.warn "hook become ready while not being the current?" current-idx idx this))

    (set! suspended? false)
    (.schedule! this))

  (mark-hooks-dirty! [this dirty-bits]
    (set! dirty-hooks (bit-or dirty-hooks dirty-bits))
    (set! current-idx (find-first-set-bit-idx dirty-hooks)))

  (mark-dirty-from-args! [this dirty-bits]
    (set! dirty-from-args (bit-or dirty-from-args dirty-bits))
    (.mark-hooks-dirty! this dirty-bits))

  (set-render-required! [this]
    (set! needs-render? true))

  (run-next! [^not-native this]
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
                hook (gp/hook-build val this current-idx)]

            ;; (js/console.log "Component:init-hook!" (:component-name config) current-idx val hook)

            (aset hooks current-idx hook)

            (gp/hook-init! hook)

            (set! updated-hooks (bit-set updated-hooks current-idx))

            (when (bit-test (.-render-deps config) current-idx)
              (set! needs-render? true))

            (when (satisfies? gp/IHookDomEffect hook)
              (.push hooks-with-effects hook))

            (if (gp/hook-ready? hook)
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
                  (gp/hook-deps-update! hook (run this))
                  (gp/hook-update! hook))]

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

            (if (gp/hook-ready? hook)
              (set! current-idx (inc current-idx))
              (.suspend! this)))

          :else
          (set! current-idx (inc current-idx))))))

  (suspend! [this hook-causing-suspend]
    ;; just in case we were already scheduled. should really track this more efficiently
    (.unschedule! this)
    (gp/did-suspend! scheduler this)
    (set! suspended? true))

  (schedule! [this]
    (when-not destroyed?
      (gp/schedule-update! scheduler this)))

  (unschedule! [this]
    (gp/unschedule! scheduler this))

  (component-render! [^ComponentInstance this]
    (assert (zero? dirty-hooks) "Got to render while hooks are dirty")
    ;; (js/console.log "Component:render!" (.-component-name config) updated-hooks needs-render? suspended? destroyed? this)
    (set! updated-hooks (int 0))
    (set! dirty-from-args (int 0))

    (let [did-render? needs-render?]
      (when needs-render?
        (let [frag (. config (render-fn this))]

          (set! rendered-args args)
          (set! needs-render? false)

          (p/update! root frag)))

      (.did-update! this did-render?))

    (gp/did-finish! scheduler this))

  (did-update! [this did-render?]
    (.forEach hooks-with-effects
      (fn [^not-native item]
        (gp/hook-did-update! item did-render?))))

  (get-slot [this slot-id]
    (get slots slot-id))

  (set-slot! [this slot-id slot]
    (set! slots (assoc slots slot-id slot))))

(set! *warn-on-infer* true)

(defn component-create [env ^gp/ComponentConfig config args]
  (when ^boolean js/goog.DEBUG
    (when-not (instance? gp/ComponentConfig config)
      (throw (ex-info "not a component definition" {:config config :props args}))))

  ;; (js/console.log "component-create" (.-component-name config) args)
  (doto (ManagedComponent.
          ;; FIXME: this is way too many args, there must be a way to simplifiy
          (::gp/scheduler env)
          env ;; parent-env
          nil ;; component-env (created in component-init! since it needs this pointer)
          args
          args ;; rendered-args
          config
          nil ;; root
          {} ;; slots
          (int 0) ;; current-idx
          nil ;; hooks, array created in component-init!
          #js [] ;; hooks-with-effects (added when hooks are created)
          (int 0) ;; dirty-from-args bits
          (int 0) ;; dirty-hooks bits
          (int 0) ;; updated-hooks bits
          true ;; needs-render?
          false ;; suspended?
          false ;; destroyed?
          false ;; dom-entered?
          )
    (.component-init!)))

(deftype ComponentInit [component args]
  p/IConstruct
  (as-managed [this env]
    (component-create env component args))

  IEquiv
  (-equiv [this ^ComponentInit other]
    (and (instance? ComponentInit other)
         (identical? component (.-component other))
         (= args (.-args other)))))

(defn component-init? [x]
  (instance? ComponentInit x))

(defn call-event-fn [{::keys [^ManagedComponent component] :as env} ev-id e ev-args]
  (when-not component
    (throw (ex-info "event handlers can only be used in components" {:env env :ev-id ev-id :e e :ev-args ev-args})))

  (gp/run-now! (.-scheduler component) #(gp/handle-dom-event! component ev-id e ev-args)))

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

(a/add-attr :on-mouseenter
  (fn [env node oval nval]
    (event-attr env node :mouseenter oval nval)))

(a/add-attr :on-mousemove
  (fn [env node oval nval]
    (event-attr env node :mousemove oval nval)))

(a/add-attr :on-mouseout
  (fn [env node oval nval]
    (event-attr env node :mouseout oval nval)))

(a/add-attr :on-mouseleave
  (fn [env node oval nval]
    (event-attr env node :mouseleave oval nval)))

(deftype HookConfig [depends-on affects run])

(defn make-hook-config
  "used by defc macro, do not use directly"
  [depends-on affects run]
  {:pre [(nat-int? depends-on)
         (nat-int? affects)
         (fn? run)]}
  (HookConfig. depends-on affects run))

(deftype EffectConfig [depends-on run])

(defn make-effect-config
  "used by defc macro, do not use directly"
  [depends-on run]
  {:pre [(int? depends-on)
         (fn? run)]}
  (EffectConfig. depends-on run))

(defn make-component-config
  "used by defc macro, do not use directly"
  [component-name
   hooks
   opts
   check-args-fn
   render-deps
   render-fn
   events]
  {:pre [(string? component-name)
         (array? hooks)
         (every? #(instance? HookConfig %) hooks)
         (map? opts)
         (fn? check-args-fn)
         (nat-int? render-deps)
         (fn? render-fn)
         (map? events)]}

  (let [cfg
        (gp/ComponentConfig.
          component-name
          hooks
          opts
          check-args-fn
          render-deps
          render-fn
          events)]

    (when ^boolean js/goog.DEBUG
      (swap! components-ref assoc component-name cfg))

    cfg))

(defn get-arg ^not-native [^ManagedComponent comp idx]
  (-nth ^not-native (.-args comp) idx))

(defn check-args! [^ManagedComponent comp new-args expected]
  (assert (>= (count new-args) expected) (str "component " (. ^ComponentConfig (. comp -config) -component-name) " expected at least " expected " arguments")))

(defn arg-triggers-hooks! [^ManagedComponent comp idx dirty-bits]
  (.mark-dirty-from-args! comp dirty-bits))

(defn arg-triggers-render! [^ManagedComponent comp idx]
  (.set-render-required! comp))

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

(deftype SimpleVal [^:mutable val]
  gp/IHook
  (hook-init! [this])
  (hook-ready? [this] true)
  (hook-value [this] val)
  (hook-update! [this])
  (hook-deps-update! [this new-val]
    (let [updated? (not= new-val val)]
      (set! val new-val)
      updated?))
  (hook-destroy! [this]))

(extend-protocol gp/IBuildHook
  default
  (hook-build [val component idx]
    (SimpleVal. val)))

(deftype SlotHook
  [slot-id ^ManagedComponent component idx node]
  gp/IBuildHook
  (hook-build [this c i]
    (SlotHook. slot-id c i (common/dom-marker (get-env c))))

  p/IConstruct
  (as-managed [this env]
    ;; FIXME: throw if env is not identical to our component env?
    this)

  p/IManaged
  (dom-first [this] node)
  (dom-insert [this parent anchor]
    (when (.-parentNode node)
      (throw (ex-info "slot already in document" {})))

    (.insertBefore parent node anchor))

  (dom-entered! [this]
    (js/console.log "slot entered" this))

  (supports? [this ^SlotHook other]
    (identical? this other))

  (dom-sync! [this other])

  (destroy! [this]
    (.remove node))

  gp/IHook
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

