(ns shadow.experiments.grove.db
  (:refer-clojure :exclude #{ident? remove})
  (:require
    [shadow.experiments.grove.protocols :as p]))

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

(defn configure [spec]
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

(defn make-ident [type id]
  (p/Ident. type id nil))

(defn ident? [thing]
  (instance? p/Ident thing))

(defn ident-key [^p/Ident thing]
  {:pre [(ident? thing)]}
  (.-entity-type thing))

(defn coll-key [^p/Ident thing]
  {:pre [(ident? thing)]}
  [::all (.-entity-type thing)])

(defn ident-val [^p/Ident thing]
  {:pre [(ident? thing)]}
  (.-id thing))

(defn- normalize* [imports schema entity-type item]
  (let [{:keys [id-attr id-pred joins] :as ent-config}
        (get-in schema [:entities entity-type])

        item-ident
        (get item ::ident)

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
          (assoc item ::ident ident))

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

(defn- merge-imports [data imports]
  (reduce
    (fn [data [ident item]]
      (-> data
          ;; build a :foo #{ident ident ...} set because of the flat structure
          (update (coll-key ident) set-conj ident)
          (update ident merge item)))
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
                       (assert id "can't import without an id")
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

(declare
  ^{:arglists '([env db query-data] [env db current query-data])}
  query)

(defmulti query-calc
  (fn [env db current query-part params] query-part)
  :default ::default)

(defmethod query-calc ::default [_ _ _ _ _]
  nil)

(defn- process-query-part
  [env db current result query-part]
  (cond
    ;; simple attr
    (keyword? query-part)
    (let [x (get current query-part ::missing)]
      (if (not= ::missing x)
        (if (contains? result query-part)
          result
          (assoc! result query-part x))
        (assoc! result query-part (query-calc env db current query-part {}))))

    ;; (::foo {:params 1})
    ;; TBD

    ;; join
    ;; {ident [attrs]}
    ;; {::foo [attrs]}
    ;; {(::foo {:params 1} [attrs])
    (map? query-part)
    (do (when-not (= 1 (count query-part))
          (throw (ex-info "join map with more than one entry" {:query-part query-part})))

        (let [[join-key join-attrs] (first query-part)]
          (when-not (vector? join-attrs)
            (throw (ex-info "join value must be a vector" {:query-part query-part})))

          (cond
            (keyword? join-key)
            (let [join-val (get current join-key ::missing)
                  join-val
                  (if (not= ::missing join-val)
                    join-val
                    ;; might be computed, should check in db schema
                    (query-calc env db current join-key {}))]

              (cond
                ;; {:some-prop [:some-other-ident 123]}
                (ident? join-val)
                (assoc! result join-key (query env db (get db join-val) join-attrs))

                ;; {:some-prop [[:some-other-ident 123] [:some-other-ident 456]]}
                (coll? join-val)
                (assoc! result join-key
                  (mapv
                    (fn [join-item]
                      (cond
                        (ident? join-item)
                        (query env db (get db join-item) join-attrs)

                        (map? join-item)
                        (query env db join-item join-attrs)

                        :else
                        (throw (ex-info "join-value contained unknown thing"
                                 {:join-key join-key
                                  :join-val join-val
                                  :join-item join-item
                                  :current current}))))
                    join-val))

                ;; non-normalized nested-map
                (map? join-val)
                (assoc! result join-key (query env db join-val join-attrs))

                :else
                (throw (ex-info "don't know how to join" {:query-part query-part :join-val join-val :join-key join-key}))))

            ;; from root
            (ident? join-key)
            (if-some [join-val (get db join-key)]
              (assoc! result join-key (query env db join-val join-attrs))
              result)

            :else
            (throw (ex-info "failed to join" {:query-part query-part})))))

    ;; tx
    (list? query-part)
    result

    :else
    (throw (ex-info "invalid query part" {:part query-part}))))

(defn query
  ([env db query-data]
   (query env db db query-data))
  ([env db current query-data]
   {:pre [(some? env)
          (map? db)
          (map? current)
          (vector? query-data)]}
   (let [len (count query-data)]
     (loop [current current
            ;; query from the root should not return the entire db
            ;; query on an entity is fine to return the entire entity
            result (transient (if (identical? db current) {} current))
            i 0]
       (if (>= i len)
         (persistent! result)
         (let [query-part (nth query-data i)
               result (process-query-part env db current result query-part)]
           (recur current result (inc i))))))))

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

    (and (map? thing) (::ident thing))
    (dissoc data (::ident thing))

    :else
    (throw (ex-info "don't know how to remove thing" {:thing thing}))))