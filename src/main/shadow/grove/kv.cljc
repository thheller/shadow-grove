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

(defprotocol IWriteLog
  (tx-log-new [this key])
  (tx-log-modified [this key])
  (tx-log-removed [this key])
  (tx-check-completed! [this])
  (tx-complete! [this]))

(defprotocol IObservable
  (observe [this]))

(defprotocol ITransactable
  (tx-begin [this])
  (tx-snapshot [this])
  (tx-commit! [this]))

(defprotocol IConfigured
  (get-config [this]))

;; FIXME: make clj variant seqable as well
#?(:cljs
   (deftype ObservedDataSeq
     [^not-native data ^not-native key-seq]
     ISeqable
     (-seq [this]
       this)

     ISeq
     (-first [this]
       (let [k (-first key-seq)]
         ;; do lookup so that it is recorded as used key
         (MapEntry. k (-lookup data k) nil)))

     (-rest [this]
       (let [r (-rest key-seq)]
         (if (not= r ())
           (ObservedDataSeq. data r)
           ())))))

#?(:clj
   (deftype ObservedData
     [^:unsynchronized-mutable keys-used
      ^:unsynchornized-mutable seq-used
      ^clojure.lang.IPersistentMap data]
     IObserved
     (observed-keys [_]
       [seq-used (persistent! keys-used)])

     clojure.lang.IMeta
     (meta [_]
       (.meta data))

     ;; FIXME: implement rest of seq functions

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

(deftype WriteLog
  #?@(:cljs
      [[data-before
        ^:mutable keys-new
        ^:mutable keys-updated
        ^:mutable keys-removed
        completed-ref]]

      :clj
      [[data-before
        ^:unsynchronized-mutable keys-new
        ^:unsynchronized-mutable keys-updated
        ^:unsynchronized-mutable keys-removed
        completed-ref]])

  #?(:cljs IDeref :clj clojure.lang.IDeref)
  (#?(:cljs -deref :clj deref) [this]
    ;; FIXME: is this the only way to get a persistent snapshot, but continue with the transient?
    ;; can't be handing out the transient and can't turn into new set via (set keys-new) or whatever
    ;; FIXME: figure out if this transient handling is even worth, it likely never is the bottleneck
    (let [new (persistent! keys-new)
          updated (persistent! keys-updated)
          removed (persistent! keys-removed)]

      (set! keys-new (transient new))
      (set! keys-updated (transient updated))
      (set! keys-removed (transient removed))

      {:data-before data-before
       :keys-new new
       :keys-updated updated
       :keys-removed removed}))

  IWriteLog
  (tx-check-completed! [this]
    (when @completed-ref
      (throw (ex-info "tx already commited!" {}))))

  (tx-log-new [this key]
    (WriteLog.
      data-before
      (conj! keys-new key)
      keys-updated
      keys-removed
      completed-ref))

  (tx-log-modified [this key]
    (WriteLog.
      data-before
      keys-new
      ;; new keys are only recorded as new, not modified in the same tx
      (if (contains? keys-new key)
        keys-updated
        (conj! keys-updated key))
      keys-removed
      completed-ref))

  (tx-log-removed [this key]
    (let [was-added-in-tx? (contains? keys-new key)]
      (WriteLog.
        data-before
        ;; removal means the key is no longer new or modified
        (disj! keys-new key)
        (disj! keys-updated key)
        ;; if created and removed in same tx, just don't record it at all
        (if was-added-in-tx?
          keys-removed
          (conj! keys-removed key))
        completed-ref)))

  (tx-complete! [this]
    (vreset! completed-ref true)
    {:data-before data-before
     :keys-new (persistent! keys-new)
     :keys-updated (persistent! keys-updated)
     :keys-removed (persistent! keys-removed)}))

(deftype GroveKV
  #?@(:clj
      [[config
        ^clojure.lang.IPersistentMap data
        ^WriteLog tx]

       clojure.lang.IDeref
       (deref [_]
         data)

       clojure.lang.Seqable
       (seq [this]
         (seq data))

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
         (when tx
           (tx-check-completed! tx))

         (when (nil? key)
           (throw (ex-info "nil key not allowed" {:value value})))

         (let [prev-val (.valAt data key NOT-FOUND)]
           (if (identical? prev-val value)
             this
             (if (identical? NOT-FOUND prev-val)
               ;; new
               (GroveKV.
                 config
                 (assoc data key value)
                 (when tx
                   (tx-log-new tx key)))
               ;; update
               (GroveKV.
                 config
                 (assoc data key value)
                 (when tx
                   (tx-log-modified tx key))))
             )))

       ;; FIXME: whats the difference between assoc and assocEx again?
       (assocEx [this key value]
         (when tx
           (tx-check-completed! tx))

         (when (nil? key)
           (throw (ex-info "nil key not allowed" {:value value})))

         (let [prev-val (.valAt data key NOT-FOUND)]

           (if (identical? prev-val value)
             this
             (if (identical? NOT-FOUND prev-val)
               ;; new
               (GroveKV.
                 config
                 (.assocEx data key value)
                 (when tx
                   (tx-log-new tx key)))
               ;; update
               (GroveKV.
                 config
                 (.assocEx data key value)
                 (when tx
                   (tx-log-modified tx key)))))))

       (without [this key]
         (when tx
           (tx-check-completed! tx))

         (GroveKV.
           config
           (.without data key)
           (when tx
             (tx-log-removed tx key))))]

      :cljs
      [[config
        ^not-native data
        ^not-native tx]

       IDeref
       (-deref [_]
         data)

       ILookup
       (-lookup [this key]
         (when tx
           (tx-check-completed! tx))
         (-lookup data key))

       (-lookup [this key default]
         (when tx
           (tx-check-completed! tx))
         (-lookup data key default))

       ICounted
       (-count [this]
         (when tx
           (tx-check-completed! tx))
         (-count data))

       ISeqable
       (-seq [this]
         (-seq data))

       IMap
       (-dissoc [this key]
         (when tx
           (tx-check-completed! tx))

         (GroveKV.
           config
           (-dissoc data key)
           (when tx
             (tx-log-removed tx key))))

       IAssociative
       (-contains-key? [coll k]
         (-contains-key? data k))

       (-assoc [this key value]
         (when tx
           (tx-check-completed! tx))

         (when (nil? key)
           (throw (ex-info "nil key not allowed" {:value value})))

         ;; FIXME: only allow modifications while in tx?

         ;; validation should be done here and not in tx interceptor
         ;; should fail as soon as possible so dev knows where invalid data was written

         ;; FIXME: would be nice if validate could supply some info why it was rejected?
         ;; maybe swap it so that any true-ish return is treated as an error and added to ex-data?
         (when-some [validate-key (:validate-key config)]
           (when-not (validate-key key)
             (throw (ex-info "tried to insert invalid key" {:kv-id (:kv-id config) :key key :val val}))))

         (when-some [validate-val (:validate-val config)]
           (when-not (validate-val val)
             (throw (ex-info "tried to insert invalid val" {:kv-id (:kv-id config) :key key :val val}))))

         (let [prev-val (-lookup data key NOT-FOUND)]
           (if (identical? prev-val value)
             this
             (GroveKV.
               config
               (-assoc data key value)
               (when tx
                 (if (identical? NOT-FOUND prev-val)
                   (tx-log-new tx key)
                   (tx-log-modified tx key))))
             )))

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
       ])

  IConfigured
  (get-config [this]
    config)

  IObservable
  (observe [this]
    (when tx
      (throw (ex-info "cannot be observed while in tx" {})))
    (ObservedData. false (transient #{}) data))

  ITransactable
  (tx-snapshot [this]
    (when tx
      @tx))

  (tx-begin [this]
    (when tx
      (throw (ex-info "already in tx" {})))

    (GroveKV.
      config
      data
      (WriteLog.
        data
        (transient #{})
        (transient #{})
        (transient #{})
        (volatile! false))))

  (tx-commit! [_]
    (when-not tx
      (throw (ex-info "not in transaction" {})))

    (assoc (tx-complete! tx)
      :kv (GroveKV. config data nil)
      ;; expose data and data-before (from tx)
      ;; so that other places can cheaply check if changes occurred via identical?
      ;; and can get values for changed keys from the regular maps
      :data data)))

#?(:clj
   ;; need this as GroveKV implements IPersistentMap and IDeref
   ;; print otherwise throws since multimethod can't decide which
   (defmethod print-method GroveKV [tbl ^java.io.Writer writer]
     (.write writer "#grove/kv [")
     (print-method (.-data tbl) writer)
     (.write writer " ")
     ;; FIXME: need print-method for Transaction
     ;; not yet sure what to expose, so waiting till actually used someplace
     (print-method (.-tx tbl) writer)
     (.write writer "]")))


(defn get-kv! [env kv-id]
  (let [kv (get env kv-id)]
    (when-not (instance? GroveKV kv)
      (throw (ex-info "can only work with grove-kv" {:kv-id kv-id :not-kv kv})))
    kv))


;; utility fns to work with grove kv
;; although totally valid to just use assoc and stuff
;; this should make for a nicer API hopefully

(defn init [kv-id config init-data]
  (GroveKV. (assoc config :kv-id kv-id) init-data nil))

(defn set [env kv-id key val]
  (let [kv (get-kv! env kv-id)]
    (assoc env kv-id (assoc kv key val))))

(defn update-val [env kv-id key update-fn & args]
  (let [kv (get-kv! env kv-id)
        val (get kv key)
        next-val (apply update-fn val args )]
    (assoc env kv-id (assoc kv key next-val))))

(defn add [env kv-id val]
  (js/console.warn "supposed to add" kv-id val)
  env)

(defn- normalize* [imports-ref env kv-id item]
  (let [kv
        (get-kv! env kv-id)

        {:keys [primary-key joins]}
        (get-config kv)

        pkey
        (primary-key item)

        _ (when-not pkey
            (throw (ex-info "item with invalid primary key" {:kv-id kv-id :item item :pkey pkey})))

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

    (swap! imports-ref conj [kv-id pkey item])

    pkey))

(defn- normalize
  "returns a seq of [[ident item] ...] tuples"
  [env kv-id vals]
  (let [imports-ref (atom [])]

    (cond
      (map? vals)
      (normalize* imports-ref env kv-id vals)

      (sequential? vals)
      (doseq [item vals]
        (normalize* imports-ref env kv-id item))

      :else
      (throw (ex-info "cannot import" {:kv-id kv-id :vals vals})))

    @imports-ref
    ))

(defn merge-or-replace [left right]
  (if (keyword-identical? :db/loading left)
    right
    (merge left right)))

(defn- merge-imports [env imports]
  (reduce
    (fn [env [kv-id pkey item]]
      (update-in env [kv-id pkey] merge-or-replace item))
    env
    imports))

(defn merge-seq
  ([env kv-id coll]
   (merge-seq env kv-id coll (fn [env items] env)))
  ([env kv-id coll target-path-or-fn]
   {:pre [(map? env)
          (keyword? kv-id)
          (sequential? coll)
          (or (fn? target-path-or-fn)
              (vector? target-path-or-fn))]}

   (let [^not-native kv
         (get-kv! env kv-id)

         {:keys [primary-key] :as config}
         (get-config kv)

         coll-keys
         (->> coll
              (map primary-key)
              (into []))

         imports
         (normalize env kv-id coll)]

     (-> env
         (merge-imports imports)
         (cond->
           (fn? target-path-or-fn)
           (target-path-or-fn coll-keys)

           (vector? target-path-or-fn)
           (assoc-in target-path-or-fn coll-keys)
           )))))

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