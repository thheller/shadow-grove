(ns shadow.experiments.grove
  "grove - a small wood or forested area (ie. trees)
   a mini re-frame/fulcro hybrid. re-frame event styles + somewhat normalized db"
  (:require
    [clojure.set :as set]
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.grove.components :as comp]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.arborist :as sa]))

(defonce active-roots-ref (atom {}))

;; not using an atom as env to make it clearer that it is to be treated as immutable
;; can put mutable atoms in it but env once created cannot be changed.
;; a node in the tree can modify it for its children but only on create.

(defn env [init app-id data-ref]
  (assoc init
    ::app-id app-id
    ::data-ref data-ref
    ::active-queries-ref (atom #{})
    ::config-ref (atom {:events {}})))

(defn start [env root-el root-node]
  (let [active (get @active-roots-ref root-el)]
    (if-not active
      (let [root (sa/dom-root root-el env)]
        (sa/update! root root-node)
        (swap! active-roots-ref assoc root-el {:env env :root root :root-el root-el})
        ::started)

      (let [{:keys [root]} active]
        (assert (identical? env (:env active)) "can't change env between restarts")
        (sa/update! root root-node)
        ::updated
        ))))

(defn stop [root-el]
  (when-let [{::keys [app-root] :as env} (get @active-roots-ref root-el)]
    (swap! active-roots-ref dissoc root-el)
    (p/destroy! app-root)
    (dissoc env ::app-root ::root-el)))

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

(deftype QueryHook
  [ident
   query
   component
   idx
   env
   ^:mutable read-keys
   ^:mutable read-result]

  p/IBuildHook
  (hook-build [this c i]
    (QueryHook. ident query c i (comp/get-env c) nil nil))

  p/IHook
  (hook-init! [this]
    (.do-read! this))

  ;; FIXME: async queries
  (hook-ready? [this] true)
  (hook-value [this] read-result)

  ;; node deps changed, node may have too
  (hook-deps-update! [this val]
    (js/console.log "QueryHook:node-deps-update!" idx this val)
    false)

  ;; node was invalidated and needs update, but its dependencies didn't change
  (hook-update! [this]
    (let [before read-result]
      (.do-read! this)

      ;; FIXME: compare actual query fields only, result may contain other fields
      ;; but should only trigger further updates when actually changed
      (not= before read-result)))

  (hook-destroy! [this]
    (let [{::keys [active-queries-ref]} env]
      ;; FIXME: clear out some fields? probably not necessary, this will be GC'd anyways
      (swap! active-queries-ref disj this)))

  Object
  (do-read! [this]
    (let [{::keys [data-ref active-queries-ref]} env
          observed-data (observed @data-ref)
          query (if ident [{ident query}] query)
          result (db/query env observed-data query)]

      (set! read-keys @observed-data)
      ;; (js/console.log "node query" ident query result read-keys)
      (set! read-result (if ident (get result ident) result))
      (swap! active-queries-ref conj this)))

  (refresh! [this]
    ;; don't read here, just invalidate the component
    ;; other updates may cause this query to be destroyed
    ;; wait till its our turn to actually run again
    (comp/hook-invalidate! component idx))

  (affected-by-key? [this key]
    (contains? read-keys key)))

(defn query
  ([query]
   {:pre [(vector? query)]}
   (QueryHook. nil query nil nil nil nil nil))
  ([ident query]
   {:pre [(db/ident? ident)
          (vector? query)]}
   (QueryHook. ident query nil nil nil nil nil)))

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
  (run!
    (fn [^QueryHook query]
      (run!
        (fn [key]
          (when (.affected-by-key? query key)
            (.refresh! query)))
        keys-to-invalidate))
    active-queries))

(defn tx*
  [{::keys [config-ref data-ref active-queries-ref]
    ::comp/keys [ev-id]
    :as env}
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

(defn tx [env e params]
  (tx* env params))

(defn run-tx [env other params]
  (tx* (assoc env ::comp/ev-id other) params))

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

(defn form [defaults])

(defn form-values [form])

(defn form-reset! [form])