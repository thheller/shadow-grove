(ns shadow.grove.kv
  (:refer-clojure :exclude (set)))

#?(:cljs
   (set! *warn-on-infer* false))

(def NOT-FOUND
  #?(:cljs
     (js/Object.)
     :clj
     (Object.)))

(defprotocol IObserved
  (observed-keys [this]))

#?(:clj
   (deftype ObservedData
     [^:unsynchronized-mutable seq-used
      ^:unsynchronized-mutable keys-used
      ^clojure.lang.IPersistentMap data]
     IObserved
     (observed-keys [_]
       [seq-used (persistent! keys-used)])

     clojure.lang.IMeta
     (meta [_]
       (.meta data))

     clojure.lang.Seqable
     (seq [this]
       (set! seq-used true)
       (seq data))

     clojure.lang.IPersistentMap
     (assoc [this key val]
       (throw (ex-info "read-only" {})))

     (assocEx [this key val]
       (throw (ex-info "read-only" {})))

     (without [this key]
       (throw (ex-info "read-only" {})))

     (containsKey [this key]
       (when (nil? key)
         (throw (ex-info "cannot read nil key" {})))
       (set! keys-used (conj! keys-used key))
       (.containsKey data key))

     (valAt [this key]
       (when (nil? key)
         (throw (ex-info "cannot read nil key" {})))
       (set! keys-used (conj! keys-used key))
       (.valAt data key))

     (valAt [this key not-found]
       (when (nil? key)
         (throw (ex-info "cannot read nil key" {})))
       (set! keys-used (conj! keys-used key))
       (.valAt data key not-found))

     (entryAt [this key]
       (when (nil? key)
         (throw (ex-info "cannot read nil key" {})))
       (set! keys-used (conj! keys-used key))
       (.entryAt data key)))

   :cljs
   (deftype ObservedData [^:mutable seq-used ^:mutable keys-used ^not-native data]
     IObserved
     (observed-keys [_]
       [seq-used (persistent! keys-used)])

     IMeta
     (-meta [_]
       (-meta data))

     ;; map? predicate checks for this protocol
     IMap
     (-dissoc [coll k]
       (throw (ex-info "observed data is read-only" {})))

     IAssociative
     (-contains-key? [coll k]
       (-contains-key? data k))
     (-assoc [coll k v]
       (throw (ex-info "observed data is read-only, assoc not allowed" {:k k :v v})))

     ISeqable
     (-seq [this]
       ;; FIXME: would be nice to only track which keys the seq used
       ;; which the custom ObservedDataSeq ensures
       ;; but we also need to trigger when new things are added
       ;; so the simplest way is to just always trigger
       ;; vs. tracking if a seq was fully traversed?
       (set! seq-used true)
       (-seq data))

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
       (-lookup data key default))))

(defn observed [data]
  (ObservedData. false (transient #{}) data))

#?(:clj
   (defprotocol ITxCheck
     (check-completed! [this])))

(defprotocol ITxCommit
  (commit! [this]))

(declare ->TransactedData)

(defn tx-log-new [data-before data key keys-new keys-updated keys-removed completed-ref]
  (->TransactedData
    data-before
    data
    (conj! keys-new key)
    keys-updated
    keys-removed
    completed-ref))

(defn tx-log-modified [data-before data key keys-new keys-updated keys-removed completed-ref]
  (->TransactedData
    data-before
    data
    keys-new
    ;; new keys are only recorded as new, not modified in the same tx
    (if (contains? keys-new key)
      keys-updated
      (conj! keys-updated key))
    keys-removed
    completed-ref))

(defn tx-log-removed [data-before data key keys-new keys-updated keys-removed completed-ref]
  (let [was-added-in-tx? (contains? keys-new key)]
    (->TransactedData
      data-before
      data
      ;; removal means the key is no longer new or modified
      (disj! keys-new key)
      (disj! keys-updated key)
      ;; if created and removed in same tx, just don't record it at all
      (if was-added-in-tx?
        keys-removed
        (conj! keys-removed key))
      completed-ref)))

#?(:clj
   (deftype TransactedData
     [^clojure.lang.IPersistentMap data-before
      ^clojure.lang.IPersistentMap data
      keys-new
      keys-updated
      keys-removed
      ;; using a ref not a mutable local since it must apply to all created instances of this
      ;; every "write" creates a new instance
      completed-ref]

     ;; useful for debugging purposes that want the actual data
     clojure.lang.IDeref
     (deref [_]
       data)

     clojure.lang.IMeta
     (meta [_]
       (.meta data))

     clojure.lang.IPersistentMap
     (count [this]
       (.count data))

     (containsKey [this key]
       (when (nil? key)
         (throw (ex-info "cannot read nil key" {})))
       (.containsKey data key))

     (valAt [this key]
       (when (nil? key)
         (throw (ex-info "cannot read nil key" {})))
       (.valAt data key))

     (valAt [this key not-found]
       (when (nil? key)
         (throw (ex-info "cannot read nil key" {})))
       (.valAt data key not-found))

     (entryAt [this key]
       (when (nil? key)
         (throw (ex-info "cannot read nil key" {})))
       (.entryAt data key))

     (assoc [this key value]
       (check-completed! this)

       (when (nil? key)
         (throw (ex-info "nil key not allowed" {:value value})))

       ;; validation should be done here and not in tx interceptor after
       ;; should fail as soon as possible, so dev knows where invalid data was written
       (let [config (::config (meta data))]

         ;; FIXME: would be nice if validate could supply some info why it was rejected?
         ;; maybe swap it so that any true-ish return is treated as an error and added to ex-data?
         (when-some [validate-key (:validate-key config)]
           (when-not (validate-key key)
             (throw (ex-info "tried to insert invalid key" {:kv-table (:kv-table config) :key key :val val}))))

         (when-some [validate-val (:validate-val config)]
           (when-not (validate-val value)
             (throw (ex-info "tried to insert invalid val" {:kv-table (:kv-table config) :key key :val val})))))

       (let [prev-val (.valAt data key NOT-FOUND)]
         (if (identical? prev-val value)
           this
           (if (identical? NOT-FOUND prev-val)
             (tx-log-new data-before (assoc data key value) key keys-new keys-updated keys-removed completed-ref)
             (tx-log-modified data-before (assoc data key value) key keys-new keys-updated keys-removed completed-ref)
             ))))

     (assocEx [this key value]
       (check-completed! this)

       (when (nil? key)
         (throw (ex-info "nil key not allowed" {:value value})))

       (let [prev-val (.valAt data key NOT-FOUND)]
         (if (identical? prev-val value)
           this
           (if (identical? NOT-FOUND prev-val)
             (tx-log-new data-before (.assocEx data key value) key keys-new keys-updated keys-removed completed-ref)
             (tx-log-modified data-before (.assocEx data key value) key keys-new keys-updated keys-removed completed-ref)
             ))))

     (without [this key]
       (check-completed! this)
       (tx-log-removed
         data-before
         (.without data key)
         keys-new keys-removed keys-updated completed-ref))

     ITxCheck
     (check-completed! [this]
       (when @completed-ref
         (throw (ex-info "transaction concluded, don't hold on to db while in tx" {}))))

     ITxCommit
     (commit! [_]
       (vreset! completed-ref true)
       {:data-before data-before
        :data data
        :keys-new (persistent! keys-new)
        :keys-updated (persistent! keys-updated)
        :keys-removed (persistent! keys-removed)}))

   :cljs
   (deftype TransactedData
     [data-before
      ^not-native data
      ^not-native keys-new
      ^not-native keys-updated
      ^not-native keys-removed
      ;; using a ref not a mutable local since it must apply to all created instances of this
      ;; every "write" creates a new instance
      completed-ref]

     ;; useful for debugging purposes that want the actual data
     IDeref
     (-deref [_]
       data)

     IMeta
     (-meta [_]
       (-meta data))

     IWithMeta
     (-with-meta [this meta]
       (.check-completed! this)
       (TransactedData.
         data-before
         (-with-meta data meta)
         keys-new
         keys-updated
         keys-removed
         completed-ref))

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

       (TransactedData.
         data-before
         (-dissoc data key)
         (-disjoin! keys-new key)
         (-disjoin! keys-updated key)
         (-conj! keys-removed key)
         completed-ref))

     IAssociative
     (-contains-key? [coll k]
       (-contains-key? data k))

     (-assoc [this key value]
       (.check-completed! this)

       (when (nil? key)
         (throw (ex-info "nil key not allowed" {:value value})))

       ;; validation should be done here and not in tx interceptor after
       ;; should fail as soon as possible, so dev knows where invalid data was written
       (let [config (::config (meta data))]

         ;; FIXME: would be nice if validate could supply some info why it was rejected?
         ;; maybe swap it so that any true-ish return is treated as an error and added to ex-data?
         (when-some [validate-key (:validate-key config)]
           (when-not (validate-key key)
             (throw (ex-info "tried to insert invalid key" {:kv-table (:kv-table config) :key key :val val}))))

         (when-some [validate-val (:validate-val config)]
           (when-not (validate-val value)
             (throw (ex-info "tried to insert invalid val" {:kv-table (:kv-table config) :key key :val val})))))

       (let [prev-val (-lookup data key NOT-FOUND)]
         (if (identical? prev-val value)
           this
           (if (identical? NOT-FOUND prev-val)
             (tx-log-new data-before (-assoc data key value) key keys-new keys-updated keys-removed completed-ref)
             (tx-log-modified data-before (-assoc data key value) key keys-new keys-updated keys-removed completed-ref)
             ))))

     ICollection
     (-conj [coll ^not-native entry]
       (if (vector? entry)
         (-assoc coll (-nth entry 0) (-nth entry 1))
         (loop [^not-native ret coll
                es (seq entry)]
           (if (nil? es)
             ret
             (let [^not-native e (first es)]
               (if (vector? e)
                 (recur
                   (-assoc ret (-nth e 0) (-nth e 1))
                   (next es))
                 (throw (js/Error. "conj on a map takes map entries or seqables of map entries"))))))))

     ITxCommit
     (commit! [_]
       (vreset! completed-ref true)

       {:data-before data-before
        :data data
        :keys-new (persistent! keys-new)
        :keys-updated (persistent! keys-updated)
        :keys-removed (persistent! keys-removed)})

     Object
     (check-completed! [this]
       (when @completed-ref
         (throw (ex-info "transaction concluded, don't hold on to db while in tx" {}))))))

(defn transacted [data]
  {:pre [(map? data)]}
  (TransactedData.
    data
    data
    (transient #{})
    (transient #{})
    (transient #{})
    (volatile! false)))

(defn get-kv! [env kv-table]
  (let [kv (get env kv-table)]
    (when-not (and (map? kv) (::config (meta kv)))
      (throw (ex-info "can only work with grove-kv" {:kv-table kv-table :not-kv kv})))
    kv))

;; utility fns to work with grove kv
;; although totally valid to just use assoc and stuff
;; this should make for a nicer API hopefully

(defn init [kv-table config init-data]
  (vary-meta init-data assoc
    ::config (assoc config :kv-table kv-table)))

(defn set [env kv-table key val]
  (let [kv (get-kv! env kv-table)]
    (assoc env kv-table (assoc kv key val))))

(defn update-val [env kv-table key update-fn & args]
  (let [kv (get-kv! env kv-table)
        val (get kv key)
        next-val (apply update-fn val args)]
    (assoc env kv-table (assoc kv key next-val))))

(defn- normalize* [imports-ref env kv-table item]
  (let [kv
        (get-kv! env kv-table)

        {:keys [primary-key joins]}
        (::config (meta kv))

        pkey
        (primary-key item)

        _ (when-not pkey
            (throw (ex-info "item with invalid primary key" {:kv-table kv-table :item item :pkey pkey})))

        item
        (reduce-kv
          (fn [item key join-kv]
            (let [curr-val
                  (get item key NOT-FOUND)

                  norm-val
                  (cond
                    (map? curr-val)
                    (normalize* imports-ref env join-kv curr-val)

                    (vector? curr-val)
                    (mapv #(normalize* imports-ref env join-kv %) curr-val)

                    :else
                    curr-val)]

              (if (identical? curr-val norm-val)
                item
                (assoc item key norm-val))))
          item
          joins)]

    (swap! imports-ref conj [kv-table pkey item])

    pkey))

(defn- normalize
  "returns a seq of [[ident item] ...] tuples"
  [env kv-table vals]
  (let [imports-ref (atom [])]

    (cond
      (map? vals)
      (normalize* imports-ref env kv-table vals)

      (sequential? vals)
      (doseq [item vals]
        (normalize* imports-ref env kv-table item))

      :else
      (throw (ex-info "cannot import" {:kv-table kv-table :vals vals})))

    @imports-ref
    ))

(defn- merge-imports [env imports]
  (reduce
    (fn [env [kv-table pkey item]]
      (update-in env [kv-table pkey] merge item))
    env
    imports))

(defn merge-seq
  ([env kv-table coll]
   (merge-seq env kv-table coll (fn [env items] env)))
  ([env kv-table coll target-path-or-fn]
   {:pre [(map? env)
          (keyword? kv-table)
          (sequential? coll)
          (or (fn? target-path-or-fn)
              (vector? target-path-or-fn))]}

   (let [^not-native kv
         (get-kv! env kv-table)

         {:keys [primary-key] :as config}
         (::config (meta kv))

         coll-keys
         (->> coll
              (map primary-key)
              (into []))

         imports
         (normalize env kv-table coll)]

     (-> env
         (merge-imports imports)
         (cond->
           (fn? target-path-or-fn)
           (target-path-or-fn coll-keys)

           (vector? target-path-or-fn)
           (assoc-in target-path-or-fn coll-keys)
           )))))

(defn add [env kv-table val]
  (let [imports (normalize env kv-table [val])]
    (merge-imports env imports)))

(comment
  (let [obs (observe {1 :foo 2 :bar})]
    (get obs 1)
    ;; FIXME: missing CLJ impl
    #_(first obs)
    #_(second obs)
    #_(mapv val obs)

    (let [keys (observed-keys obs)]
      (prn [:keys keys])
      ))

  (let [{:keys [kv] :as tx}
        (-> (GroveKV. {} {} nil)
            (tx-begin)
            (assoc 1 1)
            (assoc 2 2)
            (dissoc 1)
            (tx-commit!))]

    (tap> [:keys (keys kv)])
    (tap> tx)))