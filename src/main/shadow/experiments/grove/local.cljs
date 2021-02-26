(ns shadow.experiments.grove.local
  "runtime meant to run in the main browser thread"
  (:require
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.events :as ev]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.eql-query :as eql]
    [shadow.experiments.grove.components :as comp]
    [shadow.experiments.grove.ui.util :as util]))

(defonce query-queue (js/Promise.resolve))

(set! *warn-on-infer* false)

(deftype ActiveQuery
  [rt-ref
   query-id
   query
   callback
   ^:mutable read-keys
   ^:mutable read-result
   ^:mutable destroyed?]

  ev/IQuery
  (query-refresh! [this]
    (when-not destroyed?
      (.do-read! this)))

  Object
  (do-read! [this]
    (let [env @rt-ref
          observed-data (db/observed @(::rt/data-ref env))
          result (eql/query env observed-data query)
          new-keys (db/observed-keys observed-data)]

      ;; remember this even if query is still loading
      (ev/index-query env query-id read-keys new-keys)
      (set! read-keys new-keys)

      ;; if query is still loading don't send to main
      (when (and (not (keyword-identical? result :db/loading))
                 ;; empty result likely means the query is no longer valid
                 ;; eg. deleted ident. don't send update, will likely be destroyed
                 ;; when other query updates
                 (some? result)
                 (not (empty? result))
                 ;; compare here so main doesn't need to compare again
                 (not= result read-result))

        (set! read-result result)

        (callback result))))

  (destroy! [this]
    (set! destroyed? true)
    (ev/unindex-query @rt-ref query-id read-keys)))

(deftype QueryHook
  [^:mutable ident
   ^:mutable query
   ^:mutable config
   component
   idx
   env
   query-engine
   query-id
   ^:mutable ready?
   ^:mutable read-result]

  gp/IHook
  (hook-init! [this]
    (.register-query! this)

    ;; async query will suspend
    ;; regular query should just proceed immediately
    (when-not (some? read-result)
      (.set-loading! this)))

  (hook-ready? [this]
    (or (false? (:suspend config)) ready?))

  (hook-value [this]
    read-result)

  ;; node deps changed, check if query changed
  (hook-deps-update! [this ^QueryHook val]
    (if (and (= ident (.-ident val))
             (= query (.-query val))
             (= config (.-config val)))
      false
      ;; query changed, remove it entirely and wait for new one
      (do (.unregister-query! this)
          (set! ident (.-ident val))
          (set! query (.-query val))
          (set! config (.-config val))
          (.set-loading! this)
          (.register-query! this)
          true)))

  ;; node was invalidated and needs update, but its dependencies didn't change
  (hook-update! [this]
    true)

  (hook-destroy! [this]
    (.unregister-query! this))

  Object
  (register-query! [this]
    (gp/query-init query-engine query-id (if ident [{ident query}] query) config
      (fn [result]
        (.set-data! this result))))

  (unregister-query! [this]
    (gp/query-destroy query-engine query-id))

  (set-loading! [this]
    (set! ready? (false? (:suspend config)))
    (set! read-result (assoc (:default config {}) ::loading-state :loading)))

  (set-data! [this data]
    (let [data (if ident (get data ident) data)
          first-run? (nil? read-result)]
      (set! read-result (assoc data ::loading-state :ready))

      ;; first run may provide result immedialy in which case which don't need to tell the
      ;; component that we are ready separately, it'll just check ready? on its own
      ;; async queries never have their data immediately ready and will suspend unless configured not to
      (if first-run?
        (set! ready? true)
        (if ready?
          (comp/hook-invalidate! component idx)
          (do (comp/hook-ready! component idx)
              (set! ready? true)))))))

(deftype LocalEngine [rt-ref active-queries-ref]
  gp/IQueryEngine
  (query-hook-build [this env component idx ident query config]
    (QueryHook. ident query config component idx env this (util/next-id) false nil))

  (query-init [this query-id query config callback]
    (let [q (ActiveQuery. rt-ref query-id query callback nil nil false)]
      (swap! active-queries-ref assoc query-id q)
      (.do-read! q)))

  (query-destroy [this query-id]
    (when-some [q (get @active-queries-ref query-id)]
      (swap! active-queries-ref dissoc query-id)
      (.destroy! q)))

  (transact! [this tx with-return?]
    ;; FIXME: should this run in microtask instead?
    (let [return-val (ev/tx* @rt-ref tx)]
      (when with-return?
        (js/Promise.resolve return-val))))

  gp/IStreamEngine
  (stream-init [this env stream-id stream-key opts callback])
  (stream-destroy [this stream-id stream-key])
  (stream-clear [this stream-key]))

(defn init [rt-ref]
  (fn [env]
    (let [{::rt/keys [active-queries-ref data-ref]} @rt-ref]
      (assoc env ::gp/query-engine (LocalEngine. rt-ref active-queries-ref)))))