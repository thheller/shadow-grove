(ns shadow.experiments.grove.events
  "re-frame style event handling"
  (:require
    [clojure.set :as set]
    [shadow.experiments.grove.db :as db]))

(defprotocol IQuery
  (query-pending? [this])
  (query-keys [this])
  (query-refresh! [this]))

(defn set-conj [x y]
  (if (nil? x) #{y} (conj x y)))

(defn vec-conj [x y]
  (if (nil? x) [y] (conj x y)))

;; FIXME: very inefficient, maybe worth maintaining an index
(defn invalidate-queries! [active-queries keys-to-invalidate]
  ;; (js/console.log "invalidating queries" keys-to-invalidate)
  (reduce-kv
    (fn [_ query-id ^IQuery query]
      ;; don't need to check if already pending
      (when-not (query-pending? query)
        (run!
          (fn [key]
            (when (contains? (query-keys query) key)
              ;; FIXME: should this instead collect all the queries that need updating
              ;; and then process them in batch as well so we just send one message to main?
              ;; might be easier to schedule?
              (query-refresh! query)))
          keys-to-invalidate))
      nil)
    nil
    active-queries))

(defn invalidate-keys! [{::keys [active-queries-ref invalidate-keys-ref] :as env}]
  (invalidate-queries! @active-queries-ref @invalidate-keys-ref)
  (reset! invalidate-keys-ref #{}))

(defn tx*
  [{::keys [event-config fx-config data-ref invalidate-keys-ref] :as env}
   {ev-id :e :as tx}]
  {:pre [(map? tx)
         (keyword? ev-id)]}
  ;; (js/console.log ::worker-tx ev-id tx env)

  ;; FIXME: instead of interceptors allow handler-fn to be multiple things or use function comp
  ;; (reg-event-fx env ::foo (fn [env tx]))
  ;; (reg-event-fx env ::foo [pre (fn [env tx]) after]) ;; pre after just being functions
  ;; (reg-event-fx env ::foo [{:enter (fn [env tx]) :exit (fn [return])} fn1 fn2]) ;; maybe
  (let [^function handler-fn (get event-config ev-id)]

    (if-not event
      (js/console.warn "no event handler for" ev-id tx env)

      (let [before @data-ref

            tx-db
            (db/transacted before)

            tx-env
            (assoc env :db tx-db)

            {^clj tx-after :db return-value :return :as result}
            (handler-fn tx-env tx)]

        ;; FIXME: move all of this out to interceptor chain. including DB stuff

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
                (.commit! tx-after)

                keys-to-invalidate
                (set/union keys-new keys-removed keys-updated)]

            (when-not (identical? @data-ref before)
              (throw (ex-info "someone messed with app-state while in tx" {})))

            (reset! data-ref data)

            (when-not (identical? before data)
              ;; instead of invalidating queries for every processed tx
              ;; they are batched to update at most once in a certain interval
              ;; FIXME: make this configurable, benchmark
              ;; FIXME: maybe debounce again per query?
              ;; FIXME: this should be handled elsewhere
              (when (empty? @invalidate-keys-ref)
                (js/setTimeout #(invalidate-keys! env) 10))

              (swap! invalidate-keys-ref set/union keys-to-invalidate))))

        return-value))))

(defn run-tx [env tx]
  {:pre [(map? tx)
         (keyword? (:e tx))]}
  (tx* env tx))

(defn reg-event [app-ref ev-id handler-fn]
  {:pre [(keyword? ev-id)
         (fn? handler-fn)]}
  (swap! app-ref assoc-in [::event-config ev-id] handler-fn)
  app-ref)

(defn reg-fx [app-ref fx-id handler-fn]
  (swap! app-ref assoc-in [::fx-config fx-id] handler-fn)
  app-ref)

;; FIXME: keeping data-ref in env twice because this ns shouldn't depend on worker or local impl
(defn prepare [env data-ref]
  (assoc env
    ::data-ref data-ref
    ::event-config {}
    ::fx-config {}))

(defn init [env]
  (assoc env
    ::active-queries-ref (atom {})
    ::invalidate-keys-ref (atom #{})
    ))