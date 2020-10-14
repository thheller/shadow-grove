(ns shadow.experiments.grove.db
  (:refer-clojure :exclude #{ident? remove}))

(set! *warn-on-infer* false)

(defn parse-entity-spec [entity-type {:keys [attrs] :as config}]
  {:pre [(keyword? entity-type)
         (map? attrs)]}

  (let [[id-attr id-pred]
        (reduce-kv
          (fn [_ key val]
            (when (and (vector? val) (= :primary-key (first val)))
              (reduced [key (second val)])))
          nil
          attrs)

        joins
        (reduce-kv
          (fn [joins key val]
            (if-not (and (vector? val)
                         (or (= :one (first val))
                             (= :many (first val))))
              joins
              (assoc joins key (second val))))
          {}
          attrs)]

    (when-not id-attr
      (throw (ex-info "must define primary-key" {:entity-type entity-type
                                                 :attr-config attrs})))

    {:entity-type id-attr
     :id-attr id-attr
     :id-pred id-pred
     :attrs attrs
     :joins joins}))

(defn parse-schema [spec]
  (reduce-kv
    (fn [schema key {:keys [type] :as config}]
      (cond
        (= :entity type)
        (assoc-in schema [:entities key] (parse-entity-spec key config))
        :else
        (throw (ex-info "unknown type" {:key key :config config}))
        ))
    {:entities {}}
    spec))

(defn configure [init-db spec]
  ;; FIXME: should this use a special key instead of meta?
  (let [schema (parse-schema spec)
        m {::schema schema
           ::ident-types (set (keys (:entities schema)))}]
    (with-meta init-db m)))

(defn make-ident [type id]
  [type id])

(defn ident? [thing]
  (and (vector? thing)
       (= (count thing) 2)
       (keyword? (first thing))))

(defn ident-key [thing]
  {:pre [(ident? thing)]}
  (nth thing 0))

(defn coll-key [thing]
  {:pre [(ident? thing)]}
  [::all (ident-key thing)])

(defn ident-val [thing]
  {:pre [(ident? thing)]}
  (nth thing 1))

(defn- normalize* [imports schema entity-type item]
  (let [{:keys [id-attr id-pred joins] :as ent-config}
        (get-in schema [:entities entity-type])

        item-ident
        (get item :db/ident)

        id-val
        (get item id-attr)

        _ (when-not id-val
            (throw (ex-info "entity was supposed to have id-attr but didn't"
                     {:item item
                      :entity-type entity-type
                      :id-attr id-attr})))

        ident
        (make-ident entity-type id-val)
        ;; [entity-type id-val]

        _ (when (and item-ident (not= item-ident ident))
            (throw (ex-info "item contained ident but we generated a different one" {:item item :ident ident})))

        ;; FIXME: can an item ever have more than one ident?
        item
        (if (= item-ident ident)
          item
          (assoc item :db/ident ident))

        item
        (reduce-kv
          (fn [item key curr-val]
            (let [join-type (get joins key)]
              (if-not join-type
                item
                (let [norm-val
                      (cond
                        (= ::skip curr-val)
                        curr-val

                        ;; already normalized, no nothing
                        (ident? curr-val)
                        ::skip

                        (map? curr-val)
                        (normalize* imports schema join-type curr-val)

                        (vector? curr-val)
                        (mapv #(normalize* imports schema join-type %) curr-val)

                        ;; FIXME: assume all other vals are id-val?
                        (id-pred curr-val)
                        [join-type curr-val]

                        :else
                        (throw (ex-info "unexpected value in join attr"
                                 {:item item
                                  :key key
                                  :val curr-val
                                  :type type})))]

                  (if (= norm-val ::skip)
                    item
                    (assoc item key norm-val))))))
          item
          item)]

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
      (-> data
          ;; build a :foo #{ident ident ...} set because of the flat structure
          (update (coll-key ident) set-conj ident)
          (update ident merge-or-replace item)))
    data
    imports))

(defn merge-seq
  ([data entity-type coll]
   (merge-seq data entity-type coll nil))
  ([data entity-type coll target-path]
   {:pre [(sequential? coll)]}
   (let [{::keys [schema]}
         (meta data)

         _ (when-not schema
             (throw (ex-info "data missing schema" {:data data})))

         {:keys [id-attr] :as entity-spec}
         (get-in schema [:entities entity-type])

         _ (when-not entity-spec
             (throw (ex-info "entity not defined" {:entity-type entity-type})))

         idents
         (->> coll
              (map (fn [item]
                     (let [id (get item id-attr)]
                       (when-not id
                         (throw (ex-info "can't import item without an id" {:item item :id-attr id-attr})))
                       (make-ident entity-type id))))
              (into []))

         imports
         (normalize schema entity-type coll)]

     (-> data
         (merge-imports imports)
         (cond->
           target-path
           (assoc-in target-path idents))))))

(defn add
  ([data entity-type item]
   (add data entity-type item nil))
  ([data entity-type item target-path]
   {:pre [(map? item)]}
   (let [{::keys [schema]}
         (meta data)

         _ (when-not schema
             (throw (ex-info "data missing schema" {:data data})))

         {:keys [id-attr] :as entity-spec}
         (get-in schema [:entities entity-type])

         _ (when-not entity-spec
             (throw (ex-info "entity not defined" {:entity-type entity-type})))

         ident
         (let [id (get item id-attr)]
           (assert id)
           (make-ident entity-type id))

         imports
         (normalize schema entity-type [item])]

     (-> data
         (merge-imports imports)
         (cond->
           target-path
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

(defprotocol IObserved
  (observed-keys [this]))

(deftype ObservedData [^:mutable keys-used data]
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

  Object
  (check-completed! [this]
    (when @completed-ref
      (throw (ex-info "transaction concluded, don't hold on to db while in tx" {}))))

  (commit! [_]
    (vreset! completed-ref true)
    {:data data
     :keys-new (persistent! keys-new)
     :keys-updated (persistent! keys-updated)
     :keys-removed (persistent! keys-removed)}))

(defn transacted [data]
  (TransactedData.
    data
    (transient #{})
    (transient #{})
    (transient #{})
    (volatile! false)))