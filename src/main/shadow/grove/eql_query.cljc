(ns shadow.grove.eql-query
  "minimal EQL query engine")

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

(defn eql-ident? [thing]
  (and (vector? thing)
       (= 2 (count thing))
       (keyword (nth thing 0))))

(defn lazy-seq? [thing]
  (and (instance? #?(:clj clojure.lang.LazySeq :cljs cljs.core/LazySeq) thing)
       (not (realized? thing))))

(declare
  ^{:arglists '([env db query-data] [env db current query-data])}
  query)

;; FIXME: shouldn't use a multi-method since DCE doesn't like it
;; but is the easiest to use with hot-reload in mind

(defmulti attr
  (fn [env db current query-part params] query-part)
  :default ::default)

(defmethod attr ::default [env db current query-part params]
  (get current query-part :db/undefined))

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
      ;; FIXME: alias support
      (do #?(:cljs
             (when ^boolean js/goog.DEBUG
               (when (lazy-seq? calced)
                 (throw (ex-info (str "the lookup of attribute " kw " returned a lazy sequence. Attributes must not return lazy sequences. Realize the result before returning (eg. doall).")
                          {:kw kw
                           :result calced})))))
          (assoc! result kw calced)))))

;; FIXME: this tracking of :db/loading is really annoying, should probably just throw instead?
(defn- process-query-part
  [env db current result query-part]
  (cond
    (keyword-identical? query-part :db/all)
    (transient current)

    ;; simple attr
    (keyword? query-part)
    (process-lookup env db current result query-part {})

    ;; (::foo {:params 1})
    (list? query-part)
    (let [[kw params] query-part]
      (process-lookup env db current result kw params))

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
                    ;; process-lookup but without associng the result since we need to process it
                    (attr env db current join-key {}))]

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

                ;; {:some-prop [:some-other-ident 123]}
                ;; FIXME: buggy if val is [:foo :bar] (just a vector of two keywords, no ident)
                ;; but then the user shouldn't be trying to join so should be fine
                (eql-ident? join-val)
                (let [val (get db join-val ::missing)]
                  (cond
                    (keyword-identical? ::missing val)
                    (assoc! result join-key ::not-found)

                    (keyword-identical? :db/loading val)
                    val

                    ;; FIXME: check more possible vals?
                    :else
                    (let [query-val (query env db val join-attrs)]
                      (cond
                        (keyword-identical? :db/loading query-val)
                        query-val

                        :else
                        (assoc! result join-key query-val)))))

                ;; nested-map, may want to join nested
                (map? join-val)
                (let [query-val (query env db join-val join-attrs)]
                  (cond
                    (keyword-identical? query-val :db/loading)
                    query-val
                    :else
                    (assoc! result join-key query-val)))

                ;; {:some-prop [[:some-other-ident 123] [:some-other-ident 456]]}
                ;; {:some-prop [{:foo 1} {:foo 2}]}
                ;; FIXME: should it preserve sets?
                (coll? join-val)
                (assoc! result join-key
                  (mapv
                    (fn [join-item]
                      (cond
                        (eql-ident? join-item)
                        (let [joined (get db join-item)]
                          (if (map? joined)
                            (query env db joined join-attrs)
                            (throw (ex-info "coll item join missing" {:join-key join-key
                                                                      :join-val join-val
                                                                      :join-item join-item}))))

                        (map? join-item)
                        (query env db join-item join-attrs)

                        :else
                        (throw (ex-info "join-value contained unknown thing"
                                 {:join-key join-key
                                  :join-val join-val
                                  :join-item join-item
                                  :current current}))))
                    join-val))

                :else
                (throw (ex-info "don't know how to join" {:query-part query-part :join-val join-val :join-key join-key}))))

            ;; from root
            (eql-ident? join-key)
            (let [join-val (get db join-key)]
              (cond
                (keyword-identical? :db/loading join-val)
                join-val

                (nil? join-val)
                result

                :else
                (let [query-val (query env db join-val join-attrs)]
                  (cond
                    (keyword-identical? :db/loading query-val)
                    query-val
                    :else
                    (assoc! result join-key query-val)))))

            :else
            (throw (ex-info "failed to join" {:query-part query-part
                                              :current current
                                              :result result})))))


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
            result (transient {})
            i 0]
       (if (>= i len)
         (persistent! result)
         (let [query-part (nth query-data i)
               result (process-query-part env db current result query-part)]
           (if (keyword-identical? result :db/loading)
             result
             (recur current result (inc i)))))))))


(comment
  (query {}
    {:hello {:world 1 :foo true}}
    [{:hello [:world]}]))