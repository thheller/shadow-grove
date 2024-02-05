(ns shadow.grove.components
  (:require-macros [shadow.grove.components])
  (:require
    [goog.object :as gobj]
    [shadow.cljs.modern :refer (defclass)]
    [shadow.arborist :as sa]
    [shadow.arborist.common :as common]
    [shadow.arborist.protocols :as ap]
    [shadow.arborist.attributes :as a]
    [shadow.grove.runtime :as rt]
    [shadow.grove.protocols :as gp]))

(def ^{:tag boolean
       :jsdoc ["@define {boolean}"]}
  DEBUG
  (js/goog.define "shadow.grove.components.DEBUG" js/goog.DEBUG))

;; this file is an exercise in writing the least idiomatic clojure code possible
;; shield your eyes and beware!

(defonce components-ref (atom {}))
(defonce instances-ref (atom #{}))

(set! *warn-on-infer* false)

(def ^:dynamic ^ManagedComponent *component* nil)
(def ^:dynamic ^not-native *env* nil)
(def ^:dynamic ^numeric *slot-idx* nil)
(def ^:dynamic *slot-value* ::pending)
(def ^:dynamic *claimed* nil)
(def ^:dynamic *ready* true)

(defn debug-find-roots []
  (reduce
    (fn [all instance]
      (if (::parent (.-component-env instance))
        all
        (conj all instance)))
    []
    @instances-ref))

(defn debug-component-seq
  ([]
   (debug-component-seq (first (debug-find-roots))))
  ([root]
   (tree-seq
     (fn [component]
       true)
     (fn [component]
       (.-child-components component))
     root)))

(defn debug-find-suspended []
  (->> (debug-component-seq)
       (filter #(.-suspended? %))
       (vec)))

(set! *warn-on-infer* true)

(defn get-component [env]
  (::component env))

;; called on start for hot-reload purposes
;; otherwise components may decide to skip rendering and preventing nested UI updates
;; will be stripped in release builds
(defn mark-all-dirty! []
  (doseq [^ManagedComponent comp @instances-ref]
    (.set-render-required! comp)))

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



(defclass ManagedComponent
  (field ^not-native scheduler)
  (field ^not-native parent-env)
  (field ^not-native component-env)
  (field ^ComponentConfig config)
  (field root)
  (field args)
  (field rendered-args)
  (field ^number current-idx (int 0))

  ;; lifecycle actions
  (field ^not-native on-destroy {})
  (field ^not-native after-render  {})
  (field ^not-native after-render-cleanup {})
  (field ^not-native before-render {})

  (field ^array slot-values)

  ;; using maps here since not all slots are going to have refs/cleanup
  (field ^not-native slot-refs {})
  (field ^not-native slot-cleanup {})

  (field ^number dirty-from-args (int 0))
  (field ^number dirty-slots (int 0))
  (field ^number updated-slots (int 0))
  (field ^boolean needs-render? true) ;; initially needs a render
  (field ^boolean suspended? false)
  (field ^boolean destroyed? false)
  (field ^boolean error? false)
  (field ^boolean dom-entered? false)
  (field work-set (js/Set.)) ;; sub-tree pending work

  (constructor [this e c a]
    (set! parent-env e)
    (set! config c)
    (set! args a)
    (set! scheduler (::scheduler parent-env))
    (when DEBUG
      ;; only keeping this info for debugging purposes currently, don't think its needed otherwise
      ;; use js/Set since it always maintains insertion order which makes debugging easier
      (set! this -child-components (js/Set.))
      (when-some [parent (::component parent-env)]
        (.. parent -child-components (add this))))

    (set! component-env
      (-> parent-env
          (update ::depth safe-inc)
          (assoc ::parent (::component parent-env)
                 ::ap/dom-event-handler this
                 ::component this
                 ::event-target this
                 ::scheduler this)))

    ;; marks component boundaries in dev mode for easier inspect
    (when DEBUG
      (swap! instances-ref conj this)
      (set! (.-marker-before this)
        (doto (js/document.createComment (str "component: " (.-component-name config)))
          (set! -shadow$instance this)))
      (set! (.-marker-after this)
        (doto (js/document.createComment (str "/component: " (.-component-name config)))
          (set! -shadow$instance this))))

    (set! root (common/managed-root component-env))
    ;; marking everything dirty to ensure all slots run
    (set! dirty-slots (.-slot-init-bits config))
    (set! slot-values (js/Array. (alength (.-slots config)))))

  cljs.core/IHash
  (-hash [this]
    (goog/getUid this))

  ap/IManaged
  (dom-first [this]
    (if DEBUG
      (.-marker-before this)
      (ap/dom-first root)))

  (dom-insert [this parent anchor]
    (when DEBUG
      (.insertBefore parent (.-marker-before this) anchor))
    (ap/dom-insert root parent anchor)
    (when DEBUG
      (.insertBefore parent (.-marker-after this) anchor)))

  (dom-entered! [this]
    (set! dom-entered? true)
    (when-not error?
      (ap/dom-entered! root)
      ;; trigger first on mount
      (.did-update! this true)))

  (supports? [this next]
    (and (not error?) ;; do not try to update an error component, prefer remount
         (component-init? next)
         (let [other (.-component ^ComponentInit next)]
           (identical? config other))
         ;; (defc ui-thing [^:stable ident] ...)
         ;; should cause unmount on changing ident
         (let [stable-args (-> config .-opts (get ::stable-args))]
           (or (nil? stable-args)
               (let [old-args args
                     new-args (.-args next)]
                 (every? #(= (nth old-args %) (nth new-args %)) stable-args))))
         (let [custom-check (-> config .-opts (get :supports?))]
           (or (nil? custom-check)
               (custom-check args (.-args next))))))

  (dom-sync! [this ^ComponentInit next]
    (. config (check-args-fn this args (.-args next)))
    (set! args (.-args next))
    (when (.work-pending? this)
      (.schedule! this ::dom-sync!)))

  (destroy! [this ^boolean dom-remove?]
    (.unschedule! this)
    (when DEBUG
      (swap! instances-ref disj this)

      (when-some [parent (::parent component-env)]
        (.. parent -child-components (delete this)))

      (when dom-remove?
        (.remove (.-marker-before this))
        (.remove (.-marker-after this))))

    (set! destroyed? true)

    (reduce-kv
      (fn [_ ref callback]
        (callback @ref))
      nil
      slot-cleanup)

    ;; cleanup fns returned by sg/effect fns
    (reduce-kv
      (fn [_ ref callback]
        (callback))
      nil
      after-render-cleanup)

    (ap/destroy! root dom-remove?))

  ;; FIXME: figure out default event handler
  ;; don't want to declare all events all the time
  gp/IHandleEvents
  (handle-event! [this {ev-id :e :as ev-map} e origin]
    (let [handler
          (if (keyword? ev-id)
            (or (get (.-events config) ev-id)
                (get (.-opts config) ev-id))

            (throw (ex-info "unknown event" {:event ev-map})))]

      (if handler
        (handler component-env ev-map e origin)

        ;; no handler, try parent
        (if-some [parent (::event-target parent-env)]
          (gp/handle-event! parent ev-map e origin)
          (js/console.warn "event not handled" ev-id ev-map)))))

  gp/IScheduleWork
  (did-suspend! [this work-task]
    (gp/did-suspend! scheduler work-task))

  (did-finish! [this work-task]
    (gp/did-finish! scheduler work-task))

  (schedule-work! [this work-task trigger]
    (when (zero? (.-size work-set))
      (gp/schedule-work! scheduler this trigger))

    (.add work-set work-task))

  (unschedule! [this work-task]
    (.delete work-set work-task)

    (when (zero? (.-size work-set))
      (gp/unschedule! scheduler this)))

  (run-now! [this callback trigger]
    (gp/run-now! scheduler callback trigger))

  ;; parent tells us to work
  gp/IWork
  (work! [this]
    (try
      ;; always complete our own work first
      ;; a re-render may cause the child tree to change
      ;; and maybe some work to disappear
      ;; work-pending? checks error?, no need to check that again
      (while ^boolean (.work-pending? this)
        (.run-next! this))

      (catch :default ex
        (.handle-error! this ex)))

    ;; FIXME: only process children when this is done and not suspended?
    ;; FIXME: only process children when we are not in error state?
    ;; child tasks should handle their own errors?
    (try
      (let [iter (.values work-set)]
        (loop []
          (let [current (.next iter)]
            (when (not ^boolean (.-done current))
              (gp/work! ^not-native (.-value current))

              ;; should time slice later and only continue work
              ;; until a given time budget is consumed
              (recur)))))
      (catch :default ex
        ;; FIXME: actually treat sub-tree errors different that our own?
        (.handle-error! this ex)))

    js/undefined)

  ;; FIXME: should have an easier way to tell shadow-cljs not to create externs for these
  Object
  (handle-error! [this ex]
    (set! error? true)
    (.unschedule! this)

    (let [err-fn (::error-handler parent-env)]
      (err-fn this ex)))

  (get-slot-value [this idx]
    (aget slot-values idx))

  (set-slot-cleanup! [this ref callback]
    (set! slot-cleanup (assoc slot-cleanup ref callback)))

  (get-slot-ref [this idx]
    (or (get slot-refs idx)
        ;; FIXME: maybe custom atom type that links back to component?
        (let [ref (atom nil)]
          ;; watch atom so that any update will cause the slot to run again
          ;; and potentially re-render the component
          (add-watch ref this
            (fn [_ _ old new]
              ;; only actually invalidate when the slot isn't currently running
              (when (not= *slot-idx* idx)
                (when (not= old new)
                  (.invalidate-slot! this idx)
                  ))))
          (set! slot-refs (assoc slot-refs idx ref))
          ref
          )))

  (invalidate-slot! [this idx]
    ;; (js/console.log "invalidate-slot!" idx current-idx (.-component-name config) this)

    (set! dirty-slots (bit-set dirty-slots idx))

    ;; don't set higher when currently at lower index, would otherwise skip work
    (set! current-idx (js/Math.min idx current-idx))

    ;; always need to resume so the invalidated slots can do work
    ;; could check if actually suspended but no need
    (set! suspended? false)

    (.schedule! this ::slot-invalidate!))

  (mark-slots-dirty! [this dirty-bits]
    (set! dirty-slots (bit-or dirty-slots dirty-bits))
    (set! current-idx (find-first-set-bit-idx dirty-slots)))

  (mark-dirty-from-args! [this dirty-bits]
    (set! dirty-from-args (bit-or dirty-from-args dirty-bits))
    (.mark-slots-dirty! this dirty-bits))

  (set-render-required! [this]
    (set! needs-render? true)
    (set! current-idx (js/Math.min current-idx (alength (.-slots config))))
    js/undefined)

  (add-after-render-effect [this key callback]
    (set! after-render (assoc after-render key callback))
    this)

  (add-after-render-effect-once [this key callback]
    (set! after-render
      (assoc after-render
        key
        (fn [env]
          (set! after-render (dissoc after-render key))
          (callback env))))
    this)

  (run-slot! [^not-native this idx]
    ;; when marked dirty run fn, otherwise just skip slot
    (if-not (bit-test dirty-slots idx)
      (set! current-idx (inc current-idx))

      (let [slot-config
            (-> (.-slots config) (aget idx))

            prev-val
            (aget slot-values idx)]

        ;; not using binding because we don't need previous value capture
        ;; it is an error if this ever runs nested

        (set! *component* this)
        (set! *env* component-env)
        (set! *slot-idx* idx)
        (set! *slot-value* prev-val)
        (set! *claimed* false)
        (set! *ready* true)

        (try
          (let [val (.run slot-config this)]

            (aset slot-values idx val)

            ;; FIXME: suspense should really be tracked elsewhere
            ;; stops processing slots until invalidated and resuming
            (if-not *ready*
              (.suspend! this idx)
              ;; clear dirty bit
              (do (set! dirty-slots (bit-clear dirty-slots idx))

                  ;; make others dirty if actually updated
                  (when (not= val prev-val)
                    (set! updated-slots (bit-set updated-slots idx))

                    (set! dirty-slots (bit-or dirty-slots (.-affects slot-config)))

                    (when (bit-test (.-render-deps config) idx)
                      (set! needs-render? true)))

                  (set! current-idx (inc current-idx))
                  )))

          (finally
            (set! *component* nil)
            (set! *ready* true)
            (set! *env* nil)
            (set! *slot-idx* nil)
            (set! *slot-value* ::undefined)
            (set! *claimed* false)))))

    js/undefined)

  (run-next! [^not-native this]
    (if (identical? current-idx (alength (.-slots config)))
      ;; all slots done
      (.component-render! this)
      (.run-slot! this current-idx))

    js/undefined)

  (work-pending? [this]
    (and (not destroyed?)
         (not suspended?)
         (not error?)
         (or (pos? dirty-slots)
             needs-render?
             (>= (alength (.-slots config)) current-idx))))

  (suspend! [this hook-causing-suspend]
    ;; (js/console.log "suspending" hook-causing-suspend this)

    ;; just in case we were already scheduled. should really track this more efficiently
    (.unschedule! this)
    (gp/did-suspend! scheduler this)
    (set! suspended? true))

  (schedule! [this trigger]
    (when-not destroyed?
      (gp/schedule-work! scheduler this trigger)))

  (unschedule! [this]
    (gp/unschedule! scheduler this))

  (component-render! [^ManagedComponent this]
    (assert (zero? dirty-slots) "Got to render while slots are dirty")
    ;; (js/console.log "Component:render!" (.-component-name config) updated-slots needs-render? suspended? destroyed? this)
    (set! updated-slots (int 0))
    (set! dirty-from-args (int 0))

    (let [did-render? needs-render?]
      (when needs-render?
        (let [frag (. config (render-fn this))]

          (set! rendered-args args)
          (set! needs-render? false)

          (ap/update! root frag)))

      ;; only trigger dom effects when mounted
      (when dom-entered?
        (.did-update! this did-render?)))

    ;; must keep this for work scheduling so it knows its done
    (set! current-idx (inc current-idx))

    (gp/did-finish! scheduler this)
    (.unschedule! this))

  (did-update! [this did-render?]
    (reduce-kv
      (fn [_ key callback]
        (when-some [^function x (get after-render-cleanup key)]
          (x)
          (set! after-render-cleanup (dissoc after-render-cleanup key)))

        (let [result (callback component-env)]
          (when (fn? result)
            (set! after-render-cleanup (assoc after-render-cleanup key result))))

        nil)
      nil
      after-render)

    js/undefined))

;; FIXME: no clue why I can't put this in ManagedComponent directly
;; compiler complains with undeclared var ManagedComponent
;; probably something in defclass I missed

(extend-type ManagedComponent
  ap/IHandleDOMEvents
  (validate-dom-event-value! [this env event ev-value]
    (when-not (or (keyword? ev-value) (map? ev-value))
      (throw
        (ex-info
          (str "event: " event " expects a map or keyword value")
          {:event event :value ev-value}))))

  ;; event is "click" for :on-click etc which we just drop
  (handle-dom-event! [this event-env event ev-value dom-event]
    (let [ev-map
          (if (map? ev-value)
            ev-value
            {:e ev-value})]

      ;; (js/console.log "dom-event" this event-env event ev-map dom-event)
      (gp/run-now! (.-scheduler this)
        #(gp/handle-event! this ev-map dom-event event-env)
        ::handle-dom-event!))))

(set! *warn-on-infer* true)

(defn component-create [env ^gp/ComponentConfig config args]
  (when ^boolean js/goog.DEBUG
    (when-not (instance? gp/ComponentConfig config)
      (throw (ex-info "not a component definition" {:config config :props args}))))

  (doto (ManagedComponent. env config args)
    ;; do as much work as possible now
    ;; only go async when suspended
    (gp/work!)))

(deftype ComponentInit [component args]
  ap/IConstruct
  (as-managed [this env]
    (component-create env component args))

  IEquiv
  (-equiv [this ^ComponentInit other]
    (and (instance? ComponentInit other)
         (identical? component (.-component other))
         (= args (.-args other)))))

(defn component-init? [x]
  (instance? ComponentInit x))

(deftype SlotConfig [depends-on affects run])

(defn make-slot-config
  "used by defc macro, do not use directly"
  [depends-on affects run]
  {:pre [(nat-int? depends-on)
         (nat-int? affects)
         (fn? run)]}
  (SlotConfig. depends-on affects run))

(defn make-component-config
  "used by defc macro, do not use directly"
  [component-name
   slots
   slot-dirty-bits
   opts
   check-args-fn
   render-deps
   render-fn
   events]
  {:pre [(string? component-name)
         (array? slots)
         (every? #(instance? SlotConfig %) slots)
         (map? opts)
         (fn? check-args-fn)
         (nat-int? render-deps)
         (fn? render-fn)
         (map? events)]}

  (let [cfg
        (gp/ComponentConfig.
          component-name
          slots
          slot-dirty-bits
          opts
          check-args-fn
          render-deps
          render-fn
          events)]

    (when ^boolean js/goog.DEBUG
      (swap! components-ref assoc component-name cfg))

    cfg))

;; these are called by defc macro, do not delete!
;; cursive marks these as unused

(defn get-arg ^not-native [^ManagedComponent comp idx]
  (-nth ^not-native (.-args comp) idx))

(defn check-args! [^ManagedComponent comp new-args expected]
  (assert (>= (count new-args) expected) (str "component " (. ^ComponentConfig (. comp -config) -component-name) " expected at least " expected " arguments")))

(defn arg-triggers-slots! [^ManagedComponent comp idx dirty-bits]
  (.mark-dirty-from-args! comp dirty-bits))

(defn arg-triggers-render! [^ManagedComponent comp idx]
  (.set-render-required! comp))

(defn get-slot-value [^ManagedComponent comp idx]
  (.get-slot-value comp idx))

(defn get-events [^ManagedComponent comp]
  ;; FIXME: ... loses typehints?
  (. ^clj (. comp -config) -events))

(defn get-parent [^ManagedComponent comp]
  (get-component (. comp -parent-env)))

(defn get-component-name [^ManagedComponent comp]
  (. ^clj (. comp -config) -component-name))

(defn claim-bind! [claim-id]
  (when-not *component*
    (throw (ex-info "can only be used in component bind" {})))

  (if-not *claimed*
    (set! *claimed* claim-id)
    (throw (ex-info "slot already claimed" {:idx *slot-idx* :claimed *claimed* :attempt claim-id})))

  (.get-slot-ref *component* *slot-idx*))

(defn set-cleanup! [ref callback]
  (when-not *component*
    (throw (ex-info "can only be used in component bind" {})))

  (.set-slot-cleanup! *component* ref callback))

(defn slot-effect [deps callback]
  (let [ref (claim-bind! ::slot-effect)]

    (case deps
      :render
      ;; ref used as key, so we update the callback when called again
      (.add-after-render-effect *component* ref callback)

      :mount
      (when-not @ref
        (.add-after-render-effect-once *component* ref callback)
        (reset! ref :mount))

      ;; else
      (let [prev-deps @ref]
        (when (not= prev-deps deps)
          (.add-after-render-effect-once *component* ref callback))
        ))

    nil))

(defn env-watch [key-to-atom path-in-atom default]
  (let [ref
        (claim-bind! ::env-watch)

        {prev-atom :the-atom :as state}
        @ref

        the-atom
        (get *env* key-to-atom)]

    (set-cleanup! ref
      (fn [{:keys [the-atom]}]
        (remove-watch the-atom ref)))

    (when-not the-atom
      (throw (ex-info "couldn't find to watch in env" {:key key-to-atom})))

    (when-not (identical? the-atom prev-atom)
      (when prev-atom
        (remove-watch prev-atom ref))

      (add-watch the-atom ref
        (fn [_ _ old new]
          ;; use latest ref values, they may have changed
          (let [{:keys [path-in-atom default] :as state} @ref
                oval (get-in old path-in-atom default)
                nval (get-in new path-in-atom default)]

            (when (not= oval nval)
              ;; swap triggers component watch and invalidates
              ;; on next run we only return the new value unless other args changed
              ;; :value never actually used, just acts as invalidator
              (swap! ref assoc :value nval)))))

      (swap! ref assoc :the-atom the-atom))

    ;; always update these so the watch above has the latest
    ;; env-watch is only called again when something changes
    (swap! ref assoc :path-in-atom path-in-atom :default default)

    (get-in @the-atom path-in-atom default)
    ))

(defn atom-watch [the-atom access-fn]
  (let [ref
        (claim-bind! ::atom-watch)

        {prev-atom :the-atom :as state}
        @ref]

    (set-cleanup! ref
      (fn [{:keys [the-atom]}]
        (remove-watch the-atom ref)))

    (when-not (identical? the-atom prev-atom)
      (when prev-atom
        (remove-watch prev-atom ref))

      (add-watch the-atom ref
        (fn [_ _ old new]
          ;; use latest ref values, they may have changed
          (let [{:keys [access-fn]} @ref
                oval (access-fn old)
                nval (access-fn new)]

            (when (not= oval nval)
              ;; swap triggers component watch and invalidates
              ;; on next run we only return the new value unless other args changed
              ;; :value never actually used, just acts as invalidator
              (swap! ref assoc :value nval)))))

      (swap! ref assoc :the-atom the-atom))

    ;; always update these so the watch above has the latest
    ;; atom-watch is only called again when something changes
    (swap! ref assoc :access-fn access-fn)

    (access-fn @the-atom)
    ))

(defn track-change
  [val trigger-fn]
  (let [ref
        (claim-bind! ::track-change)

        {prev-val :val
         prev-result :result}
        @ref]

    (if (= prev-val val)
      prev-result
      (let [result (trigger-fn *env* prev-val val)]
        (swap! ref assoc :val val :result result)
        result))))