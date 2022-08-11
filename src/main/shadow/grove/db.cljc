(ns shadow.grove.db
  (:refer-clojure :exclude (ident? remove))
  (:require [shadow.grove.db.ident :as ident]))

#?(:cljs
   (set! *warn-on-infer* false))

#?(:clj
   (defn keyword-identical? [a b]
     (identical? a b)))

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

(defn configure
  "Associates `spec` to `init-db` as its schema used for normalization operations.
   
   ---
   Example:
   ```
   (def schema
     {::folder
      {:type :entity
       :primary-key :id
       :attrs {}
       :joins {:folder/contains [:many ::file]}}})
   
   (defonce data-ref
     (-> {}
         (db/configure schema)
         (atom)))
   ```"
  [init-db spec]
  ;; FIXME: should this use a special key instead of meta?
  (let [schema (parse-schema spec)
        m {::schema schema
           ::ident-types (set (keys (:entities schema)))
           ;; FIXME: conditionalize this, not necessary in release builds
           ;; convenient for when tapping the db for inspect
           'clojure.core.protocols/nav nav-fn}]

    (with-meta init-db m)))


(defn coll-key [thing]
  {:pre [(ident? thing)]}
  [::all (ident-key thing)])


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

(comment
  (let [schema
        {:foo
         {:type :entity
          :primary-key :foo-id
          :joins {:bar [:one :bar]
                  :baz [:one :baz]}}
         :bar
         {:type :entity
          :primary-key :bar-id}}

        db
        (configure {} schema)]

    (-> (transacted db)
        (add :foo {:foo-id 1 :foo "foo" :bar {:bar-id 1 :bar "bar"}})
        (commit!)
        (get :data))))

(defn- set-conj [x y]
  (if (nil? x)
    #{y}
    (conj x y)))

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
  "Normalizes `coll` of items of `entity-type` into `data` (db).
   The vector of idents normalized can be inserted at `target-path` (replacing
   what's present). Alternatively, you can specify a `target-fn` which takes
   `data normalized-idents` as arguments and should return updated `data`.
   See [[add]] for more info."
  ([data entity-type coll]
   (merge-seq data entity-type coll nil))
  ([data entity-type coll target-path-or-fn]
   {:pre [(sequential? coll)]}
   (let [{::keys [schema]}
         (meta data)

         _ (when-not schema
             (throw (ex-info "data missing schema" {:data data})))

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
  "Normalizes the `item` of `entity-type` into `data` at `target-path`.

   * `data` - db with schema (e.g. in transactions `(:db tx-env)`).
   * `entity-type` of `item` being added. Should be present in schema. 
   * `item` - a *map* of data to add. (For collections, see [[merge-seq]].)
   * `target-path` - where to 'attach' the `item`.

   ---
   Examples:
   ```clojure
   ;; inside event handler
   (update tx-env :db db/add ::node data-to-add [:root])

   ;; repl testing
   (def schema
     {::node
      {:type :entity
       :primary-key :id
       :attrs {}
       :joins {:children [:many ::node]}}})

   (-> {}
       (db/configure schema)
       (db/add ::node {:id 0 :children [{:id 1} {:id 2}]} [::root]))
   ```"  
  ([data entity-type item]
   (add data entity-type item nil))
  ([data entity-type item target-path]
   {:pre [(map? item)]}
   (let [{::keys [schema]}
         (meta data)

         _ (when-not schema
             (throw (ex-info "data missing schema" {:data data})))

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
           target-path
           (update-in target-path conj ident))))))

(defn update-entity
  "Updates entity `(make-ident entity-type id)` in `data` (db) with
   `(update-fn entity & args)`."
  [data entity-type id update-fn & args]
  ;; FIXME: validate that both entity-type is defined and id matches type
  (update data (make-ident entity-type id) #(apply update-fn % args)))

(defn all-idents-of
  "Returns the set of all idents of `entity-type`."
  [db entity-type]
  ;; FIXME: check in schema if entity-type is actually declared
  (get db [::all entity-type]))

(defn all-of
  "Returns vals of all idents of `entity-type`."
  [db entity-type]
  (->> (all-idents-of db entity-type)
       (map #(get db %))))

;; keep this as the very last thing since we excluded clojure remove
;; don't want to write code that assumes it uses core remove
(defn remove
  "Removes `thing` from `data` root. `thing` can be either an ident or a map
   like `{:db/ident ident ...}`.
   
   Will *not* remove any remaining references of `thing`.
   (When used on db/transacted, e.g. in event handlers, `thing` will be removed
   from the set of all entities of `thing`'s type.)"
  [data thing]
  (cond
    (ident? thing)
    (dissoc data thing)

    (and (map? thing) (:db/ident thing))
    (dissoc data (:db/ident thing))

    :else
    (throw (ex-info "don't know how to remove thing" {:thing thing}))))

(defn remove-idents
  "Given a coll of `idents`, [[remove]]s all corresponding entities from `data`."  
  [data idents]
  (reduce remove data idents))

(defprotocol IObserved
  (observed-keys [this]))

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

(defn observed [data]
  (ObservedData. (transient #{}) data))

#?(:clj
   (defprotocol ITxCheck
     (check-completed! [this])))

(defprotocol ITxCommit
  (commit! [this]))

#?(:clj
   (deftype TransactedData
     [^clojure.lang.IPersistentMap data
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

       ;; FIXME: should it really check each write if anything changed?
       ;; FIXME: enforce that ident keys have a map value with ::ident key?
       (let [prev-val
             (.valAt data key ::not-found)

             ;; FIXME: this should only be checking the key
             ;; but since using vectors as ident we can't tell the difference from
             ;; [::all :some.app.model/thing]
             is-ident-update?
             (and (ident? key)
                  (contains? (::ident-types (meta data)) (ident-key key)))]

         (if (identical? prev-val value)
           this
           (if (= ::not-found prev-val)
             ;; new
             (if-not is-ident-update?
               ;; new non-ident key
               (TransactedData.
                 (assoc data key value)
                 (conj! keys-new key)
                 keys-updated
                 keys-removed
                 completed-ref)

               ;; new ident
               (TransactedData.
                 (-> data
                     (assoc key value)
                     (update (coll-key key) set-conj key))
                 (conj! keys-new key)
                 (conj! keys-updated (coll-key key))
                 keys-removed
                 completed-ref))

             ;; update, non-ident key
             (if-not is-ident-update?
               (TransactedData.
                 (assoc data key value)
                 (conj! keys-updated key)
                 (conj! keys-updated key)
                 keys-removed
                 completed-ref)

               ;; FIXME: no need to track (ident-key key) since it should be present?
               (TransactedData.
                 (.assoc data key value)
                 keys-new
                 (-> keys-updated
                     (conj! key)
                     ;; need to update the entity-type collection since some queries might change if one in the list changes
                     ;; FIXME: this makes any update potentially expensive, maybe should leave this to the user?
                     (conj! (coll-key key)))
                 keys-removed
                 completed-ref))
             ))))

     (assocEx [this key value]
       (check-completed! this)

       (when (nil? key)
         (throw (ex-info "nil key not allowed" {:value value})))

       ;; FIXME: should it really check each write if anything changed?
       ;; FIXME: enforce that ident keys have a map value with ::ident key?
       (let [prev-val
             (.valAt data key ::not-found)

             ;; FIXME: this should only be checking the key
             ;; but since using vectors as ident we can't tell the difference from
             ;; [::all :some.app.model/thing]
             is-ident-update?
             (and (ident? key)
                  (contains? (::ident-types (meta data)) (ident-key key)))]

         (if (identical? prev-val value)
           this
           (if (= ::not-found prev-val)
             ;; new
             (if-not is-ident-update?
               ;; new non-ident key
               (TransactedData.
                 (.assocEx data key value)
                 (conj! keys-new key)
                 keys-updated
                 keys-removed
                 completed-ref)

               ;; new ident
               (TransactedData.
                 (-> data
                     (.assocEx key value)
                     (update (coll-key key) set-conj key))
                 (conj! keys-new key)
                 (conj! keys-updated (coll-key key))
                 keys-removed
                 completed-ref))

             ;; update, non-ident key
             (if-not is-ident-update?
               (TransactedData.
                 (.assocEx data key value)
                 keys-new
                 (conj! keys-updated key)
                 keys-removed
                 completed-ref)

               ;; FIXME: no need to track (ident-key key) since it should be present?
               (TransactedData.
                 (.assocEx data key value)
                 keys-new
                 (-> keys-updated
                     (conj! key)
                     ;; need to update the entity-type collection since some queries might change if one in the list changes
                     ;; FIXME: this makes any update potentially expensive, maybe should leave this to the user?
                     (conj! (coll-key key)))
                 keys-removed
                 completed-ref))
             ))))

     (without [this key]
       (check-completed! this)

       (let [key-is-ident?
             (ident? key)

             next-data
             (-> (.without data key)
                 (cond->
                   key-is-ident?
                   (update (coll-key key) disj key)))

             next-removed
             (-> keys-removed
                 (conj! key)
                 (cond->
                   key-is-ident?
                   (conj! (coll-key key))))]

         (TransactedData.
           next-data
           keys-new
           keys-updated
           next-removed
           completed-ref)))

     ITxCheck
     (check-completed! [this]
       (when @completed-ref
         (throw (ex-info "transaction concluded, don't hold on to db while in tx" {}))))

     ITxCommit
     (commit! [_]
       (vreset! completed-ref true)
       {:data data
        :keys-new (persistent! keys-new)
        :keys-updated (persistent! keys-updated)
        :keys-removed (persistent! keys-removed)}))

   :cljs
   (deftype TransactedData
     [^not-native data
      keys-new
      keys-updated
      keys-removed
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
             (ident? key)

             next-data
             (-> (-dissoc data key)
                 (cond->
                   key-is-ident?
                   (update (coll-key key) disj key)))

             next-removed
             (-> keys-removed
                 (conj! key)
                 (cond->
                   key-is-ident?
                   (conj! (coll-key key))))]

         (TransactedData.
           next-data
           keys-new
           keys-updated
           next-removed
           completed-ref)))

     IAssociative
     (-contains-key? [coll k]
       (-contains-key? data k))

     (-assoc [this key value]
       (.check-completed! this)

       (when (nil? key)
         (throw (ex-info "nil key not allowed" {:value value})))

       ;; FIXME: should it really check each write if anything changed?
       ;; FIXME: enforce that ident keys have a map value with ::ident key?
       (let [prev-val
             (-lookup data key ::not-found)

             ;; FIXME: this should only be checking the key
             ;; but since using vectors as ident we can't tell the difference from
             ;; [::all :some.app.model/thing]
             is-ident-update?
             (and (ident? key)
                  (contains? (::ident-types (meta data)) (ident-key key)))]

         (if (identical? prev-val value)
           this
           (if (= ::not-found prev-val)
             ;; new
             (if-not is-ident-update?
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
                     (update (coll-key key) set-conj key))
                 (conj! keys-new key)
                 (conj! keys-updated (coll-key key))
                 keys-removed
                 completed-ref))

             ;; update, non-ident key
             (if-not is-ident-update?
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
                     (conj! (coll-key key)))
                 keys-removed
                 completed-ref))
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
       {:data data
        :keys-new (persistent! keys-new)
        :keys-updated (persistent! keys-updated)
        :keys-removed (persistent! keys-removed)})

     Object
     (check-completed! [this]
       (when @completed-ref
         (throw (ex-info "transaction concluded, don't hold on to db while in tx" {}))))))

(defn transacted [data]
  (TransactedData.
    data
    (transient #{})
    (transient #{})
    (transient #{})
    (volatile! false)))