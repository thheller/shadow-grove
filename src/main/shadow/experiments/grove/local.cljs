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

(def ^function
  work-queue-task!
  (if js/window.requestIdleCallback
    (fn [work-task]
      (js/window.requestIdleCallback work-task))
    (fn [^function work-task]
      ;; microtask or goog.async.run don't do what we want
      ;; we want the browser to prioritise rendering stuff
      ;; the other work can be delayed until idle. setTimeout seems closest.
      (js/setTimeout
        (fn []
          (let [start (js/Date.now)
                fake-deadline
                #js {:timeRemaining
                     #(< 16 (- (js/Date.now) start))}]
            (work-task fake-deadline)))
        ;; usually 4 or so minimum but that is good enough for our purposes
        0))))

(def ^function
  work-queue-cancel!
  (if js/window.cancelIdleCallback
    (fn [id]
      (js/window.cancelIdleCallback id))
    (fn [id]
      (js/clearTimeout id))))

(defonce index-queue (js/Array.))

(defonce work-queued? false)
(defonce work-timeout nil)

(defn index-work-all! []
  (when work-timeout
    (work-queue-cancel! work-timeout)
    (set! work-timeout nil))

  ;; work until all work is done, immediately work off new tasks
  (loop []
    (let [^function task (.shift index-queue)]
      (when ^boolean task
        (task)
        (recur))))

  (set! work-queued? false))

(defn index-work-some! [^js deadline]
  (loop []
    (when (pos? (.timeRemaining deadline))
      (let [^function task (.shift index-queue)]
        (when ^boolean task
          (task)
          (recur)))))

  (if (pos? (alength index-queue))
    (do (set! work-timeout (work-queue-task! index-work-some!))
        (set! work-queued? true))
    (do (set! work-timeout nil)
        (set! work-queued? false))))

(defn index-queue-some! []
  (when-not work-queued?
    (set! work-timeout (work-queue-task! index-work-some!))
    (set! work-queued? true)))

(defn index-query [env query-id prev-keys next-keys]
  (.push index-queue #(ev/index-query* env query-id prev-keys next-keys))
  (index-queue-some!))

(defn unindex-query [env query-id keys]
  (.push index-queue #(ev/unindex-query* env query-id keys))
  (index-queue-some!))

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
    (let [query-env @rt-ref
          observed-data (db/observed @(::rt/data-ref query-env))
          result (eql/query query-env observed-data query)
          new-keys (db/observed-keys observed-data)]

      ;; remember this even if query is still loading
      (index-query query-env query-id read-keys new-keys)

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
    (unindex-query @rt-ref query-id read-keys)))

(deftype QueryHook
  [^:mutable ident
   ^:mutable query
   ^:mutable config
   component
   idx
   rt-ref
   active-queries-map
   query-id
   ^:mutable ready?
   ^:mutable read-count
   ^:mutable read-keys
   ^:mutable read-result]

  gp/IHook
  (hook-init! [this]
    (.do-read! this))

  (hook-ready? [this]
    (or ready? (false? (:suspend config))))

  (hook-value [this] read-result)

  ;; node deps changed, check if query changed
  (hook-deps-update! [this ^QueryHook val]
    (if (and (= ident (.-ident val))
             (= query (.-query val))
             (= config (.-config val)))
      false
      ;; query changed, perform read immediately
      (do (set! ident (.-ident val))
          (set! query (.-query val))
          (set! config (.-config val))
          (let [old-result read-result]
            (.do-read! this)
            (not= old-result read-result)))))

  ;; node was invalidated and needs update
  (hook-update! [this]
    (let [old-result read-result]
      (.do-read! this)
      (not= old-result read-result)))

  (hook-destroy! [this]
    (unindex-query @rt-ref query-id read-keys)
    (.delete active-queries-map query-id))

  ev/IQuery
  (query-refresh! [this]
    (if-not ready?
      (comp/hook-ready! component idx)
      (comp/hook-invalidate! component idx)))

  Object
  (do-read! [this]
    (let [query-env @rt-ref
          db @(::rt/data-ref query-env)]

      (if (and ident (nil? query))
        ;; shortcut for just getting data for an ident
        ;; don't need all the query stuff for those
        (let [result (get db ident)
              new-keys #{ident}]

          (set! read-keys new-keys)

          ;; only need to index once
          (when (zero? read-count)
            (index-query query-env query-id nil new-keys))

          (if (keyword-identical? result :db/loading)
            (set! read-result (assoc (:default config {}) :shadow.experiments.grove/loading-state :loading))
            (do (set! read-result result)
                (set! ready? true))))

        ;; query env is not the component env
        (let [observed-data (db/observed db)
              db-query (if ident [{ident query}] query)
              result (eql/query query-env observed-data db-query)
              new-keys (db/observed-keys observed-data)]

          (index-query query-env query-id read-keys new-keys)

          (set! read-keys new-keys)

          (if (keyword-identical? result :db/loading)
            (set! read-result (assoc (:default config {}) :shadow.experiments.grove/loading-state :loading))

            (do (set! read-result
                  (-> (if ident (get result ident) result)
                      (assoc :shadow.experiments.grove/loading-state :ready)))
                (set! ready? true))))))

    (set! read-count (inc read-count))))

(deftype LocalEngine [rt-ref active-queries-map]
  gp/IQueryEngine
  (query-hook-build [this env component idx ident query config]
    (let [query-id (util/next-id)
          hook (QueryHook.
                 ident
                 query
                 config
                 component
                 idx
                 rt-ref
                 active-queries-map
                 query-id
                 false
                 0
                 nil
                 nil)]
      (.set active-queries-map query-id hook)
      hook))

  ;; direct query, hooks don't use this
  (query-init [this query-id query config callback]
    (let [q (ActiveQuery. rt-ref query-id query callback nil nil false)]
      (.set active-queries-map query-id q)
      (.do-read! q)))

  (query-destroy [this query-id]
    (when-some [q (.get active-queries-map query-id)]
      (.delete active-queries-map query-id)
      (.destroy! q)))

  (transact! [this tx origin]
    (try
      (js/Promise.resolve (ev/tx* @rt-ref tx origin))
      (catch :default ex
        (js/Promise.reject ex)))))

(defn init! [rt-ref]
  ;; kinda ugly but shortest way to have worker not depend this
  (swap! rt-ref assoc ::rt/query-index-queue-flush!
    (fn []
      (when work-queued?
        (index-work-all!))))

  (swap! rt-ref
    (fn [{::rt/keys [active-queries-map] :as rt}]
      (let [engine (LocalEngine. rt-ref active-queries-map)]
        (assoc rt ::gp/query-engine engine)
        )))

  rt-ref)