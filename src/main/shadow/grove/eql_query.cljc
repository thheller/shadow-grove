(ns shadow.grove.eql-query
  "minimal EQL query engine"
  (:require [shadow.grove.db :as db]))

;; ideally would like to use pathom but currently that carries too much overhead
;; and is over 10x slower for small simple queries. assuming that a page
;; has 50 small queries the difference of 0.4ms vs 8.5ms per query is huge
;; also has quite excessive code size (partly because of spec)

;; pathom of course has a bajillion more features, just need to find a way
;; to make it more performant for the grove use-cases of many small queries
;; vs one large query that is composed from the root like fulcro does
;; I might have just configured or used it wrong

;; as long as everything is EQL it should be fine and easy to switch later

;; unions are currently not supported. not sure where you'd ever need them.
;; seems like it's only useful if you are forced to build one big query
;; like in om/fulcro systems which is the first thing I dropped since it felt
;; way too restrictive to use for me and that concept never "clicked".

;; IMHO in the presence of suspense it no longer makes sense to build large queries
;; since you may want to render parts early when you can

#?(:clj
   (defn keyword-identical? [x y]
     (identical? x y)))

(defn lazy-seq? [thing]
  (and (instance? #?(:clj clojure.lang.LazySeq :cljs cljs.core/LazySeq) thing)
       (not (realized? thing))))

(declare
  ^{:arglists '([env db query-data] [env db current query-data])}
  query)

;; assoc'd into the query env as ::trace, so that later parts
;; and attr queries know where they are
(defrecord Trace [root part idx query parent])

(defn throw-traced [env msg data]
  (throw (ex-info msg (assoc data :type ::query-error :trace (::trace env)))))

(defn throw-invalid-ident-lookup! [env ident val]
  (throw-traced env
    "joined ident lookup was not a map, don't know how to continue query join"
    {:val val
     :ident ident}))

;; FIXME: shouldn't use a multi-method since DCE doesn't like it
;; but is the easiest to use with hot-reload in mind

(defmulti attr
  "Define 'computed attributes'. The attribute is the dispatch-fn of the method.
   The return of this function will be included in query results for that
   attribute. *Must not return lazy seq.*
   The methods are called with the following args:
   1. `env` - component env.
   2. `db` - the db map.
   3. `current` - the entity which is the root of the query. For `query-ident`
      this is the entity corresponding to the ident, for `query-root` it is the
      whole db map.
   4. `query-part` – the dispatch-fn of the method, i.e. an EQL attribute.
   5. `params` - optional parameters specified with EQL attributes.
   Called when building query results for each EQL attribute. See [[::default]].
   Idents accessed within the method will be 'observed': the query will re-run
   if said idents are modified.

   ---
   Example:

   ```clojure
   ;; in a component
   (bind query-result
     (sg/query-ident ident [::foo '(::bar {:test 1})]))
   ;; elsewhere
   (defmethod attr ::foo
     [env db current query-part params]
     ;; the ::default impl
     (get current query-part :db/undefined))
   ;; {:test 1} available in `params` for
   (defmethod attr ::bar
     [_ _ _ _ params])

   ;; another example
   (defmethod eql/attr ::m/num-active
     [env db current _ params]
     (->> (db/all-of db ::m/todo)
         (remove ::m/completed?)
         (count)))
   ```"
  (fn [env db current query-part params] query-part)
  :default ::default)

(defmethod attr ::default [env db current query-part params]
  (get current query-part :db/undefined))

;; for debugging/tests
(defmethod attr :db/trace [env db current query-part params]
  (::trace env))

(defmethod attr :db/env [env db current query-part params]
  env)

;; kw query with optional params
;; ::foo
;; (::foo {:bar 1})
(defn- process-lookup [env db current result kw params]
  (let [calced (attr env db current kw params)]
    (cond
      (keyword-identical? :db/loading calced)
      calced

      ;; don't add to result
      (keyword-identical? :db/undefined calced)
      result

      :else
      (do #?(:cljs
             (when ^boolean js/goog.DEBUG
               (when (lazy-seq? calced)
                 (throw-traced env
                   (str "the lookup of attribute " kw " returned a lazy sequence. Attributes must not return lazy sequences. Realize the result before returning (eg. doall).")
                   {:kw kw
                    :result calced}))))
          (assoc! result kw calced)))))

(defn- db-ident-lookup [env db ident]
  (get db ident ::missing))

;; process join of keyword
;;
;; {:foo
;;  [:bar]}
;;
;; {(:foo {:args 1})
;;  [:bar]}
;;
;; join-key :foo
;; params {} or {:args 1}
;; join-attrs [:bar]
(defn- process-query-kw-join [env db current result join-key params join-attrs]
  (let [join-val (attr env db current join-key params)]
    (cond
      (keyword-identical? join-val :db/loading)
      join-val

      (keyword-identical? join-val :db/undefined)
      result

      ;; FIXME: should this return nil or no key at all
      ;; [{:foo [:bar]}] against {:foo nil}
      ;; either {} or {:foo nil}?
      (nil? join-val)
      result

      ;; {:some-prop #gdb/ident [:some-other-ident 123]}
      (db/ident? join-val)
      (let [val (db-ident-lookup env db join-val)]
        (cond
          (keyword-identical? ::missing val)
          (assoc! result join-key {:db/ident join-val
                                   :db/not-found true})

          (keyword-identical? :db/loading val)
          val

          ;; continue query using ident value as new current
          (map? val)
          (let [query-val (query env db val join-attrs)]
            (cond
              (keyword-identical? :db/loading query-val)
              query-val

              :else
              (assoc! result join-key query-val)))

          :else
          (throw-invalid-ident-lookup! env join-val val)
          ))

      ;; nested-map, run new query from that root
      (map? join-val)
      (let [query-val (query env db join-val join-attrs)]
        (cond
          (keyword-identical? query-val :db/loading)
          query-val
          :else
          (assoc! result join-key query-val)))

      ;; {:some-prop [[:some-other-ident 123] [:some-other-ident 456]]}
      ;; {:some-prop [{:foo 1} {:foo 2}]}
      (coll? join-val)
      (let [joined-coll
            (reduce
              (fn [acc join-item]
                (cond
                  (db/ident? join-item)
                  (let [val (db-ident-lookup env db join-item)]
                    (cond
                      (keyword-identical? ::missing val)
                      (conj! acc {:db/ident join-item
                                  :db/not-found true})

                      (keyword-identical? :db/loading val)
                      val

                      ;; continue query using ident value as new current
                      (map? val)
                      (let [query-val (query env db val join-attrs)]
                        (cond
                          (keyword-identical? :db/loading query-val)
                          query-val

                          :else
                          (conj! acc query-val)))

                      :else
                      (throw-invalid-ident-lookup! env join-item val)))

                  (map? join-item)
                  (query env db join-item join-attrs)

                  :else
                  (throw-traced env
                    "join-value contained unknown thing we cannot continue query from"
                    {:join-key join-key
                     :join-val join-val
                     :join-item join-item
                     :current current})))

              (transient [])
              join-val)]

        (if (keyword-identical? joined-coll :db/loading)
          joined-coll
          (assoc! result join-key (persistent! joined-coll))))

      :else
      (throw-traced env
        "don't know how to join"
        {:join-val join-val
         :join-key join-key}))))

(defn- process-query-part
  [env db current result query-part]
  (cond
    (keyword-identical? query-part :db/all)
    (if (zero? (count result))
      ;; shortcut to use current as result when starting from empty, saves merging everything
      (transient current)
      ;; need to merge in case :db/all was not used as the first query attr
      (reduce-kv assoc! result current))

    ;; simple attr
    (keyword? query-part)
    (process-lookup env db current result query-part {})

    ;; (::foo {:params 1})
    ;; list? doesn't work for `(:foo {:bar ~bar})
    (seq? query-part)
    (let [[kw params] query-part]
      (if (and (keyword? kw) (map? params))
        (process-lookup env db current result kw params)
        (throw-traced env "invalid query list expression" {:part query-part})))

    ;; join
    ;; {ident [attrs]}
    ;; {::foo [attrs]}
    ;; {(::foo {:params 1} [attrs])
    (map? query-part)
    (do (when-not (= 1 (count query-part))
          (throw-traced env "join map with more than one entry" {:query-part query-part}))

        (let [[join-key join-attrs] (first query-part)]
          (when-not (vector? join-attrs)
            (throw-traced env "join value must be a vector" {:query-part query-part}))

          (cond
            (keyword? join-key)
            (process-query-kw-join env db current result join-key {} join-attrs)

            ;; can join idents from anywhere, most likely from root though
            (db/ident? join-key)
            (let [join-val (db-ident-lookup env db join-key)]
              (cond
                (keyword-identical? :db/loading join-val)
                join-val

                ;; FIXME: maybe just have db-ident-lookup return the not found map?
                ;; but then the query will continue and the values we put there since
                ;; the query likely won't contain the fields
                (keyword-identical? ::missing join-val)
                (assoc! result join-key {:db/ident join-key :db/not-found true})

                ;; do we want the query result to be {:foo nil} or just {}
                ;; when {ident nil} is in the db?
                (nil? join-val)
                (assoc! result join-key nil)

                (map? join-val)
                (let [query-val (query env db join-val join-attrs)]
                  (cond
                    (keyword-identical? :db/loading query-val)
                    query-val
                    :else
                    (assoc! result join-key query-val)))

                :else
                (throw-traced env "joined value was a unsupported value, cannot continue query"
                  {:ident join-key
                   :value join-val})))

            (seq? join-key)
            (let [[join-kw join-params] join-key]
              (process-query-kw-join env db current result join-kw join-params join-attrs))

            :else
            (throw-traced env
              "failed to join"
              {:query-part query-part
               :current current
               :result result}))))

    :else
    (throw-traced env "invalid query-part" {:part query-part})
    ))

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
            result (transient {})
            i 0]
       (if (>= i len)
         (persistent! result)
         (let [query-part (nth query-data i)
               ;; trying to add the least overhead way of tracking where we are in a query
               ;; using a record here so that allocation is cheaper
               ;; this code is potentially called a lot, I want this to be efficient
               ;; can't waste time when in a UI rendering context
               trace (->Trace (:db/ident current) query-part i query-data (::trace env))
               env (assoc env ::trace trace)
               result (process-query-part env db current result query-part)]

           ;; FIXME: this tracking of :db/loading is really annoying, should probably just throw instead?
           ;; the reason for doing this is short-cutting the query, so that it send as soon as it
           ;; cannot be fulfilled completely. could alternatively add an atom or so to env
           ;; and flip that to stop, or not actually stop but leave a marker in the data
           ;; currently this is only used by query hooks for suspense support
           (if (keyword-identical? result :db/loading)
             result
             (recur current result (inc i)))))))))


(comment
  (query {}
    {:hello {:world 1 :foo true}}
    [{:hello [:world]}]))