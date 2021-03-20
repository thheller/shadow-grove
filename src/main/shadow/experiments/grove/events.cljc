(ns shadow.experiments.grove.events
  "re-frame style event handling"
  (:require
    [clojure.set :as set]
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

(defn tx*
  [{::rt/keys [data-ref event-config fx-config]
    :as env}
   {ev-id :e :as tx}]
  {:pre [(map? tx)
         (keyword? ev-id)]}
  ;; (js/console.log ::tx* ev-id tx env)

  ;; FIXME: instead of interceptors allow handler-fn to be multiple things or use function comp
  ;; (reg-event-fx env ::foo (fn [env tx]))
  ;; (reg-event-fx env ::foo [pre (fn [env tx]) after]) ;; pre after just being functions
  ;; (reg-event-fx env ::foo [{:enter (fn [env tx]) :exit (fn [return])} fn1 fn2]) ;; maybe
  (let [^function handler-fn (get event-config ev-id)]

    (if-not handler-fn
      (throw (ex-info
               (str "unhandled event " ev-id)
               {:ev-id ev-id :tx tx}))

      (let [before @data-ref

            tx-db
            (db/transacted before)

            tx-env
            (assoc env
              :db tx-db
              ;; FIXME: should this be strict and only allow chaining tx from fx handlers?
              ;; should be forbidden to execute side effects directly in tx handlers?
              ;; but how do we enforce this cleanly? this feels kinda dirty maybe needless indirection?
              :transact!
              (fn [next-tx]
                (throw (ex-info "transact! only allowed from fx env" {:tx next-tx}))))

            {^clj tx-after :db return-value :return :as result}
            (handler-fn tx-env tx)]

        (reduce-kv
          (fn [_ fx-key value]
            (let [fx-fn (get fx-config fx-key)]
              (if-not fx-fn
                (throw (ex-info "invalid fx" {:fx-key fx-key :fx-value value}))
                (let [transact-fn
                      (fn [fx-tx]
                        ;; FIXME: should probably track the fx causing this transaction and the original tx
                        ;; FIXME: should probably prohibit calling this while tx is still processing?
                        ;; just meant for async events triggered by fx
                        (tx* env fx-tx))
                      fx-env
                      (assoc env :transact! transact-fn)]
                  (fx-fn fx-env value)))))
          nil
          (dissoc result :db :return))

        (when tx-after
          (let [{:keys [data keys-new keys-removed keys-updated] :as result}
                (db/commit! tx-after)]

            (when-not (identical? @data-ref before)
              (throw (ex-info "someone messed with app-state while in tx" {})))

            (reset! data-ref data)

            ;; FIXME: figure out if invalidation/refresh should be immediate or microtask'd/delayed?
            (when-not (identical? before data)
              (invalidate-keys! env keys-new keys-removed keys-updated))))

        return-value))))

(defn run-tx [env tx]
  {:pre [(map? tx)
         (keyword? (:e tx))]}
  (tx* env tx))

(defn reg-event [app-ref ev-id handler-fn]
  {:pre [(keyword? ev-id)
         (fn? handler-fn)]}
  (swap! app-ref assoc-in [::rt/event-config ev-id] handler-fn)
  app-ref)

(defn reg-fx [app-ref fx-id handler-fn]
  (swap! app-ref assoc-in [::rt/fx-config fx-id] handler-fn)
  app-ref)
