(ns shadow.grove
  "grove - a small wood or forested area (ie. trees)
   a mini re-frame/fulcro hybrid. re-frame event styles + somewhat normalized db"
  (:require-macros [shadow.grove])
  (:require
    [clojure.core.protocols :as cp]
    [goog.async.nextTick]
    [shadow.arborist.protocols :as ap]
    [shadow.arborist.common :as common]
    [shadow.arborist.fragments] ;; util macro references this
    [shadow.arborist :as sa]
    [shadow.arborist.collections :as sc]
    [shadow.grove.protocols :as gp]
    [shadow.grove.runtime :as rt]
    [shadow.grove.components :as comp]
    [shadow.grove.ui.util :as util]
    [shadow.grove.ui.suspense :as suspense]
    [shadow.grove.ui.atoms :as atoms]
    [shadow.grove.ui.portal :as portal]
    [shadow.arborist.attributes :as a]
    [shadow.grove.kv :as kv]
    [shadow.grove.impl :as impl]
    [shadow.grove.trace :as trace]
    [shadow.css] ;; used in macro ns
    ))

(set! *warn-on-infer* false)

(defn vec-conj [x y]
  (if (nil? x) [y] (conj x y)))

;; used by shadow.grove.preload to funnel debug messages to devtools
(def dev-log-handler nil)

(defn dispatch-up! [{::comp/keys [^not-native parent] :as env} ev-map]
  {:pre [(map? env)
         (map? ev-map)
         (qualified-keyword? (:e ev-map))]}
  ;; FIXME: should schedule properly when it isn't in event handler already
  (gp/handle-event! parent ev-map nil env))

;; the idea is that query (and potentially others) can call suspend!
;; to let runtime know that data it required was still loading
;; components will stop processing slots that suspend and will resume once the slot self invalidates
;; FIXME: suspend is currently only checked while mounting
;; but the idea with default-return is so that while suspended it keeps returning the previous value
;; so that it at least can still proceed with the old value without the user having to hold on to that
(defn suspend!
  ([]
   (suspend! nil))
  ([default-return]
   (set! rt/*ready* false)
   (or rt/*slot-value* default-return)))

(defn query
  [query-fn & args]
  (impl/slot-query args query-fn))

(defn kv-lookups [kv-table keys]
  (impl/slot-query nil
    (fn [env]
      (select-keys (get env kv-table) keys))))

(defn kv-lookup
  ([kv-table]
   (impl/slot-kv-get kv-table))
  ([kv-table key]
   (impl/slot-kv-lookup kv-table key))
  ;; (sg/kv-lookup :db 1 :foo)
  ;; bit more convenient than
  ;; (:foo (sg/kv-lookup :db 1))
  ([kv-table key & path]
   (get-in (kv-lookup kv-table key) path)))

(defn use-state
  ([]
   (impl/slot-state {} nil))
  ([init-state]
   {:pre [(satisfies? IMeta init-state)]}
   (impl/slot-state init-state nil))
  ([init-state merge-fn]
   {:pre [(satisfies? IMeta init-state)
          (fn? merge-fn)]}
   (impl/slot-state init-state merge-fn)))

(defn swap-state! [state update-fn & args]
  (let [ref (::impl/ref (meta state))]
    (when-not ref
      (throw (ex-info "can only swap-state! things created via use-state" {:thing state})))
    (swap! ref
      (fn [state]
        (apply update-fn state args)))))

(defn run
  [env other-tx]
  (let [process-fn (::tx (meta other-tx))]
    (when-not process-fn
      (throw (ex-info "invalid run call, expected a deftx result" {:other-tx other-tx})))
    (-> env
        ;; remember which other event was processed during tx
        (update ::chain vec-conj other-tx)
        (process-fn other-tx (::dom-event env)))))

(defn run-tx
  ([{::keys [runtime-ref] :as env} tx]
   (impl/process-event runtime-ref tx nil env))
  ([{::keys [runtime-ref] :as env} tx dom-event]
   (impl/process-event runtime-ref tx dom-event env)))

(defn run-tx! [runtime-ref tx]
  (assert (rt/ref? runtime-ref) "expected runtime ref?")
  (let [{::keys [scheduler]} @runtime-ref]
    (let [tx (cond
               (fn? tx) (with-meta {:e ::fn!} {::tx tx})
               (and (map? tx) (keyword? (:e tx))) tx
               :else (throw (js/Error. "run-tx! only accepts functions or maps as tx argument")))]

      (impl/process-event runtime-ref tx nil nil)
      )))

(defn unmount-root [^js root-el]
  (when-let [^sa/TreeRoot root (.-sg$root root-el)]
    (.destroy! root true)
    (js-delete root-el "sg$root")
    (js-delete root-el "sg$env")))

(defn watch
  "watches an atom and triggers an update on change
   accepts an optional path-or-fn arg that can be used for quick diffs

   (watch the-atom [:foo])
   (watch the-atom (fn [old new] ...))"
  ([watchable]
   (watch watchable identity))
  ([watchable path-or-fn]
   ;; more relaxed check than (instance? Atom the-atom), to support custom types
   ;; impl calls add-watch/remove-watch and does a deref
   {:pre [(satisfies? IWatchable watchable)
          (satisfies? IDeref watchable)]}
   (if (vector? path-or-fn)
     (comp/atom-watch watchable (fn [val] (get-in val path-or-fn)))
     (comp/atom-watch watchable path-or-fn))))

(defn env-watch
  ([key-to-atom]
   (env-watch key-to-atom [] nil))
  ([key-to-atom path]
   (env-watch key-to-atom path nil))
  ([key-to-atom path default]
   {:pre [(keyword? key-to-atom)
          (vector? path)]}
   (comp/env-watch key-to-atom path default)))

(defn suspense [opts vnode]
  (suspense/SuspenseInit. opts vnode))

(defn simple-seq [coll render-fn]
  (sc/simple-seq coll render-fn))

(defn keyed-seq [coll key-fn render-fn]
  (sc/keyed-seq coll key-fn render-fn))

(defn track-change
  "(bind x
     (sg/track-change val
       (fn [env old new prev-result]
         ...))

   only calls trigger-fn if val has changed, even if trigger-fn itself may have changed
   calls (trigger-fn env nil val) on mount
   return value is used for bind (i.e. x above)
   calls (trigger-fn env prev-val val prev-result) when val changed between renders

   env is component environment"
  [val trigger-fn]
  (comp/track-change val trigger-fn))

;; using volatile so nobody gets any ideas about add-watch
;; pretty sure that would cause havoc on the entire rendering
;; if sometimes does work immediately on set before render can even complete
(defn ref []
  (volatile! nil))


(defn effect
  "calls (callback env) after render when provided deps argument changes
   callback can return a function which will be called if cleanup is required"
  [deps callback]
  {:pre [(fn? callback)]}
  (comp/slot-effect deps callback))

(defn render-effect
  "call (callback env) after every render"
  [callback]
  {:pre [(fn? callback)]}
  (comp/slot-effect :render callback))

(defn mount-effect
  "call (callback env) on mount once"
  [callback]
  {:pre [(fn? callback)]}
  (comp/slot-effect :mount callback))

;; FIXME: does this ever need to take other options?
(defn portal
  ([body]
   (portal/portal js/document.body body))
  ([ref-node body]
   (portal/portal ref-node body)))

(defn default-error-handler [component ex]
  ;; FIXME: this would be the only place there component-name is accessed
  ;; without this access closure removes it completely in :advanced which is nice
  ;; ok to access in debug builds though
  (if ^boolean js/goog.DEBUG
    (js/console.error (str "An Error occurred in " (.. component -config -component-name) ", it will not be rendered.") component)
    (js/console.error "An Error occurred in Component, it will not be rendered." component))
  (js/console.error ex))

(deftype RootEventTarget [rt-ref]
  gp/IHandleEvents
  (handle-event! [this ev-map e origin]
    (impl/process-event rt-ref ev-map e origin)))

(defn- make-root-env
  [rt-ref root-el]

  ;; FIXME: have a shared root scheduler rt-ref
  ;; multiple roots should schedule in some way not indepdendently
  (let [event-target
        (RootEventTarget. rt-ref)

        env-init
        (::env-init @rt-ref)]

    (reduce
      (fn [env init-fn]
        (init-fn env))

      ;; base env, using init-fn to customize
      {::scheduler (::scheduler @rt-ref)
       ::comp/event-target event-target
       ::suspense-keys (atom {})
       ::root-el root-el
       ::runtime-ref rt-ref
       ;; FIXME: get this from rt-ref?
       ::comp/error-handler default-error-handler}

      env-init)))

(defn render* [rt-ref ^js root-el root-node]
  {:pre [(rt/ref? rt-ref)]}
  (if-let [active-root (.-sg$root root-el)]
    (do (when ^boolean js/goog.DEBUG
          (comp/mark-all-dirty!))

        ;; FIXME: somehow verify that env hasn't changed
        ;; env is supposed to be immutable once mounted, but someone may still modify rt-ref
        ;; but since env is constructed on first mount we don't know what might have changed
        ;; this is really only a concern for hot-reload, apps only call this once and never update

        (sa/update! active-root root-node)
        ::updated)

    (let [new-env (make-root-env rt-ref root-el)
          new-root (sa/dom-root root-el new-env)]
      (sa/update! new-root root-node)
      (swap! rt-ref update ::roots conj new-root)
      (set! (.-sg$root root-el) new-root)
      (set! (.-sg$env root-el) new-env)
      ::started)))

(defn render [rt-ref ^js root-el root-node]
  {:pre [(rt/ref? rt-ref)]}
  (let [t (trace/render-root)
        res (render* rt-ref root-el root-node)]
    (trace/render-root-done t)

    res))

(deftype RootScheduler [^:mutable update-pending? work-set]
  gp/IScheduleWork
  (schedule-work! [this work-task trigger]
    (.add work-set work-task)

    (when-not update-pending?
      (set! update-pending? true)
      (rt/microtask #(.microtask-start this trigger))))

  (unschedule! [this work-task]
    (.delete work-set work-task))

  (did-suspend! [this target])
  (did-finish! [this target])

  Object
  (microtask-start [this trigger]
    (let [t (trace/run-microtask this trigger)]
      (try
        (.process-work! this trigger)
        (finally
          (trace/run-microtask-done this trigger t))))

    js/undefined)

  (process-work! [this trigger]
    (let [t (trace/run-work this trigger)]
      (try
        (let [iter (.values work-set)]
          (loop []
            (let [current (.next iter)]
              (when (not ^boolean (.-done current))
                ;; kick off work from scheduler root, going down through components and their schedulers
                (gp/work! ^not-native (.-value current))

                (recur)))))

        js/undefined

        (finally
          (trace/run-work-done this trigger t)
          (set! update-pending? false))))

    js/undefined))

;; using datafy/nav tools (e.g. Inspect) can jump between tables with this
;; makes it slightly nicer to browse/explore

;; this is all basically free, so keeping it in release builds for now
;; build report shows a 0.3kb gzip size difference
;; it is however never used, so might as well remove it

(defn make-kv-navigable [init-data]
  ;; a bit of nested madness. trying to keep all nav related things only active
  ;; when navigating from :shadow.grove.kv itself. nav from a table can't follow references
  ;; if it does not have a kv container map itself. nav should only
  ;; use the data from that kv map instance and not get a new snapshot from data-ref
  ;; since that can lead to inconsistencies, e.g. something already being removed.
  (with-meta init-data
    {`cp/nav
     (fn [kv table-id table]
       ;; nav in table, kv only contains other tables
       (vary-meta table assoc
         `cp/nav
         (fn [table entry-key v]
           ;; only navigate further on maps for now
           (if-not (map? v)
             v
             ;; nav in entity map
             (vary-meta v assoc
               `cp/nav
               (fn [entry k v]
                 ;; e.g. :runtime-id nil, can't navigate further if value was nil
                 (if (nil? v)
                   v
                   (let [fk-reference (some-> (meta table) ::kv/config :attrs (get k) :references)]
                     (if-not fk-reference
                       v
                       (let [table (cp/nav kv fk-reference (get kv fk-reference))]
                         (cp/nav table v (get table v)))
                       )))))))))}))

(defn- prepare [app-id]
  (when (get @rt/known-runtimes-ref app-id)
    (throw
      (ex-info
        (str "app " app-id " already registered!")
        {:app-id app-id})))

  (let [root-scheduler
        (RootScheduler. false (js/Set.))

        rt-ref
        (atom
          {::runtime true
           ::roots #{}
           ::scheduler root-scheduler
           ::app-id app-id
           ::kv (make-kv-navigable {})
           ::event-config {}
           ::event-interceptors [impl/kv-interceptor]
           ::event-error-handler
           (fn [env ev origin ex]
             ;; re-throwing is total shit in JS, so instead just use console
             ;; if users want to handle this differently they can replace this fn
             (js/console.error "--- FAILED TO PROCESS EVENT ---" ev)
             (js/console.error ex)
             ::failed!)
           ::fx-config {}
           ::timeouts {}
           ::env-init []})]

    (swap! rt-ref update ::fx-config merge
      {::timeout
       (fn [env {:keys [timeout-id timeout ev]}]
         (let [tid (js/setTimeout
                     (fn []
                       (swap! rt-ref update ::timeouts dissoc timeout-id)
                       (run-tx! rt-ref ev))
                     timeout)]

           (swap! rt-ref update ::timeouts assoc timeout-id tid)))

       ::timeout-clear
       (fn [env {:keys [timeout-id]}]
         (when-some [tid (get-in @rt-ref [::timeouts timeout-id])]
           (js/clearTimeout tid)
           (swap! rt-ref update ::timeouts dissoc timeout-id)
           ))})

    (swap! rt/known-runtimes-ref assoc app-id rt-ref)

    rt-ref))

(defn get-runtime [app-id]
  (cond
    (keyword? app-id)
    (or (get @rt/known-runtimes-ref app-id)
        (prepare app-id))

    (rt/ref? app-id)
    app-id

    :else
    (throw (ex-info "invalid app-id" {:app-id app-id}))))

;; for convenience allowing these to use runtime keywords
;; instead of only rt-ref. saves user having to juggle the rt-ref too much
;; since events may be spread between many namespaces
(defn reg-event
  ([ev-id handler-fn]
   (reg-event :default ev-id handler-fn))
  ([app-id ev-id handler-fn]
   {:pre [(keyword? ev-id)
          (ifn? handler-fn)]}
   (let [rt-ref (get-runtime app-id)]
     (swap! rt-ref assoc-in [::event-config ev-id] handler-fn)
     rt-ref)))

(defn queue-fx [env fx-id fx-val]
  (update env ::fx vec-conj [fx-id fx-val]))

(defn reg-fx
  [rt-ref fx-id handler-fn]
  (swap! rt-ref assoc-in [::fx-config fx-id] handler-fn)
  rt-ref)


(defn add-animation-callbacks [anim callbacks]
  (reduce-kv
    (fn [_ key val]
      (case key
        :on-finish
        (.addEventListener anim "finish" val)

        :on-cancel
        (.addEventListener anim "cancel" val)

        :on-remove
        (.addEventListener anim "remove" val)

        (throw (ex-info (str "unknown animate callback " key) {:key key :val val}))
        ))
    nil
    callbacks)
  anim)

(defn animate
  "helper for Element.animate Web Animations API, saves manually repeating clj->js"
  ([^js node keyframes options]
   (animate node keyframes options nil))
  ([^js node keyframes options callbacks]
   (doto (.animate node (clj->js keyframes) (clj->js options))
     (add-animation-callbacks callbacks))))

(deftype PreparedAnimation [keyframes options]
  cljs.core/IFn
  (-invoke [this node]
    (.animate node keyframes options))
  (-invoke [this node callbacks]
    (doto (.animate node keyframes options)
      (add-animation-callbacks callbacks))))

;; JS doesn't allow "prepping" an animation without a node reference
;; I prefer an API that lets me def an animation and then apply it to a node on demand
(defn prepare-animation [keyframes options]
  (->PreparedAnimation (clj->js keyframes) (clj->js options)))


(defn check-unmounted! [rt-ref]
  (when (seq (::roots @rt-ref))
    (throw (ex-info "operation not allowed, runtime already mounted" {:rt-ref rt-ref}))))

;; these are only supposed to run once in init
;; will overwrite with no attempts at merging
(defn add-kv-table
  ([rt-ref kv-table config]
   (add-kv-table rt-ref kv-table config {}))
  ([rt-ref kv-table config init-data]
   (swap! rt-ref assoc-in [::kv kv-table]
     (kv/init kv-table config init-data))
   rt-ref))

;; just more convenient to do this directly
(defn kv-init
  [rt-ref init-fn]
  (check-unmounted! rt-ref)
  (swap! rt-ref update ::kv init-fn)
  rt-ref)

(defn valid-interceptor? [x]
  (fn? x))

;; FIXME: exposing this here to that users don't need to require impl namespace when using interceptors
(def kv-interceptor impl/kv-interceptor)

(defn set-interceptors! [rt-ref interceptors]
  {:pre [(vector? interceptors)
         (every? valid-interceptor? interceptors)]}
  (swap! rt-ref assoc ::event-interceptors interceptors))

(defn queue-after-interceptor [tx-env interceptor]
  {:pre [(fn? interceptor)]}
  (update tx-env ::tx-after conj interceptor))

(defn fx-timeout [env timeout-id timeout ev]
  (queue-fx env
    ::timeout
    {:timeout-id timeout-id
     :timeout timeout
     :ev ev}))

(defn fx-timeout-clear [env timeout-id]
  (queue-fx env
    ::timeout-clear
    {:timeout-id timeout-id}))