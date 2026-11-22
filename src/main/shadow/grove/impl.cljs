(ns shadow.grove.impl
  (:require
    [shadow.grove :as-alias sg]
    [shadow.grove.kv :as kv]
    [shadow.grove.protocols :as gp]
    [shadow.grove.runtime :as rt]
    [shadow.grove.components :as comp]
    ))

;; {[query-fn args] <Query>}
(defonce active-queries-ref (atom {}))

(set! *warn-on-infer* false)

;; mutable map with cljs key equality, for the read index below.
;; numbers and strings are their own js/Map key. keywords are not identical per instance,
;; so they are stored by fqn in a second js/Map, kw-keys has the keyword for the fqn.
;; none of these need a hash. everything else (vectors from composite primary keys,
;; uuids, nil, ...) is rare and goes into a persistent map.
(deftype KeyMap [^js prims ^js kws ^js kw-keys ^:mutable ^not-native others])

(defn- key-map []
  (KeyMap. (js/Map.) (js/Map.) (js/Map.) {}))

(defn- km-get [^KeyMap m key]
  (cond
    (or (number? key) (string? key))
    (.get (.-prims m) key)
    (keyword? key)
    (.get (.-kws m) (.-fqn ^Keyword key))
    :else
    (-lookup (.-others m) key)))

(defn- km-set! [^KeyMap m key val]
  (cond
    (or (number? key) (string? key))
    (.set (.-prims m) key val)
    (keyword? key)
    (let [fqn (.-fqn ^Keyword key)]
      (when-not (.has (.-kws m) fqn)
        (.set (.-kw-keys m) fqn key))
      (.set (.-kws m) fqn val))
    :else
    (set! (.-others m) (-assoc (.-others m) key val)))
  js/undefined)

(defn- km-delete! [^KeyMap m key]
  (cond
    (or (number? key) (string? key))
    (.delete (.-prims m) key)
    (keyword? key)
    (let [fqn (.-fqn ^Keyword key)]
      (.delete (.-kws m) fqn)
      (.delete (.-kw-keys m) fqn))
    :else
    (set! (.-others m) (-dissoc (.-others m) key)))
  js/undefined)

(defn- km-count [^KeyMap m]
  (+ (.-size (.-prims m))
     (.-size (.-kws m))
     (-count (.-others m))))

(defn- km-each
  "(f key val) for every entry"
  [^KeyMap m f]
  (.forEach (.-prims m) (fn [val key] (f key val)))
  (let [kw-keys (.-kw-keys m)]
    (.forEach (.-kws m) (fn [val fqn] (f (.get kw-keys fqn) val))))
  (-kv-reduce (.-others m) (fn [_ key val] (f key val) nil) nil)
  js/undefined)

(defn- km-reduce
  "(f acc key val) for every entry"
  [^KeyMap m f init]
  (let [acc (volatile! init)]
    (km-each m (fn [key val] (vreset! acc (f @acc key val))))
    @acc))

;; the read index: which kv keys the mounted UI reads and who reads them.
;; a reader (sub) is the slot ref of a sg/kv-lookup or a Query, both are told about
;; changes via gp/invalidate!. most keys only have one reader, so that is stored as is.
;; a js/Set is only created for the second one.

(defn- notify-sub [sub]
  (gp/invalidate! sub))

(defn- subs-add [subs sub]
  (cond
    (nil? subs) sub
    (instance? js/Set subs) (do (.add subs sub) subs)
    (identical? subs sub) subs
    :else (doto (js/Set.) (.add subs) (.add sub))))

;; returns nil when no reader is left
(defn- subs-del [subs sub]
  (cond
    (nil? subs) nil
    (identical? subs sub) nil
    (instance? js/Set subs) (do (.delete subs sub) (if (zero? (.-size subs)) nil subs))
    :else subs))

(defn- subs-notify [subs]
  (cond
    (nil? subs) nil
    (instance? js/Set subs) (.forEach subs notify-sub)
    :else (gp/invalidate! subs))
  js/undefined)

;; all: readers of the table as a whole, (sg/kv-lookup table) or a query that used seq on it.
;; tracking every entry a seq touched is expensive, so those are told about every change
;; keys: {key subs}, only has keys that currently have a reader
(deftype TableIndex [kv-table ^:mutable all ^KeyMap keys])

;; {kv-table TableIndex}
(defonce ^KeyMap read-index (key-map))

(defn- get-table-index ^TableIndex [kv-table]
  (let [ti (km-get read-index kv-table)]
    (if-not (nil? ti)
      ti
      (let [ti (TableIndex. kv-table nil (key-map))]
        (km-set! read-index kv-table ti)
        ti))))

(defn- keys-add! [^KeyMap keys key sub]
  (let [subs (km-get keys key)
        next (subs-add subs sub)]
    (when-not (identical? subs next)
      (km-set! keys key next))))

(defn- keys-del! [^KeyMap keys key sub]
  (let [subs (km-get keys key)]
    (when-not (nil? subs)
      ;; a set is edited in place, the entry only goes away with the last reader
      (when (nil? (subs-del subs sub))
        (km-delete! keys key)))))

(defn index-key-add! [kv-table key sub]
  (keys-add! (.-keys (get-table-index kv-table)) key sub))

(defn index-key-del! [kv-table key sub]
  (when-some [^TableIndex ti (km-get read-index kv-table)]
    (keys-del! (.-keys ti) key sub)))

(defn index-table-add! [kv-table sub]
  (let [ti (get-table-index kv-table)]
    (set! (.-all ti) (subs-add (.-all ti) sub))))

(defn index-table-del! [kv-table sub]
  (when-some [^TableIndex ti (km-get read-index kv-table)]
    (set! (.-all ti) (subs-del (.-all ti) sub))))

(defn reduce-read-keys
  "calls (f acc kv-table key) for every key the mounted UI currently reads,
   through sg/kv-lookup or in a sg/query. see read-tables for reads of whole tables"
  [f init]
  (km-reduce read-index
    (fn [acc kv-table ^TableIndex ti]
      (km-reduce (.-keys ti)
        (fn [acc key _]
          (f acc kv-table key))
        acc))
    init))

(defn read-keys
  "vector of [kv-table key] tuples the mounted UI currently reads"
  []
  (persistent!
    (reduce-read-keys
      (fn [acc kv-table key]
        (conj! acc [kv-table key]))
      (transient []))))

(defn read-tables
  "vector of the kv-tables the mounted UI currently reads as a whole,
   so it depends on every key of them"
  []
  (persistent!
    (km-reduce read-index
      (fn [acc kv-table ^TableIndex ti]
        (if (nil? (.-all ti))
          acc
          (conj! acc kv-table)))
      (transient []))))

;; bumped per invalidate-kv!, a query reading many of the changed keys only has to tell its subs once
(defonce invalidation-id 0)

(defn invalidate-kv! [^not-native tx-info]
  (set! invalidation-id (inc invalidation-id))

  (-kv-reduce
    tx-info
    (fn [_ kv-table ^not-native table-info]
      ;; no index, no one ever read this table
      (when-some [^TableIndex ti (km-get read-index kv-table)]
        (let [keys-new (:keys-new table-info)
              keys-updated (:keys-updated table-info)
              keys-removed (:keys-removed table-info)]

          (when (or (pos? (count keys-new))
                    (pos? (count keys-updated))
                    (pos? (count keys-removed)))

            (subs-notify (.-all ti))

            ;; a table that is only written to, or only read as a whole, has no keys to check
            (let [keys (.-keys ti)
                  read-count (km-count keys)
                  n-new (count keys-new)
                  n-updated (count keys-updated)
                  n-removed (count keys-removed)]
              (cond
                (zero? read-count)
                nil

                ;; a tx changing more keys than are read: walk the read keys instead.
                ;; testing if a key changed is a lot cheaper than iterating a persistent set,
                ;; so this is still ahead when both are about the same size
                (<= read-count (+ n-new n-updated n-removed))
                (km-each keys
                  (fn [key subs]
                    (when (or (and (pos? n-updated) (contains? keys-updated key))
                              (and (pos? n-removed) (contains? keys-removed key))
                              (and (pos? n-new) (contains? keys-new key)))
                      (subs-notify subs))))

                :else
                (let [notify (fn [_ key] (subs-notify (km-get keys key)) nil)]
                  (reduce notify nil keys-new)
                  (reduce notify nil keys-updated)
                  (reduce notify nil keys-removed)))))))
      nil)
    nil)

  js/undefined)

(defn merge-result [tx-env ev result]
  (cond
    (nil? result)
    tx-env

    (not (map? result))
    (throw
      (js/Error. (str "tx handler returned invalid result for event " (:e ev))))

    (identical? (::tx-guard tx-env) (::tx-guard result))
    result

    :else
    (throw
      (js/Error. (str "tx handler returned invalid result for event" (:e ev) ", expected a modified env")))))

(defn unhandled-event-ex! [ev-id tx origin]
  (if (and ^boolean js/goog.DEBUG (map? origin))
    (loop [comp (comp/get-component origin)
           err-msg (str "Unhandled Event " ev-id "\n    Component Trace:")]
      (if-not comp
        ;; FIXME: directly outputting this is here is kinda ugly?
        (do (js/console.error err-msg)
            (throw (js/Error. (str "Unhandled Event " ev-id))))

        (recur
          (comp/get-parent comp)
          (str err-msg "\n    " (comp/get-component-name comp))
          )))

    (do (js/console.error "Unhandled event" ev-id tx origin)
        (throw (js/Error. (str "Unhandled Event " ev-id))))))

(defn call-interceptors [interceptors tx-env]
  (reduce
    (fn [tx-env ^function handler]
      ;; allow nils, easier to do (when DEBUG extra-interceptor) kind of handlers
      (if (nil? handler)
        tx-env
        (try
          (let [result (handler tx-env)]
            (when-not (identical? (::tx-guard result) (::tx-guard tx-env))
              (throw (js/Error. "interceptor didn't return tx-env")))
            result))))
    tx-env
    interceptors))

(def ^function tx-reporter nil)

(defn do-tx-report [tx-env]
  (when-not (nil? tx-reporter)
    (rt/next-tick
      (fn []
        (tx-reporter tx-env)))))

;; FIXME: I'm unsure it was worth extracting this into an interceptor
;; might be better if still handled in process-event directly
;; but this technically allows users to run stuff after/before this is done
(defn kv-interceptor [tx-env]
  (let [rt-ref (::sg/runtime-ref tx-env)
        ^not-native before (::sg/kv @rt-ref)

        tx-before
        (-kv-reduce
          before
          (fn [^not-native tx-env kv-table kv]
            (-assoc tx-env kv-table (kv/transacted kv)))
          tx-env)]

    (update tx-before ::sg/tx-after conj
      (fn kv-interceptor-after [^not-native tx-env]
        (when-not (identical? (::sg/kv @rt-ref) before)
          (throw (js/Error. "someone messed with kv state while in tx")))

        (let [tx-info
              (-kv-reduce
                before
                (fn [^not-native tx-info kv-table _]
                  (let [^not-native kv
                        (-lookup tx-env kv-table)

                        ^not-native kv
                        (cond
                          (instance? kv/TransactedData kv)
                          kv

                          ;; acts as dissoc-all
                          (nil? kv)
                          (kv/transacted-clear (-lookup tx-before kv-table))

                          ;; user turned transacted table into a map, acts as an override
                          ;; where something may just want to empty out a table or replace a full table with remote data
                          ;; the map becomes the table, diffed against the old one for tx-info
                          (map? kv)
                          (kv/transacted-replace (-lookup tx-before kv-table) kv)

                          :else
                          (throw (js/Error. (str "during transaction the " kv-table " was replaced with an invalid return value: " (type kv)))))


                        commit
                        (kv/tx-commit! kv)]

                    (if (identical? (:data commit) (:data-before commit))
                      ;; if no changes were done there should be no trace in tx-info
                      ;; saves some time later in invalidate-kv!
                      tx-info
                      (-assoc tx-info kv-table commit))))
                {})

              kv-after
              (-kv-reduce
                tx-info
                (fn [^not-native m kv-table tx-info]
                  (-assoc m kv-table (:data tx-info)))
                before)]

          (swap! rt-ref assoc ::sg/kv kv-after)

          (invalidate-kv! tx-info)

          (-kv-reduce
            before
            (fn [^not-native tx-env kv-table _]
              ;; just to avoid anyone touching this again
              (-dissoc tx-env kv-table))
            (-assoc tx-env ::sg/tx-info tx-info)
            ))))))

(defn process-event
  [rt-ref
   ev
   dom-ev
   origin]
  {:pre [(map? ev)]}

  ;; (js/console.log ev-id ev origin @rt-ref)

  (let [{::sg/keys [event-config event-interceptors fx-config] :as env}
        @rt-ref

        ev-id
        (:e ev)

        handler
        (or (::sg/tx (meta ev))
            (get event-config ev-id))]

    (if-not handler
      (unhandled-event-ex! ev-id ev origin)

      (try
        (let [tx-guard
              (js/Object.)

              tx-done-ref
              (atom false)

              tx-env
              ;; only set use namespaced keys
              ;; must avoid clashing with kv-tables overriding them
              {::tx-guard tx-guard
               ::sg/runtime-ref rt-ref
               ::sg/tx-after (list) ;; FILO
               ::sg/fx []
               ::sg/origin origin
               ::sg/dom-event dom-ev
               ::sg/event ev}

              tx-env
              (call-interceptors event-interceptors tx-env)

              handler-result
              (handler tx-env ev dom-ev)

              result
              (merge-result tx-env ev handler-result)

              result
              (call-interceptors (::sg/tx-after result) result)]

          ;; FIXME: re-frame allows fx to edit db but we already committed it
          ;; currently not checking fx-fn return value at all since they supposed to run side effects only
          ;; and may still edit stuff in env, just not db?

          ;; dispatching async so render can get to it sooner
          ;; dispatching these async since they can never do anything that affects the current render right?
          (rt/next-tick
            (fn []
              (doseq [[fx-key value] (::sg/fx result)]
                (let [fx-fn (get fx-config fx-key)

                      fx-env
                      (assoc result
                        ;; creating this here so we can easily track which fx caused further work
                        ;; technically all fx could run-now! directly given they have the scheduler from the env
                        ;; but here we can easily track tx-done-ref to ensure fx doesn't actually immediately trigger
                        ;; other events when they shouldn't because this is still in run-now! itself
                        ;; FIXME: remove this once this is handled directly in the scheduler
                        ;; run-now! inside run-now! should be a hard error
                        :transact!
                        (fn [fx-tx]
                          (when-not @tx-done-ref
                            (throw (js/Error. "cannot start another tx yet, current one is still running. transact! is meant for async events" {})))

                          (process-event rt-ref fx-tx nil origin)))]

                  (if-not fx-fn
                    (throw (js/Error. (str "unknown fx " fx-key)))

                    (fx-fn fx-env value))))))

          (do-tx-report result)

          (reset! tx-done-ref true)

          (:return result))

        (catch :default e
          (let [event-error-handler (::sg/event-error-handler env)]
            (event-error-handler env ev origin e))
          )))))

(defn lazy-seq? [thing]
  (and (instance? cljs.core/LazySeq thing)
       (not (realized? thing))))

;; calls (f keys key sub) for every key in reads that is not in other-reads
;; reads are {kv-table #{key}}, keys is the index of the kv-table
(defn- diff-reads! [^not-native reads ^not-native other-reads f sub]
  (-kv-reduce
    reads
    (fn [_ kv-table ^not-native table-keys]
      (let [other-keys (-lookup other-reads kv-table)]
        (when-not (identical? table-keys other-keys)
          (let [keys (.-keys (get-table-index kv-table))]
            (if (nil? other-keys)
              (reduce (fn [_ key] (f keys key sub) nil) nil table-keys)
              (reduce
                (fn [_ key]
                  (when-not (contains? other-keys key)
                    (f keys key sub))
                  nil)
                nil
                table-keys)))))
      nil)
    nil))

(defn- table-in? [^array tables kv-table]
  (loop [i 0]
    (cond
      (>= i (alength tables)) false
      (keyword-identical? kv-table (aget tables i)) true
      :else (recur (inc i)))))

;; calls (f kv-table sub) for every table in tables that is not in other-tables
(defn- diff-tables! [^array tables ^array other-tables f sub]
  (dotimes [i (alength tables)]
    (let [kv-table (aget tables i)]
      (when-not (table-in? other-tables kv-table)
        (f kv-table sub)))))

(def ^:private no-tables (array))

;; a query is shared by all slots using the same query-fn and args (subs).
;; it is a sub in the read index itself, for everything the query-fn read
(deftype Query
  [^not-native query-key
   rt-ref
   subs
   ^:mutable invalidated
   ;; the invalidation-id it last told subs about
   ^:mutable invalidation
   ;; {kv-table #{key}}, only tables it read keys from
   ^:mutable read-keys
   ;; array of kv-table, the tables it used seq on
   ^:mutable read-tables
   ^:mutable result]

  gp/IInvalidateSlot
  (invalidate! [this]
    (set! invalidated true)
    ;; only invalidate sub refs, do not run yet
    ;; possible that component gets unmounted and query is no longer needed
    (when-not (identical? invalidation invalidation-id)
      (set! invalidation invalidation-id)
      (.forEach subs notify-sub)))

  Object
  (add-sub [this ref]
    (.add subs ref))

  (del-sub [this ref]
    (.delete subs ref)

    ;; FIXME: using the removal of the last sub as signal to cleanup might not be ideal
    ;; this doesn't need to happen immediately
    (when (zero? (.-size subs))
      (swap! active-queries-ref dissoc query-key)
      (diff-reads! read-keys {} keys-del! this)
      (diff-tables! read-tables no-tables index-table-del! this)
      (set! read-keys {})
      (set! read-tables no-tables)))

  (run! [this]
    (let [kv
          (::sg/kv @rt-ref)

          ^not-native query-env
          (-kv-reduce
            kv
            (fn [query-env kv-table ^not-native kv]
              (-assoc query-env kv-table (kv/observed kv)))
            {::sg/runtime-ref rt-ref
             ::sg/previous-result result})

          next-result
          (apply (-nth query-key 0) query-env (-nth query-key 1))

          ;; FIXME: could just force it?
          ;; seems cleaner to let user handle it
          ;; it needs to be forced since otherwise the key recording might miss something
          _ (when (lazy-seq? next-result)
              (throw
                (js/Error. "query functions are not allowed to return lazy sequences!")))

          next-tables
          (array)

          next-keys
          (-kv-reduce
            kv
            (fn [^not-native reads kv-table _]
              (let [^not-native observed (-lookup query-env kv-table)
                    ^not-native res (kv/observed-keys observed)
                    ^not-native kv-keys (-nth res 1)]
                (when (-nth res 0)
                  (.push next-tables kv-table))
                (if (zero? (-count kv-keys))
                  reads
                  (-assoc reads kv-table kv-keys))))
            {})]

      ;; only what changed since the last run touches the index
      (diff-reads! next-keys read-keys keys-add! this)
      (diff-reads! read-keys next-keys keys-del! this)
      (diff-tables! next-tables read-tables index-table-add! this)
      (diff-tables! read-tables next-tables index-table-del! this)

      (set! read-keys next-keys)
      (set! read-tables next-tables)
      (set! invalidated false)
      (set! result next-result))

    js/undefined)

  (get-result [this]
    (when invalidated
      (.run! this))

    result
    ))

(defn- setup-query [rt-ref query-key]
  (let [q (Query.
            query-key
            rt-ref
            (js/Set.)
            true
            0
            {}
            no-tables
            nil)]
    (swap! active-queries-ref assoc query-key q)
    q))

(defn- get-query [rt-ref read-fn args]
  (let [query-key [read-fn args]]
    (or (-lookup @active-queries-ref query-key)
        (setup-query rt-ref query-key))))

(defn slot-query [args read-fn]
  (let [ref (rt/claim-slot! ::slot-query)
        rt-ref (::sg/runtime-ref rt/*env*)
        query (get-query rt-ref read-fn args)]

    (when (nil? @ref)
      (comp/set-cleanup! ref
        (fn [query]
          (.del-sub query ref))))

    (when-not (identical? query @ref)
      (when-some [prev-query @ref]
        (.del-sub prev-query ref))

      (.add-sub query ref)
      (reset! ref query))

    (.get-result query)))

;; state of a sg/kv-lookup slot, what it is in the read index for
;; the slot ref itself is the sub, so a change invalidates the slot directly
(deftype KvRead [ref ^:mutable kv-table ^:mutable key])

(defn- kv-read-table-cleanup [^KvRead read]
  (index-table-del! (.-kv-table read) (.-ref read)))

(defn- kv-read-key-cleanup [^KvRead read]
  (index-key-del! (.-kv-table read) (.-key read) (.-ref read)))

(defn slot-kv-get [kv-table]
  (let [^not-native ref
        (rt/claim-slot! ::slot-kv-get)

        rt-ref
        (::sg/runtime-ref rt/*env*)

        ;; throws for unknown tables, before the index is touched
        kv
        (kv/get-kv! (::sg/kv @rt-ref) kv-table)

        ^KvRead read
        @ref]

    (cond
      (nil? read)
      (do (index-table-add! kv-table ref)
          (-reset! ref (KvRead. ref kv-table nil))
          (comp/set-cleanup! ref kv-read-table-cleanup))

      (not (keyword-identical? kv-table (.-kv-table read)))
      (do (index-table-del! (.-kv-table read) ref)
          (index-table-add! kv-table ref)
          (set! (.-kv-table read) kv-table)))

    ;; no observation is performed, assuming the user wanted everything
    kv))

(defn slot-kv-lookup [kv-table key]
  (let [^not-native ref
        (rt/claim-slot! ::slot-kv-lookup)

        rt-ref
        (::sg/runtime-ref rt/*env*)

        ;; throws for unknown tables, before the index is touched
        ^not-native kv
        (kv/get-kv! (::sg/kv @rt-ref) kv-table)

        ^KvRead read
        @ref]

    (cond
      (nil? read)
      (do (index-key-add! kv-table key ref)
          (-reset! ref (KvRead. ref kv-table key))
          (comp/set-cleanup! ref kv-read-key-cleanup))

      ;; only a different key touches the index, a re-run after a change doesn't
      (not (and (keyword-identical? kv-table (.-kv-table read))
                (= key (.-key read))))
      (do (index-key-del! (.-kv-table read) (.-key read) ref)
          (index-key-add! kv-table key ref)
          (set! (.-kv-table read) kv-table)
          (set! (.-key read) key)))

    (-lookup kv key)))

(defn slot-state [init-state merge-fn]
  (let [^not-native ref (rt/claim-slot! ::slot-state)
        state @ref]

    (cond
      (nil? state)
      (-reset! ref (vary-meta init-state assoc ::ref ref ::init-state init-state))

      (and merge-fn (not= init-state (::init-state (meta state))))
      (-swap! ref (fn [state]
                    (-> state
                        (merge-fn init-state)
                        ;; just in case the merge-fn dropped these
                        (vary-meta assoc ::ref ref ::init-state init-state)))))

    @ref
    ))