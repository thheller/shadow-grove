(ns shadow.grove.db
  (:refer-clojure :exclude (ident? remove))
  (:require [shadow.grove.db.ident :as ident]))

(defprotocol ITransaction
  (tx-log-new [this key])
  (tx-log-modified [this key])
  (tx-log-removed [this key])
  (tx-check-completed! [this]))

(defprotocol ITransactable
  (tx-begin [this])
  (tx-get [this])
  (db-schema [this]))

(defprotocol ITransactableCommit
  (tx-commit! [this]))

(defprotocol IObserved
  (observed-keys [this]))

#?(:cljs
   (set! *warn-on-infer* false))

#?(:clj
   (defn keyword-identical? [a b]
     (identical? a b)))

(defn- set-conj [x y]
  (if (nil? x)
    #{y}
    (conj x y)))

(defn make-ident [type id]
  #?(:clj (ident/->Ident type id)
     :cljs (ident/->Ident type id nil)))

(defn ident? [thing]
  (ident/ident? thing))

(defn ident-key [thing]
  {:pre [(ident/ident? thing)]}
  #?(:clj (:entity-type thing)
     :cljs (.-entity-type ^Ident thing)))

(defn ident-val [thing]
  {:pre [(ident/ident? thing)]}
  #?(:clj (:id thing)
     :cljs (.-id ^Ident thing)))

(defn ident-as-vec [ident]
  [(ident-key ident)
   (ident-val ident)])

(defn parse-joins [spec joins]
  (reduce-kv
    (fn [spec attr val]
      (if-not (and (vector? val)
                   (or (= :one (first val))
                       (= :many (first val))))
        (throw (ex-info "invalid join" joins))

        ;; FIXME: actually make use of :one/:many, right now relying and user supplying proper value
        (update spec :joins assoc attr (second val))))
    spec
    joins))

(defn parse-primary-key
  [{:keys [entity-type] :as spec}
   {:keys [primary-key] :as config}]
  (cond
    (and (not primary-key) (:ident-gen config))
    spec

    (keyword? primary-key)
    (assoc spec
      :ident-gen
      #(make-ident entity-type (get % primary-key)))

    (and (vector? primary-key) (every? keyword? primary-key))
    (assoc spec
      :ident-gen
      (fn [item]
        (make-ident entity-type
          (mapv #(get item %) primary-key))))

    :else
    (throw (ex-info "invalid :primary-key" config))))

(defn parse-entity-spec [entity-type {:keys [joins] :as config}]
  {:pre [(keyword? entity-type)]}

  (-> (assoc config :entity-type entity-type :joins {})
      (parse-primary-key config)
      (parse-joins joins)
      ))

(defn parse-schema [spec]
  (reduce-kv
    (fn [schema key {:keys [type] :as config}]
      (cond
        (= :entity type)
        (assoc-in schema [:entities key] (parse-entity-spec key config))

        ;; only have entities for now, will need custom config later
        :else
        (throw (ex-info "unknown type" {:key key :config config}))
        ))
    {:entities {}}
    spec))

(defn nav-fn [db key val]
  (cond
    (ident? val)
    (get db val)

    (coll? val)
    (vary-meta val assoc
      'clojure.core.protocols/datafy
      (fn [m]
        (vary-meta m assoc
          'clojure.core.protocols/nav
          (fn [m key val]
            (if (ident? val)
              (get db val)
              val)))))

    :else
    val))


(defn coll-key [thing]
  {:pre [(ident? thing)]}
  [::all (ident-key thing)])



#?(:clj
   (deftype ObservedData
     [^:unsynchronized-mutable keys-used
      ^clojure.lang.IPersistentMap data]
     IObserved
     (observed-keys [_]
       (persistent! keys-used))

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
   (deftype ObservedData [^:mutable keys-used ^not-native data]
     IObserved
     (observed-keys [_]
       (persistent! keys-used))

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

(deftype Transaction
  [data-before
   keys-new
   keys-updated
   keys-removed
   completed-ref]

  ITransaction
  (tx-check-completed! [this]
    (when @completed-ref
      (throw (ex-info "tx already commited!" {}))))

  (tx-log-new [this key]
    (Transaction.
      data-before
      (conj! keys-new key)
      keys-updated
      keys-removed
      completed-ref))

  (tx-log-modified [this key]
    (Transaction.
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
      (Transaction.
        data-before
        ;; removal means the key is no longer new or modified
        (disj! keys-new key)
        (disj! keys-updated key)
        ;; if created and removed in same tx, just don't record it at all
        (if was-added-in-tx?
          keys-removed
          (conj! keys-removed key))
        completed-ref)))

  ITransactableCommit
  (tx-commit! [this]
    (vreset! completed-ref true)
    {:data-before data-before
     :keys-new (persistent! keys-new)
     :keys-updated (persistent! keys-updated)
     :keys-removed (persistent! keys-removed)}))


(deftype GroveDB
  #?@(:clj
      [[schema
        ^clojure.lang.IPersistentMap data
        ^Transaction tx]

       clojure.lang.IDeref
       (deref [_]
         data)

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

         ;; FIXME: should it really check each write if anything changed?
         (let [prev-val (.valAt data key ::not-found)]

           (cond
             (identical? prev-val value)
             this

             ;; new
             (= ::not-found prev-val)
             (if-not (ident? key)
               ;; new non-ident key
               (GroveDB.
                 schema
                 (assoc data key value)
                 (when tx
                   (tx-log-new tx key)))

               ;; new ident
               (GroveDB.
                 schema
                 (-> data
                     (assoc key value)
                     (update (coll-key key) set-conj key))
                 (when tx
                   (-> tx
                       (tx-log-new key)
                       (tx-log-modified (coll-key key))))))

             ;; update
             :else-is-update
             (GroveDB.
               schema
               (assoc data key value)
               (when tx
                 (tx-log-modified tx key)))
             )))

       ;; FIXME: whats the difference between assoc and assocEx again?
       (assocEx [this key value]
         (when tx
           (tx-check-completed! tx))

         (when (nil? key)
           (throw (ex-info "nil key not allowed" {:value value})))

         (let [prev-val (.valAt data key ::not-found)]

           (cond
             (identical? prev-val value)
             this

             ;; new
             (= ::not-found prev-val)
             (if-not (ident? key)
               ;; new non-ident key
               (GroveDB.
                 schema
                 (.assocEx data key value)
                 (when tx
                   (tx-log-new tx key)))

               ;; new ident
               (GroveDB.
                 schema
                 (-> data
                     (.assocEx key value)
                     (update (coll-key key) set-conj key))
                 (when tx
                   (-> tx
                       (tx-log-new key)
                       (tx-log-modified (coll-key key))))))

             ;; update, non-ident key
             :else-is-update
             (GroveDB.
               schema
               (.assocEx data key value)
               (when tx
                 (tx-log-modified tx key)))
             )))

       (without [this key]
         (when tx
           (tx-check-completed! tx))

         (let [key-is-ident? (ident? key)]

           (GroveDB.
             schema
             (-> (.without data key)
                 (cond->
                   key-is-ident?
                   (update (coll-key key) disj key)))
             (when tx
               (-> tx
                   (tx-log-removed key)
                   (cond->
                     key-is-ident?
                     (tx-log-modified (coll-key key))))))))]

      :cljs
      [[schema
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

       IMap
       (-dissoc [this key]
         (when tx
           (tx-check-completed! tx))

         (let [key-is-ident? (ident? key)]

           (GroveDB.
             schema
             (-> (-dissoc data key)
                 (cond->
                   key-is-ident?
                   (update (coll-key key) disj key)))
             (when tx
               (-> tx
                   (tx-log-removed key)
                   (cond->
                     key-is-ident?
                     (tx-log-modified (coll-key key))))))))

       IAssociative
       (-contains-key? [coll k]
         (-contains-key? data k))

       (-assoc [this key value]
         (when tx
           (tx-check-completed! tx))

         (when (nil? key)
           (throw (ex-info "nil key not allowed" {:value value})))

         ;; FIXME: should it really check each write if anything changed?
         ;; FIXME: enforce that ident keys have a map value with ::ident key?
         (let [prev-val (-lookup data key ::not-found)]

           (cond
             (identical? prev-val value)
             this

             ;; new
             (= ::not-found prev-val)
             (if-not (ident? key)
               ;; new non-ident key
               (GroveDB.
                 schema
                 (-assoc data key value)
                 (when tx
                   (tx-log-modified tx key)))

               ;; new ident
               (GroveDB.
                 schema
                 (-> data
                     (-assoc key value)
                     (update (coll-key key) set-conj key))
                 (when tx
                   (-> tx
                       (tx-log-new key)
                       (tx-log-modified (coll-key key))))))

             ;; update, non-ident key
             :else-is-update
             (GroveDB.
               schema
               (-assoc data key value)
               (when tx
                 (tx-log-modified tx key)))
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

  ITransactable
  (db-schema [this]
    schema)
  (tx-get [this]
    tx)
  (tx-begin [this]
    (when tx
      (throw (ex-info "already in tx" {})))

    (GroveDB.
      schema
      data
      (Transaction.
        data
        (transient #{})
        (transient #{})
        (transient #{})
        (volatile! false))))

  ITransactableCommit
  (tx-commit! [_]
    (when-not tx
      (throw (ex-info "not in transaction" {})))
    (assoc
      (tx-commit! tx)
      :db (GroveDB. schema data nil)
      :data data)))

(defn transacted [^GroveDB db]
  (tx-begin db))

(defn observed [^GroveDB db]
  (ObservedData.
    (transient #{})
    ;; we just need the data map
    ;; checking so this can also work with regular maps
    (if (instance? GroveDB db) @db db)))

(defn configure
  ([spec]
   (configure {} spec))
  ([init-db spec]
   ;; FIXME: should this use a special key instead of meta?
   (let [schema (parse-schema spec)]
     (GroveDB. schema init-db nil))))

(defn- normalize* [imports schema entity-type item]
  (let [{:keys [ident-gen id-pred joins] :as ent-config}
        (get-in schema [:entities entity-type])

        item-ident
        (get item :db/ident)

        ident
        (ident-gen item)

        _ (when (and item-ident (not= item-ident ident))
            (throw (ex-info "item contained ident but we generated a different one" {:item item :ident ident})))

        ;; FIXME: can an item ever have more than one ident?
        item
        (if (= item-ident ident)
          item
          (assoc item :db/ident ident))

        item
        (reduce-kv
          (fn [item key join-type]
            (let [curr-val
                  (get item key ::skip)

                  norm-val
                  (cond
                    (keyword-identical? ::skip curr-val)
                    curr-val

                    ;; already normalized, no nothing
                    (ident? curr-val)
                    ::skip

                    (map? curr-val)
                    (normalize* imports schema join-type curr-val)

                    (vector? curr-val)
                    (mapv #(normalize* imports schema join-type %) curr-val)

                    ;; FIXME: add back predicate to check if curr-val is valid id-val to make ident
                    ;; might be garbage leading to invalid ident stored in norm db
                    (some? curr-val)
                    (make-ident join-type curr-val)

                    :else
                    (throw (ex-info "unexpected value in join attr"
                             {:item item
                              :key key
                              :val curr-val
                              :type type})))]

              (if (keyword-identical? norm-val ::skip)
                item
                (assoc item key norm-val))))
          item
          joins)]

    (swap! imports conj [ident item])

    ident))

(defn- normalize
  "returns a seq of [[ident item] ...] tuples"
  [schema entity-type vals]
  (let [imports (atom [])]

    (cond
      (map? vals)
      (normalize* imports schema entity-type vals)

      (sequential? vals)
      (doseq [item vals]
        (normalize* imports schema entity-type item))

      :else
      (throw (ex-info "cannot import" {:entity-type entity-type :vals vals})))

    @imports
    ))

(defn merge-or-replace [left right]
  (if (keyword-identical? :db/loading left)
    right
    (merge left right)))

(defn- merge-imports [data imports]
  (reduce
    (fn [data [ident item]]
      (update data ident merge-or-replace item))
    data
    imports))

(defn merge-seq
  ([data entity-type coll]
   (merge-seq data entity-type coll nil))
  ([data entity-type coll target-path-or-fn]
   {:pre [(sequential? coll)]}
   (let [schema
         (db-schema data)

         {:keys [ident-gen] :as entity-spec}
         (get-in schema [:entities entity-type])

         _ (when-not entity-spec
             (throw (ex-info "entity not defined" {:entity-type entity-type})))

         idents
         (->> coll
              (map ident-gen)
              (into []))

         imports
         (normalize schema entity-type coll)]

     (-> data
         (merge-imports imports)
         (cond->
           (vector? target-path-or-fn)
           (assoc-in target-path-or-fn idents)

           (fn? target-path-or-fn)
           (target-path-or-fn idents))
         ))))

(defn add
  ([data entity-type item]
   (add data entity-type item nil))
  ([data entity-type item target-path]
   {:pre [(map? item)]}
   (let [schema
         (db-schema data)

         {:keys [ident-gen] :as entity-spec}
         (get-in schema [:entities entity-type])

         _ (when-not entity-spec
             (throw (ex-info "entity not defined" {:entity-type entity-type})))

         ident
         (ident-gen item)

         imports
         (normalize schema entity-type [item])]

     (-> data
         (merge-imports imports)
         (cond->
           (fn? target-path)
           (target-path ident)
           (vector? target-path)
           (update-in target-path conj ident))))))

(defn update-entity [data entity-type id update-fn & args]
  ;; FIXME: validate that both entity-type is defined and id matches type
  (update data (make-ident entity-type id) #(apply update-fn % args)))

(defn all-idents-of [db entity-type]
  ;; FIXME: check in schema if entity-type is actually declared
  (get db [::all entity-type]))

(defn all-of [db entity-type]
  (->> (all-idents-of db entity-type)
       (map #(get db %))))

;; keep this as the very last thing since we excluded clojure remove
;; don't want to write code that assumes it uses core remove
(defn remove [data thing]
  (cond
    (ident? thing)
    (dissoc data thing)

    (and (map? thing) (:db/ident thing))
    (dissoc data (:db/ident thing))

    :else
    (throw (ex-info "don't know how to remove thing" {:thing thing}))))

(defn remove-idents [data idents]
  (reduce remove data idents))

