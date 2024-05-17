(ns shadow.grove.eql-query
  "minimal EQL query engine"
  (:require [shadow.grove.kv :as kv]))

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
  ^{:arglists '([env table query-data] [env table current query-data])}
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
  (fn [env db current query-part params] query-part)
  :default ::default)

(defmethod attr ::default [env db current query-part params]
  (get current query-part kv/NOT-FOUND))

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
    (if (identical? kv/NOT-FOUND calced)
      calced
      (do #?(:cljs
             (when ^boolean js/goog.DEBUG
               (when (lazy-seq? calced)
                 (throw-traced env
                   (str "the lookup of attribute " kw " returned a lazy sequence. Attributes must not return lazy sequences. Realize the result before returning (eg. doall).")
                   {:kw kw
                    :result calced}))))
          (assoc! result kw calced)))))

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
      (nil? join-val)
      result

      ;; nested-map, run new query from that root
      (map? join-val)
      (let [query-val (query env db join-val join-attrs)]
        (assoc! result join-key query-val))

      ;; {:some-prop [[:some-other-ident 123] [:some-other-ident 456]]}
      ;; {:some-prop [{:foo 1} {:foo 2}]}
      (coll? join-val)
      (let [joined-coll
            (reduce
              (fn [acc join-item]
                (cond
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

        (assoc! result join-key (persistent! joined-coll)))

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
  ([env table query-data]
   (query env table table query-data))
  ([env table current query-data]
   {:pre [(some? env)
          (map? table)
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
               trace (->Trace current query-part i query-data (::trace env))
               env (assoc env ::trace trace)
               result (process-query-part env table current result query-part)]

           (recur current result (inc i))))))))


(comment
  (query {}
    {:hello {:world 1 :foo true}}
    [{:hello [:world]}]))