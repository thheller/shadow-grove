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

(defn reduce-> [init rfn coll]
  (reduce rfn init coll))

(defn index-query* [idx query query-keys]
  (reduce
    (fn [idx key]
      (update idx key set-conj query))
    idx
    query-keys))

(defn index-query-diff* [idx query prev-keys next-keys]
  (-> idx
      ;; add new keys
      (reduce->
        (fn [idx key]
          (if (contains? prev-keys key)
            idx
            (update idx key set-conj query)))
        next-keys)

      ;; delete keys no longer used
      (reduce->
        (fn [idx key]
          (if (contains? next-keys key)
            idx
            (update idx key disj query)))
        prev-keys)))

(defn index-query
  [{::rt/keys [query-index-ref] :as env} query prev-keys next-keys]
  ;; FIXME: should the index be using the query-id instead of the query?
  ;; its an int so it could fall back to using js/Set or an object?
  ;; can't use js colls for keys though
  (if (nil? prev-keys)
    ;; first run, no need to diff keys
    (swap! query-index-ref index-query* query next-keys)
    (swap! query-index-ref index-query-diff* query prev-keys next-keys)))

(defn unindex-query* [idx query query-keys]
  (reduce
    (fn [idx key]
      (update idx key disj query))
    idx
    query-keys))

(defn unindex-query
  [env query keys]
  (swap! (::rt/query-index-ref env) unindex-query* query keys))

(defn invalidate-keys!
  [env keys-to-invalidate]
  (let [idx @(::rt/query-index-ref env)

        queries
        (persistent!
          (reduce
            (fn [queries key]
              (let [used-by (get idx key)]
                (if-not (seq used-by)
                  queries
                  (reduce conj! queries used-by))))

            (transient #{})
            keys-to-invalidate))]

    ;; just refreshes all affected queries in no deterministic order
    ;; each query will figure out on its own if if actually triggers an update
    ;; FIXME: figure out if this can be smarter
    (reduce
      (fn [_ ^IQuery query]
        (query-refresh! query))
      nil
      queries)))

(defn tx*
  [{::rt/keys [data-ref event-config fx-config]
    :as env}
   {ev-id :e :as tx}]
  {:pre [(map? tx)
         (keyword? ev-id)]}
  ;; (js/console.log ::worker-tx ev-id tx env)

  ;; FIXME: instead of interceptors allow handler-fn to be multiple things or use function comp
  ;; (reg-event-fx env ::foo (fn [env tx]))
  ;; (reg-event-fx env ::foo [pre (fn [env tx]) after]) ;; pre after just being functions
  ;; (reg-event-fx env ::foo [{:enter (fn [env tx]) :exit (fn [return])} fn1 fn2]) ;; maybe
  (let [^function handler-fn (get event-config ev-id)]

    (if-not handler-fn
      (js/console.warn "no event handler for" ev-id tx env)

      (let [before @data-ref

            tx-db
            (db/transacted before)

            tx-env
            (assoc env :db tx-db)

            {^clj tx-after :db return-value :return :as result}
            (handler-fn tx-env tx)]

        (reduce-kv
          (fn [_ fx-key value]
            (let [fx-fn (get fx-config fx-key)]
              (if-not fx-fn
                (js/console.warn "invalid fx" fx-key value)
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
                (.commit! tx-after)]

            (when-not (identical? @data-ref before)
              (throw (ex-info "someone messed with app-state while in tx" {})))

            (reset! data-ref data)

            ;; FIXME: figure out if invalidation/refresh should be immediate or microtask'd/delayed?
            (when-not (identical? before data)
              (let [keys-to-invalidate (set/union keys-new keys-removed keys-updated)]
                (invalidate-keys! env keys-to-invalidate)))))

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
