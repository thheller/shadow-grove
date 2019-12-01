(ns shadow.experiments.grove-worker
  "grove - a small wood or forested area (ie. trees)
   a mini re-frame/fulcro hybrid. re-frame event styles + somewhat normalized db"
  (:require
    [clojure.set :as set]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.protocols :as gp]
    [cognitect.transit :as transit]))

(defn send-to-main [{::keys [transit-str] :as env} msg]
  ;; (js/console.log "main-write" env msg)
  (js/postMessage (transit-str msg)))

(deftype ObservedData [^:mutable keys-used data]
  IDeref
  (-deref [_]
    (persistent! keys-used))

  IMeta
  (-meta [_]
    (-meta data))

  ;; map? predicate checks for this protocol
  IMap
  (-dissoc [coll k]
    (throw (ex-info "observed data is read-only" {})))

  ILookup
  (-lookup [_ key]
    (when (nil? key)
      (throw (ex-info "cannot read nil key" {})))
    (set! keys-used (conj! keys-used key))
    (-lookup data key))

  (-lookup [_ key default]
    (when (nil? key)
      (throw (ex-info "cannot read nil key" {})))
    (set! keys-used (conj! keys-used key))
    (-lookup data key default)))

(defn observed [data]
  (ObservedData. (transient #{}) data))

(deftype TransactedData
  [^not-native data
   keys-new
   keys-updated
   keys-removed
   completed-ref]

  IMeta
  (-meta [_]
    (-meta data))

  gp/TxData
  (commit! [_]
    (vreset! completed-ref true)
    {:data data
     :keys-new (persistent! keys-new)
     :keys-updated (persistent! keys-updated)
     :keys-removed (persistent! keys-removed)})

  ILookup
  (-lookup [this key]
    (.check-completed! this)
    (-lookup data key))

  (-lookup [this key default]
    (.check-completed! this)
    (-lookup data key default))

  ICounted
  (-count [this]
    (.check-completed! this)
    (-count data))

  IMap
  (-dissoc [this key]
    (.check-completed! this)

    (let [key-is-ident?
          (db/ident? key)

          next-data
          (-> data
              (cond->
                key-is-ident?
                (-> (-dissoc key)
                    (update (db/coll-key key) disj key))))

          next-removed
          (-> keys-removed
              (conj! key)
              (cond->
                key-is-ident?
                (conj! (db/coll-key key))))]

      (TransactedData.
        next-data
        keys-new
        keys-updated
        next-removed
        completed-ref)))

  IAssociative
  (-assoc [this key value]
    (.check-completed! this)

    (when (nil? key)
      (throw (ex-info "nil key not allowed" {:value value})))

    ;; FIXME: should it really check each write if anything changed?
    ;; FIXME: enforce that ident keys have a map value with ::ident key?
    (let [prev-val
          (-lookup data key ::not-found)

          key-is-ident?
          (db/ident? key)]

      (if (identical? prev-val value)
        this
        (if (= ::not-found prev-val)
          ;; new
          (if-not key-is-ident?
            ;; new non-ident key
            (TransactedData.
              (-assoc data key value)
              (conj! keys-new key)
              keys-updated
              keys-removed
              completed-ref)

            ;; new ident
            (TransactedData.
              (-> data
                  (-assoc key value)
                  (update (db/coll-key key) db/set-conj key))
              (conj! keys-new key)
              (conj! keys-updated (db/coll-key key))
              keys-removed
              completed-ref))

          ;; update, non-ident key
          (if-not key-is-ident?
            (TransactedData.
              (-assoc data key value)
              keys-new
              (conj! keys-updated key)
              keys-removed
              completed-ref)

            ;; FIXME: no need to track (ident-key key) since it should be present?
            (TransactedData.
              (-assoc data key value)
              keys-new
              (-> keys-updated
                  (conj! key)
                  ;; need to update the entity-type collection since some queries might change if one in the list changes
                  ;; FIXME: this makes any update potentially expensive, maybe should leave this to the user?
                  (conj! (db/coll-key key)))
              keys-removed
              completed-ref))
          ))))

  Object
  (^clj check-completed! [this]
    (when @completed-ref
      (throw (ex-info "transaction concluded, don't hold on to db while in tx" {})))
    ))

(defn transacted [data]
  (TransactedData.
    data
    (transient #{})
    (transient #{})
    (transient #{})
    (volatile! false)))

;; FIXME: very inefficient, maybe worth maintaining an index
(defn invalidate-queries! [active-queries keys-to-invalidate]
  (reduce-kv
    (fn [_ query-id ^ActiveQuery query]
      (run!
        (fn [key]
          (when (.affected-by-key? query key)
            (.refresh! query)))
        keys-to-invalidate)
      nil)
    nil
    active-queries))

(defn tx*
  [{::keys [config-ref data-ref active-queries-ref]
    :as env}
   ev-id
   params]
  ;; (js/console.log ::db-tx env tx-id params)

  (let [{:keys [interceptors ^function handler-fn] :as event}
        (get-in @config-ref [:events ev-id])]

    (if-not event
      (js/console.warn "no event handler for" ev-id params)
      (let [before @data-ref

            tx-db
            (transacted before)

            {tx-after :db :as result}
            (handler-fn (assoc env :db tx-db) params)]

        ;; FIXME: move all of this out to interceptor chain

        (let [config @config-ref]
          (reduce-kv
            (fn [_ key value]
              (let [fx-fn (get-in config [:fx key])]
                (if-not fx-fn
                  (js/console.warn "invalid fx" key value)
                  (fx-fn env value))))
            nil
            (dissoc result :db)))

        (when (seq interceptors)
          (js/console.warn "TBD, ignored interceptors for event" ev-id))

        (when tx-after
          (let [{:keys [data keys-new keys-removed keys-updated] :as result}
                (gp/commit! tx-after)

                keys-to-invalidate
                (set/union keys-new keys-removed keys-updated)]

            ;; (js/console.log "invalidated keys" keys-to-invalidate @active-queries-ref)

            (when-not (identical? @data-ref before)
              (throw (ex-info "someone messed with app-state while in tx" {})))

            (reset! data-ref data)

            (when-not (identical? before data)
              (invalidate-queries! @active-queries-ref keys-to-invalidate))

            data))))))

(defn run-tx [env ev-id params]
  (tx* env ev-id params))

(defn reg-event-fx [{::keys [config-ref] :as env} ev-id interceptors handler-fn]
  {:pre [(map? env)
         (keyword? ev-id)
         (vector? interceptors)
         (fn? handler-fn)]}
  (swap! config-ref assoc-in [:events ev-id]
    {:handler-fn handler-fn
     :interceptors interceptors}))

(defn reg-fx [{::keys [config-ref] :as env} fx-id handler-fn]
  (swap! config-ref assoc-in [:fx fx-id] handler-fn))

(defn prepare [env data-ref]
  (assoc env
    ::config-ref (atom {:events {}})
    ::data-ref data-ref))

(defonce query-queue (js/Promise.resolve))

(deftype ActiveQuery
  [env
   query-id
   ident
   query
   ^:mutable read-keys
   ^:mutable read-result
   ^:mutable pending?]

  Object
  (do-read! [this]
    (let [{::keys [data-ref]} env
          observed-data (observed @data-ref)
          query (if ident [{ident query}] query)
          result (db/query env observed-data query)

          new-result
          (if ident (get result ident) result)]

      (set! read-keys @observed-data)

      (when (not= new-result read-result)
        (set! read-result new-result)
        ;; (js/console.log "did-read" read-keys read-result @data-ref)
        ;; FIXME: we know which data the client already had. could skip over those parts
        ;; but computing that might be more expensive than just replacing it?
        (send-to-main env [:query-result query-id new-result]))))

  (actually-refresh! [this]
    (when pending?
      (set! pending? false)
      (.do-read! this)))

  (refresh! [this]
    ;; this may be called multiple times during one invalidation cycle right now
    ;; so we queue to ensure its only sent out once
    ;; FIXME: fix invalidate-queries! so this isn't necessary
    (set! pending? true)
    (.then query-queue #(.actually-refresh! this)))

  (affected-by-key? [this key]
    (contains? read-keys key)))

(defn init [env]
  (let [tr (transit/reader :json)
        tw (transit/writer :json)

        transit-read
        (fn transit-read [data]
          (transit/read tr data))

        transit-str
        (fn transit-str [obj]
          (transit/write tw obj))

        active-queries-ref
        (atom {})

        env
        (assoc env
          ::active-queries-ref active-queries-ref
          ::transit-read transit-read
          ::transit-str transit-str)]

    (js/self.addEventListener "message"
      (fn [e]
        (let [start (js/performance.now)
              [op & args :as msg] (transit-read (.-data e))
              t (js/performance.now)]
          ;; (js/console.log "worker-read took" (- t start))
          (case op
            :query-init
            (let [[query-id ident query] args
                  q (ActiveQuery. env query-id ident query nil nil true)]
              (swap! active-queries-ref assoc query-id q)
              (.do-read! q))

            ;; FIXME: make sure no more work happens for this, might still be in queue
            :query-destroy
            (let [[query-id] args]
              (swap! active-queries-ref dissoc query-id))

            :tx
            (let [[ev-id params] args]
              (tx* env ev-id params))

            (js/console.warn "unhandled worker msg" msg)))))

    (js/postMessage (transit-str [:worker-ready]))

    env))

