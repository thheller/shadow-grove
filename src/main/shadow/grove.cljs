(ns shadow.grove
  "grove - a small wood or forested area (ie. trees)
   a mini re-frame/fulcro hybrid. re-frame event styles + somewhat normalized db"
  (:require-macros [shadow.grove])
  (:require
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
    [shadow.grove.db :as db]
    [shadow.grove.impl :as impl]
    [shadow.css] ;; used in macro ns
    ))

(set! *warn-on-infer* false)

(defn dispatch-up!
  "Use within a component event handler to propagate the event `ev-map` up the
   component tree. `env` is the environment map available in event handlers."
  [{::comp/keys [^not-native parent] :as env} ev-map]
  {:pre [(map? env)
         (map? ev-map)
         (qualified-keyword? (:e ev-map))]}
  ;; FIXME: should schedule properly when it isn't in event handler already
  (gp/handle-event! parent ev-map nil env))

(defn query-ident
  "Queries the db starting from `ident`.
   * `query`  - Optional. EQL query. Defaults to ident lookup if not provided.
   * `config` - Optional. Any kind of config that may come up.

   Changes to idents accessed by the query (including inside `eql/attr`) during
   transactions will cause the query to re-un.

   ---
   Example
   ```clojure
    (defmethod eql/attr ::contains
      [env db {:dir/keys [files dirs] :as current} query-part params]
      (cond->> (concat dirs files)
        (not (::show-hidden? db))
        (filterv (fn [ident] (not (::hidden? (get db ident)))))))

    (bind {:as query-result
           :dir/keys [name open?]
           ::keys [contains]}
      (sg/query-ident ident [:dir/name :dir/open? ::contains]))
   ```"
  ;; shortcut for ident lookups that can skip EQL queries
  ([ident]
   (impl/hook-query ident nil {}))
  ;; EQL queries
  ([ident query]
   (impl/hook-query ident query {}))
  ([ident query config]
   (impl/hook-query ident query config)))

(defn query-root
  "Queries from the root of the db.
   * `query`  - EQL query.
   * `config` - Optional. Any kind of config that may come up.

   Changes to idents accessed by the query (including inside `eql/attr`) during
   transactions will cause the query to re-un.

   ---
   Example
   ```clojure
    (defmethod eql/attr :products-in-stock [env db _ _]
      (->> (db/all-of :product)
           (filter #(pos? (:stock %)))
           (mapv :db/ident)))

    (defc ui-homepage []
      (bind {:keys [products-in-stock a-root-key]}
        (sg/query-root [:products-in-stock :a-root-key]))
   ```"
  ([query]
   (impl/hook-query nil query {}))
  ([query config]
   (impl/hook-query nil query config)))

(defn run-tx
  "Use inside a component event handler. Runs transaction `tx`, e.g.
   `{:e ::some-event :data ...}`. `env` is the component environment map
   available in event handlers.
   
   ---
   Example
   ```clojure
    (event :hide! [env ev-map event]
      (when (.-ctrlKey event)
        (sg/run-tx env ev-map)))
   ```"
  [{::rt/keys [runtime-ref] :as env} tx]
  (impl/process-event runtime-ref tx env))

(defn run-tx!
  "Runs the transaction `tx`, e.g. `{:e ::some-event :data ...}`, outside of the
   component context."
  [runtime-ref tx]
  (assert (rt/ref? runtime-ref) "expected runtime ref?")
  (let [{::rt/keys [scheduler]} @runtime-ref]
    (gp/run-now! scheduler #(impl/process-event runtime-ref tx nil) ::run-tx!)))

(defn unmount-root [^js root-el]
  (when-let [^sa/TreeRoot root (.-sg$root root-el)]
    (.destroy! root true)
    (js-delete root-el "sg$root")
    (js-delete root-el "sg$env")))

(defn watch
  "Hook that watches `the-atom` and updates when the atom's value changes.
   
   Accepts an optional `path-or-fn` arg that can be used to 'watch' a portion of
   `the-atom`, enabling quick diffs.
   * 'path' – as in `(get-in @the-atom path)`
   * 'fn'   - similar to above, defines how to access the relevant parts of
   `the-atom`. Takes [old-state new-state] of `the-atom` and returns the actual
   value stored in the hook. Example: `(fn [_ new] (get-in new [:id :name]))`.
   
   **Use strongly discouraged** in favor of the normalized central db.
   
   ---
   Examples
   ```clojure
   (watch the-atom [:foo])
   (watch the-atom (fn [old new] ...))
   ```"
  ([the-atom]
   (watch the-atom (fn [old new] new)))
  ([the-atom path-or-fn]
   (if (vector? path-or-fn)
     (atoms/AtomWatch. the-atom (fn [old new] (get-in new path-or-fn)) nil nil)
     (atoms/AtomWatch. the-atom path-or-fn nil nil))))

(defn env-watch
  "Similar to [[watch]], but for atoms inside the component env."
  ([key-to-atom]
   (env-watch key-to-atom [] nil))
  ([key-to-atom path]
   (env-watch key-to-atom path nil))
  ([key-to-atom path default]
   {:pre [(keyword? key-to-atom)
          (vector? path)]}
   (atoms/EnvWatch. key-to-atom path default nil nil nil)))

(defn suspense
  "See [docs](https://github.com/thheller/shadow-experiments/blob/master/doc/async.md)."
  [opts vnode]
  (suspense/SuspenseInit. opts vnode))

(defn simple-seq
  "Creates a collection of DOM elements by applying `render-fn` to each item 
   in `coll`. `render-fn` can be a function or component.
   
   Makes no attempts to minimize DOM operations required for updates. Efficient
   with colls which change infrequently or colls updated at the tail. Otherwise,
   consider using [[keyed-seq]].

   ---
   Example:

   ```clojure
   (sg/simple-seq
    (range 5)
    (fn [num]
      (<< [:div \"inline-item: \" num])))
   ```"
  [coll render-fn]
  (sc/simple-seq coll render-fn))

(defn keyed-seq
  "Creates a keyed collection of DOM elements by applying `render-fn` to each
   item in `coll`.
   * `key-fn` is used to extract a unique key from items in `coll`.
   * `render-fn` can be a function or component.

   Uses the key to minimize DOM updates. Consider using instead of (the more
   lightweight) [[simple-seq]] when `coll` changes frequently.
   
   ---
   Examples:

   ```clojure
   ;; ident used as key
   (keyed-seq [[::ident 1] ...] identity component)

   (keyed-seq [{:id 1 :data ...} ...] :id
     (fn [item] (<< [:div.id (:data item) ...])))
   ```"
  [coll key-fn render-fn]
  (sc/keyed-seq coll key-fn render-fn))

(deftype TrackChange
  [^:mutable val
   ^:mutable trigger-fn
   ^:mutable result
   ^:mutable ^not-native component-handle]

  gp/IHook
  (hook-init! [this ch]
    (set! component-handle ch)
    (set! result (trigger-fn (gp/get-component-env component-handle) nil val)))

  (hook-ready? [this] true)
  (hook-value [this] result)
  (hook-update! [this] false)

  (hook-deps-update! [this ^TrackChange new-track]
    (let [next-val (.-val new-track)
          prev-result result]

      (set! trigger-fn (.-trigger-fn new-track))
      (set! result (trigger-fn (gp/get-component-env component-handle) val next-val))
      (set! val next-val)

      (not= result prev-result)))

  (hook-destroy! [this]
    ))

(defn track-change [val trigger-fn]
  (TrackChange. val trigger-fn nil nil))

;; using volatile so nobody gets any ideas about add-watch
;; pretty sure that would cause havoc on the entire rendering
;; if sometimes does work immediately on set before render can even complete
(defn ref []
  (volatile! nil))

(defn effect
  "Calls `(callback env)` after render when `deps` changes. (*Note*: will be
   called on mount too.) `callback` may return a cleanup function which is
   called on component unmount *and* just before whenever callback would be
   called."
  [deps callback]
  {:pre [(fn? callback)]}
  (comp/EffectHook. deps callback nil true nil))

(defn render-effect
  "Calls `(callback env)` after every render. `callback` may return a cleanup
   function which is called on component unmount *and* after each render before
   callback."
  [callback]
  {:pre [(fn? callback)]}
  (comp/EffectHook. :render callback nil true nil))

(defn mount-effect
  "Calls `(callback env)` on mount. `callback` may return a cleanup function
   which is called on unmount."
  [callback]
  {:pre [(fn? callback)]}
  (comp/EffectHook. :mount callback nil true nil))

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
    ;; dropping DOM event since that should have been handled elsewhere already
    ;; or it wasn't relevant to begin with
    (impl/process-event rt-ref ev-map origin)))

(defn- make-root-env
  [rt-ref root-el]

  ;; FIXME: have a shared root scheduler rt-ref
  ;; multiple roots should schedule in some way not indepdendently
  (let [event-target
        (RootEventTarget. rt-ref)

        env-init
        (::rt/env-init @rt-ref)]

    (reduce
      (fn [env init-fn]
        (init-fn env))

      ;; base env, using init-fn to customize
      {::comp/scheduler (::rt/scheduler @rt-ref)
       ::comp/event-target event-target
       ::suspense-keys (atom {})
       ::rt/root-el root-el
       ::rt/runtime-ref rt-ref
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
      (set! (.-sg$root root-el) new-root)
      (set! (.-sg$env root-el) new-env)
      ::started)))

(defn render
  "Renders the UI root. Call on init and `^:dev/after-load`.
   * `rt-ref`    – runtime atom
   * `root-el`   – DOM element, e.g. `(js/document.getElementById \"app\")`.
   * `root-node` – root fn/component (e.g. defined with `defc`)."
  [rt-ref ^js root-el root-node]
  {:pre [(rt/ref? rt-ref)]}
  (gp/run-now! ^not-native (::rt/scheduler @rt-ref) #(render* rt-ref root-el root-node) ::render))


(deftype RootScheduler [^:mutable update-pending? work-set]
  gp/IScheduleWork
  (schedule-work! [this work-task trigger]
    (.add work-set work-task)

    (when-not update-pending?
      (set! update-pending? true)
      (rt/next-tick #(.process-work! this))))

  (unschedule! [this work-task]
    (.delete work-set work-task))

  (did-suspend! [this target])
  (did-finish! [this target])

  (run-now! [this action trigger]
    (set! update-pending? true)
    (action)
    ;; work must happen immediately since (action) may need the DOM event that triggered it
    ;; any delaying the work here may result in additional paint calls (making things slower overall)
    ;; if things could have been async the work should have been queued as such and not ended up here
    (.process-work! this))

  Object
  (process-work! [this]
    (try
      (let [iter (.values work-set)]
        (loop []
          (let [current (.next iter)]
            (when (not ^boolean (.-done current))
              (gp/work! ^not-native (.-value current))

              ;; should time slice later and only continue work
              ;; until a given time budget is consumed
              (recur)))))

      (finally
        (set! update-pending? false)))))

;; FIXME: make this delegate to the above, don't duplicate the code
(deftype TracingRootScheduler [^:mutable update-pending? work-set]
  gp/IScheduleWork
  (schedule-work! [this work-task trigger]
    (.add work-set work-task)

    (when-not update-pending?
      (set! update-pending? true)
      (rt/next-tick
        (fn []
          (js/console.group (str trigger))
          (try
            (.process-work! this)
            (finally
              (js/console.groupEnd)))
          ))))

  (unschedule! [this work-task]
    (.delete work-set work-task))

  (did-suspend! [this target])
  (did-finish! [this target])

  (run-now! [this action trigger]
    (js/console.group (str trigger))
    (try
      (set! update-pending? true)
      (action)
      ;; work must happen immediately since (action) may need the DOM event that triggered it
      ;; any delaying the work here may result in additional paint calls (making things slower overall)
      ;; if things could have been async the work should have been queued as such and not ended up here
      (.process-work! this)

      (finally
        (js/console.groupEnd))
      ))

  Object
  (process-work! [this]
    (try
      (let [iter (.values work-set)]
        (loop []
          (let [current (.next iter)]
            (when (not ^boolean (.-done current))
              (gp/work! ^not-native (.-value current))

              ;; should time slice later and only continue work
              ;; until a given time budget is consumed
              (recur)))))

      (finally
        (set! update-pending? false)))))

(goog-define TRACE false)

(defn prepare
  "Initialises the runtime atom.
   * `init`       – Optional. A map.
   * `data-ref`   – Ref to the grove db atom.
   * `runtime-id`
  
  ---
  Example:
   
  ```clojure
  (defonce rt-ref
    (-> {::rt/tx-reporter (fn [report] (tap> report))}
        (rt/prepare data-ref ::my-rt)))
  ```"
  ([data-ref runtime-id]
   (prepare {} data-ref runtime-id))
  ([init data-ref runtime-id]
   (let [root-scheduler
         (if ^boolean TRACE
           (TracingRootScheduler. false (js/Set.))
           (RootScheduler. false (js/Set.)))

         rt-ref
         (atom nil)

         active-queries-map
         (js/Map.)]

     (reset! rt-ref
       (assoc init
         ::rt/rt true
         ::rt/scheduler root-scheduler
         ::rt/runtime-id runtime-id
         ::rt/data-ref data-ref
         ::rt/event-config {}
         ::rt/fx-config {}
         ::rt/active-queries-map active-queries-map
         ::rt/key-index-seq (atom 0)
         ::rt/key-index-ref (atom {})
         ::rt/query-index-map (js/Map.)
         ::rt/query-index-ref (atom {})
         ::rt/env-init []))

     (when ^boolean js/goog.DEBUG
       (swap! rt/known-runtimes-ref assoc runtime-id rt-ref))

     rt-ref)))

(defn vec-conj [x y]
  (if (nil? x) [y] (conj x y)))

(defn queue-fx
  "Used inside an event handler, it queues up the registered handler of `fx-id`
   to run at the end of the transaction. The handler will be called with
   `fx-val`.

   ---
   Example:

   ```clojure
    (sg/reg-event rt-ref ::toggle-show-hidden!
      (fn [tx-env {:keys [show?] :as event}]
        (-> tx-env
            (assoc-in [:db ::show-hidden?] show?)   ;; modify the db
            (sg/queue-fx ::alert! event))))         ;; schedule an fx

    (sg/reg-fx rt-ref ::alert!
      (fn [fx-env {:keys [show?] :as fx-val}]
        (js/alert (str \"Will \" (when-not show? \"not\") \" show hidden files.\"))))
   ```"
  [env fx-id fx-val]
  (update env ::rt/fx vec-conj [fx-id fx-val]))

(defn reg-event
  "Registers the `handler-fn` for event `ev-id`. `handler-fn` will be called
   with `{:as tx-env :keys [db]} event-map` and should return the modified
   `tx-env`.

   There is an alternative approach to registering event handlers, see examples.
   
   ---
   Example:
   ```clojure
   (sg/reg-event rt-ref ::complete!
     (fn [tx-env {:keys [checked ident] :as ev}]
       (assoc-in tx-env [:db ident :completed?] checked)))
   
   ;; metadata approach
   (defn complete! {::ev/handle ::complete!}
     [tx-env {:keys [checked ident] :as ev}]
     (assoc-in tx-env [:db ident :completed?] checked))
   
   ;; use `{:dev/always true}` in namespaces utilising the metadata approach. 
   ```"
  [rt-ref ev-id handler-fn]
  {:pre [(keyword? ev-id)
         (ifn? handler-fn)]}
  (swap! rt-ref assoc-in [::rt/event-config ev-id] handler-fn)
  rt-ref)

(defn reg-fx
  "Registers the `handler-fn` for fx `fx-id`. fx is used for side effects, so
   `handler-fn` shouldn't modify the db.
   
   ---
   Examples:

   ```clojure
    (sg/reg-event rt-ref ::toggle-show-hidden!
      (fn [tx-env {:keys [show?] :as event}]
        (-> tx-env
            (assoc-in [:db ::show-hidden?] show?)   ;; modify the db
            (sg/queue-fx ::alert! event))))         ;; schedule an fx

    (sg/reg-fx rt-ref ::alert!
      (fn [fx-env {:keys [show?] :as fx-data}]
        (js/alert (str \"Will \" (when-not show? \"not\") \" show hidden files.\"))))
   ```
   
   `(:transact! fx-env)` allows fx to schedule another transaction, but it
   should be an async call:
   ```clojure
    (sg/reg-fx rt-ref :ui/redirect!
      (fn [{:keys [transact!] :as env} {:keys [token title]}]
        (let [tokens (str/split (subs token 1) #\"/\")]
          ;; forcing the transaction to be async
          (js/setTimeout #(transact! {:e :ui/route! :token token :tokens tokens}) 0))))
   ```"
  [rt-ref fx-id handler-fn]
  (swap! rt-ref assoc-in [::rt/fx-config fx-id] handler-fn)
  rt-ref)
