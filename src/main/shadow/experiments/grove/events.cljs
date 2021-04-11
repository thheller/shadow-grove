(ns shadow.experiments.grove.events
  "re-frame style event handling"
  (:require-macros [shadow.experiments.grove.events])
  (:require
    [clojure.set :as set]
    [shadow.experiments.grove.components :as comp]
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove.db :as db]))

(defprotocol IQuery
  (query-refresh! [this]))

(defn set-conj [x y]
  (if (nil? x) #{y} (conj x y)))

(defn vec-conj [x y]
  (if (nil? x) [y] (conj x y)))

(defn js-set-union [a b]
  (.forEach b (fn [x] (.add a x))))

(defn reduce-> [init rfn coll]
  (reduce rfn init coll))

;; FIXME: this needs some kind of GC
;; currently does not remove empty sets from query-index-map

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

(defn invalidate-keys!
  [{::rt/keys
    [active-queries-map
     ^function query-index-queue-flush!
     query-index-map
     key-index-ref] :as env}
   keys-new
   keys-removed
   keys-updated]

  ;; before we can invalidate anything we need to make sure the index is updated
  ;; we delay updating index stuff to be async since we only need it here later
  (when query-index-queue-flush!
    (query-index-queue-flush!))

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
          (query-refresh! query))))))

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


(defn queue-fx [env fx-id fx-val]
  (update env ::fx vec-conj [fx-id fx-val]))

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

    ;; naively merge result into tx-env overwriting values
    ;; and using proper queue-fx for registered fx entries
    ;; does not warn about unknown key/vals
    ;; makes no attempt to deep merge values except ::fx
    ;; FIXME: I'm not sure this is at all worth doing
    ;; maybe just enforce handlers returning updated tx-env?
    :else
    (let [fx-config (::rt/fx-config tx-env)]
      (reduce-kv
        (fn [env rkey rval]
          (cond
            (contains? fx-config rkey)
            (queue-fx env rkey rval)

            (= ::fx rkey)
            (update env ::fx into rval)

            ;; FIXME: should this just accept :db or ::fx and warn on all others?
            ;; typo in fx name just disappear silently
            :else
            (assoc env rkey rval)))
        tx-env
        result))))

(defn tx*
  [{::rt/keys [data-ref event-config fx-config]
    :as env}
   {ev-id :e :as ev}
   origin]
  {:pre [(map? ev)
         (keyword? ev-id)]}
  ;; (js/console.log ::tx* ev-id tx env)

  (let [handler (get event-config ev-id)]

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

        (let [{:keys [data keys-new keys-removed keys-updated] :as tx-result}
              (db/commit! (:db result))]

          (when-not (identical? @data-ref before)
            (throw (ex-info "someone messed with app-state while in tx" {})))

          (reset! data-ref data)

          ;; FIXME: figure out if invalidation/refresh should be immediate or microtask'd/delayed?
          (when-not (identical? before data)
            (invalidate-keys! env keys-new keys-removed keys-updated))

          (when-some [tx-reporter (::tx-reporter env)]
            (let [report
                  {:event ev
                   :origin origin
                   :keys-new keys-new
                   :keys-removed keys-removed
                   :keys-updated keys-updated
                   :fx (::fx result)
                   :db-before before
                   :db-after data
                   :env env
                   :env-changes
                   (reduce-kv
                     (fn [report rkey rval]
                       (if (identical? rval (get env rkey))
                         report
                         (assoc report rkey rval)))
                     {}
                     (dissoc result :db ::fx ::tx-guard :transact!))}]

              (tx-reporter report)))

          ;; FIXME: re-frame allows fx to edit db but we already committed it
          ;; currently not checking fx-fn return value at all since they supposed to run side effects only
          ;; and may still edit stuff in env, just not db?
          (let [fx-env
                (assoc result
                  ;; FIXME: is this really needed?
                  ;; meant for fx that want to trigger async events later
                  :transact!
                  (fn [fx-tx]
                    (when-not @tx-done-ref
                      (throw (ex-info "cannot start another tx yet, current one is still running. transact! is meant for async events" {})))
                    (tx* env fx-tx origin)))]

            (doseq [[fx-key value] (::fx result)]
              (let [fx-fn (get fx-config fx-key)]
                (if-not fx-fn
                  (throw (ex-info (str "unknown fx " fx-key) {:fx-key fx-key :fx-value value}))

                  (fx-fn fx-env value)))))

          (reset! tx-done-ref true)

          (:return result))))))

(defn reg-event [app-ref ev-id handler-fn]
  {:pre [(keyword? ev-id)
         (ifn? handler-fn)]}
  (swap! app-ref assoc-in [::rt/event-config ev-id] handler-fn)
  app-ref)

(defn reg-fx [app-ref fx-id handler-fn]
  (swap! app-ref assoc-in [::rt/fx-config fx-id] handler-fn)
  app-ref)
