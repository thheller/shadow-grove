(ns shadow.grove.impl
  (:require
    [clojure.set :as set]
    [shadow.grove :as-alias sg]
    [shadow.grove.protocols :as gp]
    [shadow.grove.runtime :as rt]
    [shadow.grove.db :as db]
    [shadow.grove.eql-query :as eql]
    [shadow.grove.components :as comp]
    ))

(defn js-set-union [a b]
  (.forEach b (fn [x] (.add a x))))

(set! *warn-on-infer* false)

(def ^function
  work-queue-task!
  (if js/window.requestIdleCallback
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
  (if js/window.cancelIdleCallback
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

(defn index-query*
  [{::rt/keys [active-queries-map key-index-seq key-index-ref query-index-map]} query-id prev-keys next-keys]
  (when (.has active-queries-map query-id)
    (let [key-index @key-index-ref]

      ;; index keys that weren't used previously
      (reduce
        (fn [_ key]
          (when-not (contains? prev-keys key)
            (let [key-idx
                  (or (get key-index key)
                      (let [idx (swap! key-index-seq inc)]
                        (swap! key-index-ref assoc key idx)
                        idx))

                  query-set
                  (or (.get query-index-map key-idx)
                      (let [query-set (js/Set.)]
                        (.set query-index-map key-idx query-set)
                        query-set))]

              (.add query-set query-id)))
          nil)
        nil
        next-keys)

      ;; remove old keys that are no longer used
      (when prev-keys
        (reduce
          (fn [_ key]
            (when-not (contains? next-keys key)
              (let [key-idx (get key-index key)
                    query-set (.get query-index-map key-idx)]
                (when ^boolean query-set
                  (.delete query-set query-id)))))
          nil
          prev-keys)))))

(defn index-query [env query-id prev-keys next-keys]
  (.push index-queue #(index-query* env query-id prev-keys next-keys))
  (index-queue-some!))

;; FIXME: this needs some kind of GC
;; currently does not remove empty sets from query-index-map

(defn unindex-query*
  [{::rt/keys [key-index-seq key-index-ref query-index-map]} query-id keys]

  ;; FIXME: does this need to check if query is still active?
  ;; I don't think so because unindex is called on destroy and things are never destroyed twice

  (let [key-index @key-index-ref]
    (reduce
      (fn [_ key]
        (let [key-idx
              (or (get key-index key)
                  (let [idx (swap! key-index-seq inc)]
                    (swap! key-index-ref assoc key idx)
                    idx))]

          (when-some [query-set (.get query-index-map key-idx)]
            (.delete query-set query-id))))
      nil
      keys)))

(defn unindex-query [env query-id keys]
  (.push index-queue #(unindex-query* env query-id keys))
  (index-queue-some!))

(defn invalidate-keys!
  [{::rt/keys
    [active-queries-map
     query-index-map
     key-index-ref] :as env}
   keys-new
   keys-removed
   keys-updated]

  ;; before we can invalidate anything we need to make sure the index is updated
  ;; we delay updating index stuff to be async since we only need it here later
  (index-work-all!)

  (let [keys-to-invalidate (set/union keys-new keys-updated keys-removed)
        key-index @key-index-ref
        query-ids (js/Set.)]

    (reduce
      (fn [_ key]
        ;; key might not be used by any query so might not have an id
        (when-some [key-id (get key-index key)]
          ;; same here
          (when-some [query-set (.get query-index-map key-id)]
            (js-set-union query-ids query-set))))

      nil
      keys-to-invalidate)

    ;; just refreshes all affected queries in no deterministic order
    ;; each query will figure out on its own if if actually triggers an update
    ;; FIXME: figure out if this can be smarter
    (.forEach query-ids
      (fn [query-id]
        (when-some [query (.get active-queries-map query-id)]
          (gp/query-refresh! query))))))

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

(defn process-event
  [rt-ref
   ev
   origin]
  {:pre [(or (fn? ev) (map? ev))]}

  ;; (js/console.log ev-id ev origin @rt-ref)

  (let [{::rt/keys [data-ref event-config fx-config] :as env}
        @rt-ref

        ev-id
        (if (fn? ev) ::fn (:e ev))

        handler
        (if (fn? ev)
          ev
          (get event-config ev-id))]

    (if-not handler
      (unhandled-event-ex! ev-id ev origin)

      (let [before @data-ref

            tx-db
            (db/transacted before)

            tx-guard
            (js/Object.)

            tx-done-ref
            (atom false)

            tx-env
            (assoc env
              ::tx-guard tx-guard
              ::fx []
              :db tx-db
              ;; FIXME: should this be strict and only allow chaining tx from fx handlers?
              ;; should be forbidden to execute side effects directly in tx handlers?
              ;; but how do we enforce this cleanly? this feels kinda dirty maybe needless indirection?
              :transact!
              (fn [next-tx]
                (throw (ex-info "transact! only allowed from fx env" {:tx next-tx}))))

            result
            (merge-result tx-env ev (handler tx-env ev))]

        (let [{:keys [db data keys-new keys-removed keys-updated] :as tx-result}
              (db/tx-commit! (:db result))]

          (when-not (identical? @data-ref before)
            (throw (ex-info "someone messed with app-state while in tx" {})))

          (reset! data-ref db)

          ;; FIXME: figure out if invalidation/refresh should be immediate or microtask'd/delayed?
          (when-not (identical? before data)
            (invalidate-keys! env keys-new keys-removed keys-updated))

          ;; FIXME: re-frame allows fx to edit db but we already committed it
          ;; currently not checking fx-fn return value at all since they supposed to run side effects only
          ;; and may still edit stuff in env, just not db?

          ;; dispatching async so render can get to it sooner
          ;; dispatching these async since they can never do anything that affects the current render right?
          (rt/next-tick
            (fn []
              (doseq [[fx-key value] (::rt/fx result)]
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

                          (gp/run-now! ^not-native (::rt/scheduler env) #(process-event rt-ref fx-tx origin) [::fx-transact! fx-key])))]

                  (if-not fx-fn
                    (throw (ex-info (str "unknown fx " fx-key) {:fx-key fx-key :fx-value value}))

                    (fx-fn fx-env value))))))

          (when-some [tx-reporter (::rt/tx-reporter env)]
            ;; dispatch tx-reporter async so it doesn't hold up rendering
            ;; the only purpose of this is debugging anyways
            (rt/next-tick
              (fn []
                (let [report
                      {:event ev
                       :origin origin
                       :keys-new keys-new
                       :keys-removed keys-removed
                       :keys-updated keys-updated
                       :fx (::rt/fx result)
                       :db-before @before
                       :db-after data
                       :env env
                       :env-changes
                       (reduce-kv
                         (fn [report rkey rval]
                           (if (identical? rval (get env rkey))
                             report
                             (assoc report rkey rval)))
                         {}
                         (dissoc result :db ::rt/fx ::tx-guard :transact!))}]

                  (tx-reporter report)))))

          (reset! tx-done-ref true)

          (:return result))))))

(deftype HookQuery
  [^:mutable ident
   ^:mutable query
   ^:mutable config
   ^:mutable ^not-native component-handle
   ^:mutable rt-ref
   ^:mutable active-queries-map
   ^:mutable query-id
   ^:mutable ready?
   ^:mutable read-count
   ^:mutable read-keys
   ^:mutable read-result]

  gp/IHook
  (hook-init! [this ch]
    (set! component-handle ch)

    (let [env (gp/get-component-env ch)]
      (set! rt-ref (::rt/runtime-ref env))
      (set! active-queries-map (::rt/active-queries-map @rt-ref)))

    (set! query-id (rt/next-id))

    (.set active-queries-map query-id this)

    (.do-read! this))

  (hook-ready? [this]
    (or ready? (false? (:suspend config))))

  (hook-value [this] read-result)

  ;; node deps changed, check if query changed
  (hook-deps-update! [this ^HookQuery val]
    (let [new-ident (.-ident val)
          ident-equal? (= ident new-ident)]

      (if (and ident-equal?
               (= query (.-query val))
               (= config (.-config val)))
        false
        ;; query changed, perform read immediately
        (do (when-not ident-equal?
              ;; need to reset this to 0 since do-read! uses this to track
              ;; whether it needs to index the query or not
              ;; thus a query with changing ident is not properly re-indexed if this is not reset
              ;; I intended read-count as a debug utility to find overly active queries
              ;; so this somehow needs to be addressed properly somewhere so this doesn't have multiple uses
              (set! read-count 0))

            (set! ident new-ident)
            (set! query (.-query val))
            (set! config (.-config val))
            (let [old-result read-result]
              (.do-read! this)
              (not= old-result read-result))))))

  ;; node was invalidated and needs update
  (hook-update! [this]
    (let [old-result read-result]
      (.do-read! this)
      (not= old-result read-result)))

  (hook-destroy! [this]
    (unindex-query @rt-ref query-id read-keys)
    (.delete active-queries-map query-id))

  gp/IQuery
  (query-refresh! [this]
    (gp/hook-invalidate! component-handle))

  Object
  (do-read! [this]
    (let [query-env @rt-ref
          db @(::rt/data-ref query-env)]

      (if (and ident (nil? query))
        ;; shortcut for just getting data for an ident
        ;; don't need all the query stuff for those
        (let [result (get db ident)
              new-keys #{ident}]

          ;; only need to index once
          (when (zero? read-count)
            (index-query query-env query-id read-keys new-keys))

          (set! read-keys new-keys)

          (if (keyword-identical? result :db/loading)
            (set! read-result (assoc (:default config {}) ::sg/loading-state :loading))
            (do (set! read-result result)
                (set! ready? true))))

        ;; query env is not the component env
        (let [observed-data (db/observed db)
              db-query (if ident [{ident query}] query)
              result (eql/query query-env observed-data db-query)
              new-keys (db/observed-keys observed-data)]

          (index-query query-env query-id read-keys new-keys)

          (set! read-keys new-keys)

          (if (keyword-identical? result :db/loading)
            (set! read-result (assoc (:default config {}) ::sg/loading-state :loading))

            (do (set! read-result
                  (-> (if ident (get result ident) result)
                      (assoc ::sg/loading-state :ready)))
                (set! ready? true))))))

    (set! read-count (inc read-count))))

(defn hook-query [ident query config]
  {:pre [(or (nil? ident) (db/ident? ident))
         (or (nil? query) (vector? query))
         (map? config)]}
  (HookQuery.
    ident
    query
    config
    nil
    nil
    nil
    nil
    false
    0
    nil
    nil))

(deftype DirectQuery
  [rt-ref
   query-id
   query
   callback
   ^:mutable read-keys
   ^:mutable read-result
   ^:mutable destroyed?]

  gp/IQuery
  (query-refresh! [this]
    (when-not destroyed?
      (.do-read! this)))

  Object
  (do-read! [this]
    (let [query-env @rt-ref
          observed-data (db/observed @(::rt/data-ref query-env))
          result (eql/query query-env observed-data query)
          new-keys (db/observed-keys observed-data)]

      ;; remember this even if query is still loading
      (index-query query-env query-id read-keys new-keys)

      (set! read-keys new-keys)

      ;; if query is still loading don't send to main
      (when (and (not (keyword-identical? result :db/loading))
                 ;; empty result likely means the query is no longer valid
                 ;; eg. deleted ident. don't send update, will likely be destroyed
                 ;; when other query updates
                 (some? result)
                 (not (empty? result))
                 ;; compare here so main doesn't need to compare again
                 (not= result read-result))

        (set! read-result result)

        (callback result))))

  (destroy! [this]
    (set! destroyed? true)
    (unindex-query @rt-ref query-id read-keys)))

;; direct query, hooks don't use this
(defn query-init [rt-ref query-id query config callback]
  (let [{::rt/keys [active-queries-map]} @rt-ref
        q (DirectQuery. rt-ref query-id query callback nil nil false)]
    (.set active-queries-map query-id q)
    (.do-read! q)))

(defn query-destroy [rt-ref query-id]
  (let [{::rt/keys [active-queries-map]} @rt-ref]
    (when-some [q (.get active-queries-map query-id)]
      (.delete active-queries-map query-id)
      (.destroy! q))))