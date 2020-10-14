(ns shadow.experiments.grove.local
  "runtime meant to run in the main browser thread"
  (:require
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.events :as ev]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.eql-query :as eql]))

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
      (ev/index-query env this read-keys new-keys)
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
    (ev/unindex-query @rt-ref this read-keys)))

(deftype LocalEngine [rt-ref active-queries-ref]
  gp/IQueryEngine
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