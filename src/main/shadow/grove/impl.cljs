(ns shadow.grove.impl
  (:require
    [shadow.grove :as-alias sg]
    [shadow.grove.kv :as kv]
    [shadow.grove.protocols :as gp]
    [shadow.grove.runtime :as rt]
    [shadow.grove.components :as comp]
    ))

(defn js-set-union [a b]
  (.forEach b (fn [x] (.add a x))))

(defn set-conj [x y]
  (if (nil? x)
    #{y}
    (conj x y)))

(set! *warn-on-infer* false)

(defrecord IndexKey [kv-table key])

(def ^function
  work-queue-task!
  (if (and (exists? js/window) js/window.requestIdleCallback)
    (fn [work-task]
      (js/window.requestIdleCallback work-task))
    (fn [^function work-task]
      ;; microtask or goog.async.run don't do what we want
      ;; we want the browser to prioritise rendering stuff
      ;; the other work can be delayed until idle. setTimeout seems closest.
      (js/setTimeout
        (fn []
          (let [start (js/Date.now)
                fake-deadline
                #js {:timeRemaining
                     #(< 16 (- (js/Date.now) start))}]
            (work-task fake-deadline)))
        ;; usually 4 or so minimum but that is good enough for our purposes
        0))))

(def ^function
  work-queue-cancel!
  (if (and (exists? js/window) js/window.cancelIdleCallback)
    (fn [id]
      (js/window.cancelIdleCallback id))
    (fn [id]
      (js/clearTimeout id))))

(defonce index-queue (js/Array.))

(defonce ^boolean work-queued? false)
(defonce work-timeout nil)

(defn index-work-all! []
  (when work-queued?
    (when work-timeout
      (work-queue-cancel! work-timeout)
      (set! work-timeout nil))

    ;; work until all work is done, immediately work off new tasks
    (loop []
      (let [^function task (.shift index-queue)]
        (when ^boolean task
          (task)
          (recur))))

    (set! work-queued? false)))

(defn index-work-some! [^js deadline]
  (loop []
    (when (pos? (.timeRemaining deadline))
      (let [^function task (.shift index-queue)]
        (when ^boolean task
          (task)
          (recur)))))

  (if (pos? (alength index-queue))
    (do (set! work-timeout (work-queue-task! index-work-some!))
        (set! work-queued? true))
    (do (set! work-timeout nil)
        (set! work-queued? false))))

(defn index-queue-some! []
  (when-not work-queued?
    (set! work-timeout (work-queue-task! index-work-some!))
    (set! work-queued? true)))

(defn mset [key-index-ref key]
  (or (get @key-index-ref key)
      (let [s (js/Set.)]
        (vswap! key-index-ref assoc key s)
        s)))

(defn index-query-key*
  [rt-ref query-id prev-key next-key]
  (let [{::sg/keys [active-queries-map key-index-ref]} @rt-ref]
    ;; this is done async, query might be gone since index was queued
    (when (.has active-queries-map query-id)
      ;; index keys that weren't used previously

      (cond
        (nil? prev-key)
        (let [set (mset key-index-ref next-key)]
          (.add set query-id))

        (not= prev-key next-key)
        (let [prev-set (mset key-index-ref prev-key)
              next-set (mset key-index-ref next-key)]
          (.delete prev-set query-id)
          (.add next-set query-id))

        )))

  js/undefined)

;; FIXME: this is likely overkill for a single key? might as well just do it immediately?
(defn index-query-key [env query-id prev-key next-key]
  (.push index-queue #(index-query-key* env query-id prev-key next-key))
  (index-queue-some!))

(defn unindex-query-key*
  [{::sg/keys [key-index-ref]} query-id key]
  (when-some [s (get @key-index-ref key)]
    (.delete s query-id)))

(defn unindex-query-key [env query-id key]
  (.push index-queue #(unindex-query-key* env query-id key))
  (index-queue-some!))

(defn index-query-keys*
  [rt-ref query-id prev-keys next-keys]
  (let [{::sg/keys [active-queries-map key-index-ref]} @rt-ref]
    ;; this is done async, query might be gone since index was queued
    (when (.has active-queries-map query-id)
      ;; index keys that weren't used previously
      (reduce
        (fn [_ key]
          (when-not (contains? prev-keys key)
            (let [set (mset key-index-ref key)]
              (.add set query-id)))
          nil)
        nil
        next-keys)

      ;; remove old keys that are no longer used
      (when-not (nil? prev-keys)
        (reduce
          (fn [_ key]
            (when-not (contains? next-keys key)
              (let [set (mset key-index-ref key)]
                (.delete set query-id))))
          nil
          prev-keys))))

  js/undefined)

(defn index-query-keys [env query-id prev-keys next-keys]
  (.push index-queue #(index-query-keys* env query-id prev-keys next-keys))
  (index-queue-some!))

(defn unindex-query-keys*
  [{::sg/keys [key-index-ref]} query-id keys]
  (reduce
    (fn [_ key]
      (when-some [s (get @key-index-ref key)]
        ;; FIXME: this needs some kind of GC
        ;; currently does not remove empty sets from key-index-ref
        ;; so possibly accumulates unused keys over time
        ;; checking every time if its empty seems more expensive overall
        (.delete s query-id)))
    nil
    keys))

(defn unindex-query-keys [env query-id keys]
  (.push index-queue #(unindex-query-keys* env query-id keys))
  (index-queue-some!))

(defn invalidate-kv! [rt-ref tx-info]
  ;; before we can invalidate anything we need to make sure the index is updated
  ;; we delay updating index stuff to be async since we only need it here later
  (index-work-all!)

  (let [key-index
        @(::sg/key-index-ref @rt-ref)

        active-queries-map
        (::sg/active-queries-map @rt-ref)

        ;; using mutable things since they are a bit faster and time matters here
        keys-to-invalidate
        (js/Array.)

        query-ids
        (js/Set.)]

    (reduce-kv
      (fn [_ kv-table tx-info]
        (let [keys-new (:keys-new tx-info)
              keys-updated (:keys-updated tx-info)
              keys-removed (:keys-removed tx-info)
              add (fn [key] (.push keys-to-invalidate (IndexKey. kv-table key)))]

          ;; using kv-table as marker when kv seq was used (e.g. keys/vals)
          ;; tracking every single entry is expensive, so just assuming
          ;; there was interest in all when seq was used
          (when (or (seq keys-updated)
                    (seq keys-new)
                    (seq keys-removed))
            (.push keys-to-invalidate kv-table))

          (run! add keys-new)
          (run! add keys-updated)
          (run! add keys-removed))
        nil)
      nil
      tx-info)

    (.forEach keys-to-invalidate
      (fn [key]
        (when-some [query-set (get key-index key)]
          (js-set-union query-ids query-set))))

    ;; (js/console.log "invalidating" keys-to-invalidate query-ids tx-info)

    (.forEach query-ids
      (fn [query-id]
        (when-some [callback (.get active-queries-map query-id)]
          (callback)))))

  js/undefined)

(defn merge-result [tx-env ev result]
  (cond
    (nil? result)
    tx-env

    (not (map? result))
    (throw
      (ex-info
        (str "tx handler returned invalid result for event " (:e ev))
        {:event ev
         :env tx-env
         :result result}))

    (identical? (::tx-guard tx-env) (::tx-guard result))
    result

    :else
    (throw
      (ex-info
        (str "tx handler returned invalid result for event" (:e ev) ", expected a modified env")
        {:event ev
         :env tx-env
         :result result}))))

(defn unhandled-event-ex! [ev-id tx origin]
  (if (and ^boolean js/goog.DEBUG (map? origin))
    (loop [comp (comp/get-component origin)
           err-msg (str "Unhandled Event " ev-id "\n    Component Trace:")]
      (if-not comp
        ;; FIXME: directly outputting this is here is kinda ugly?
        (do (js/console.error err-msg)
            (throw (ex-info (str "Unhandled Event " ev-id) {:ev-id ev-id :tx tx :origin origin})))

        (recur
          (comp/get-parent comp)
          (str err-msg "\n    " (comp/get-component-name comp))
          )))

    (throw (ex-info
             (str "Unhandled Event " ev-id)
             {:ev-id ev-id :tx tx}))))

(defn call-interceptors [interceptors tx-env]
  (reduce
    (fn [tx-env ^function handler]
      ;; allow nils, easier to do (when DEBUG extra-interceptor) kind of handlers
      (if (nil? handler)
        tx-env
        (try
          (let [result
                (try
                  (handler tx-env)
                  (catch :default e
                    (throw (ex-info "interceptor failed" {:interceptor handler} e))))]

            (when-not (identical? (::tx-guard result) (::tx-guard tx-env))
              (throw (ex-info "interceptor didn't return tx-env" {:interceptor handler :result result})))

            result))))
    tx-env
    interceptors))

(def ^function tx-reporter nil)

(defn do-tx-report [tx-env]
  (when-not (nil? tx-reporter)
    (rt/next-tick
      (fn []
        (tx-reporter tx-env)))))


;; FIXME: I'm unsure it was worth extracting this into an interceptor
;; might be better if still handled in process-event directly
;; but this technically allows users to run stuff after/before this is done
(defn kv-interceptor [tx-env]
  (let [rt-ref (::sg/runtime-ref tx-env)
        kv-ref (::sg/kv-ref @rt-ref)
        before @kv-ref

        tx-env
        (reduce-kv
          (fn [tx-env kv-table kv]
            (assoc tx-env kv-table (kv/transacted kv)))
          tx-env
          before)]

    (update tx-env ::sg/tx-after conj
      (fn kv-interceptor-after [tx-env]
        (when-not (identical? @kv-ref before)
          (throw (ex-info "someone messed with kv state while in tx" {})))

        (let [tx-info
              (reduce-kv
                (fn [tx-info kv-table _]
                  (let [kv (get tx-env kv-table)]

                    ;; completely disallow (assoc tx-env :a-defined-table {:a "new-map"})
                    ;; FIXME: could actually allow that and so some sort of diff when getting a map?
                    ;; but user should have merged instead
                    (when-not (instance? kv/TransactedData kv)
                      (throw (ex-info
                               (str "during transaction the " kv-table " table was replaced. only a modified table can be returned.")
                               {:kv-table kv-table
                                :return kv})))

                    (let [commit (kv/tx-commit! kv)]
                      (if (identical? (:data commit) (:data-before commit))
                        ;; if no changes were done there should be no trace in tx-info
                        ;; saves some time later in invalidate-kv!
                        tx-info
                        (assoc tx-info kv-table commit)))))
                {}
                before)

              kv-after
              (reduce-kv
                (fn [m kv-table tx-info]
                  (assoc m kv-table (:data tx-info)))
                before
                tx-info)]

          (reset! kv-ref kv-after)

          (invalidate-kv! rt-ref tx-info)

          (reduce-kv
            (fn [tx-env kv-table _]
              ;; just to avoid anyone touching this again
              (dissoc tx-env kv-table))
            (assoc tx-env ::sg/tx-info tx-info)
            before)
          )))))

(defn process-event
  [rt-ref
   ev
   origin]
  {:pre [(or (fn? ev) (map? ev))]}

  ;; (js/console.log ev-id ev origin @rt-ref)

  (let [{::sg/keys [event-config event-interceptors fx-config] :as env}
        @rt-ref

        ev-id
        (if (fn? ev) ::fn (:e ev))

        handler
        (if (fn? ev)
          ev
          (get event-config ev-id))]

    (if-not handler
      (unhandled-event-ex! ev-id ev origin)

      (let [tx-guard
            (js/Object.)

            tx-done-ref
            (atom false)

            tx-env
            ;; only set use namespaced keys
            ;; must avoid clashing with kv-tables overriding them
            (-> {::tx-guard tx-guard
                 ::sg/runtime-ref rt-ref
                 ::sg/tx-after (list) ;; FILO
                 ::sg/fx []
                 ::sg/origin origin}
                (cond->
                  (map? ev)
                  (assoc ::sg/event ev)))

            tx-env
            (call-interceptors event-interceptors tx-env)

            handler-result
            (handler tx-env ev)

            result
            (merge-result tx-env ev handler-result)

            result
            (call-interceptors (::sg/tx-after result) result)]

        ;; FIXME: re-frame allows fx to edit db but we already committed it
        ;; currently not checking fx-fn return value at all since they supposed to run side effects only
        ;; and may still edit stuff in env, just not db?

        ;; dispatching async so render can get to it sooner
        ;; dispatching these async since they can never do anything that affects the current render right?
        (rt/next-tick
          (fn []
            (doseq [[fx-key value] (::sg/fx result)]
              (let [fx-fn (get fx-config fx-key)

                    fx-env
                    (assoc result
                      ;; creating this here so we can easily track which fx caused further work
                      ;; technically all fx could run-now! directly given they have the scheduler from the env
                      ;; but here we can easily track tx-done-ref to ensure fx doesn't actually immediately trigger
                      ;; other events when they shouldn't because this is still in run-now! itself
                      ;; FIXME: remove this once this is handled directly in the scheduler
                      ;; run-now! inside run-now! should be a hard error
                      :transact!
                      (fn [fx-tx]
                        (when-not @tx-done-ref
                          (throw (ex-info "cannot start another tx yet, current one is still running. transact! is meant for async events" {})))

                        (gp/run-now! ^not-native (::sg/scheduler env) #(process-event rt-ref fx-tx origin) [::fx-transact! fx-key])))]

                (if-not fx-fn
                  (throw (ex-info (str "unknown fx " fx-key) {:fx-key fx-key :fx-value value}))

                  (fx-fn fx-env value))))))

        (do-tx-report result)

        (reset! tx-done-ref true)

        (:return result)))))

(defn lazy-seq? [thing]
  (and (instance? cljs.core/LazySeq thing)
       (not (realized? thing))))

(defn slot-query [args read-fn]
  (let [ref
        (rt/claim-slot! ::slot-query)

        rt-ref
        (::sg/runtime-ref rt/*env*)

        {::sg/keys [active-queries-map] :as query-env}
        @rt-ref]

    ;; setup only once
    (when (nil? @ref)
      (comp/set-cleanup! ref
        (fn [{:keys [query-id read-keys] :as last-state}]
          (unindex-query-keys @rt-ref query-id read-keys)
          (.delete active-queries-map query-id)
          ))

      (let [query-id (rt/next-id)]
        (swap! ref assoc :query-id query-id)

        (.set active-queries-map query-id #(gp/invalidate! ref))))

    ;; perform query
    (let [kv
          @(::sg/kv-ref query-env)

          {:keys [query-id read-keys]}
          @ref

          query-env
          (reduce-kv
            (fn [query-env kv-table ^not-native kv]
              (assoc query-env kv-table (kv/observed kv)))
            {::sg/runtime-ref rt-ref
             ::sg/previous-result rt/*slot-value*}
            kv)

          result
          (apply read-fn query-env args)

          ;; FIXME: could just force it?
          ;; seems cleaner to let user handle it
          ;; it needs to be forced since otherwise the key recording might miss something
          _ (when (lazy-seq? result)
              (throw
                (ex-info
                  "query functions are not allowed to return lazy sequences!"
                  {:result result})))

          new-keys
          (reduce-kv
            (fn [key-set kv-table _]
              (let [^not-native observed (get query-env kv-table)
                    [seq-used kv-keys] (kv/observed-keys observed)]
                (-> key-set
                    (cond->
                      seq-used
                      (conj kv-table))
                    (into (map #(IndexKey. kv-table %)) kv-keys))))
            #{}
            kv)]

      (index-query-keys rt-ref query-id read-keys new-keys)

      (swap! ref assoc :read-keys new-keys)

      result
      )))

(defn slot-kv-get [kv-table]
  (let [ref
        (rt/claim-slot! ::slot-kv-get)

        rt-ref
        (::sg/runtime-ref rt/*env*)

        {::sg/keys [active-queries-map] :as query-env}
        @rt-ref]

    ;; setup only once
    (when (nil? @ref)
      (comp/set-cleanup! ref
        (fn [{:keys [query-id read-key] :as last-state}]
          (unindex-query-key @rt-ref query-id read-key)
          (.delete active-queries-map query-id)
          ))

      (let [query-id (rt/next-id)]
        (swap! ref assoc :query-id query-id)
        (.set active-queries-map query-id #(gp/invalidate! ref))))

    (let [all
          @(::sg/kv-ref query-env)

          {:keys [query-id read-key]}
          @ref

          kv
          (kv/get-kv! all kv-table)]

      (index-query-key rt-ref query-id read-key kv-table)

      (swap! ref assoc :read-key kv-table)

      ;; no observation is performed, assuming the user wanted everything
      kv)))

(defn slot-kv-lookup [kv-table key]
  (let [ref
        (rt/claim-slot! ::slot-kv-lookup)

        rt-ref
        (::sg/runtime-ref rt/*env*)

        {::sg/keys [active-queries-map] :as query-rt}
        @rt-ref]

    ;; setup only once
    (when (nil? @ref)
      (comp/set-cleanup! ref
        (fn [{:keys [query-id read-key] :as last-state}]
          (unindex-query-key @rt-ref query-id read-key)
          (.delete active-queries-map query-id)
          ))

      (let [query-id (rt/next-id)]
        (swap! ref assoc :query-id query-id)
        (.set active-queries-map query-id #(gp/invalidate! ref))))

    ;; perform query
    (let [all
          @(::sg/kv-ref query-rt)

          {:keys [query-id read-key]}
          @ref

          kv
          (kv/get-kv! all kv-table)

          new-key
          (IndexKey. kv-table key)]

      (index-query-key rt-ref query-id read-key new-key)

      (swap! ref assoc :read-key new-key)

      (get kv key))))

(defn slot-state [init-state merge-fn]
  (let [ref (rt/claim-slot! ::slot-state)
        state @ref]

    (cond
      (nil? state)
      (reset! ref (vary-meta init-state assoc ::ref ref ::init-state init-state))

      (and merge-fn (not= init-state (::init-state (meta state))))
      (swap! ref (fn [state]
                   (-> state
                       (merge-fn init-state)
                       ;; just in case the merge-fn dropped these
                       (vary-meta assoc ::ref ref ::init-state init-state)))))

    @ref
    ))