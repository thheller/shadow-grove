(ns shadow.experiments.grove.worker-engine
  (:require
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.components :as comp]
    [cognitect.transit :as transit]
    [shadow.experiments.grove.ui.util :as util]))

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

(deftype WorkerEngine
  [^function send! active-queries-ref active-streams-ref pending-tx-ref]
  gp/IQueryEngine
  (query-hook-build [this env component idx ident query config]
    (QueryHook. ident query config component idx env this (util/next-id) false nil))

  (query-init [this query-id query config callback]
    (swap! active-queries-ref assoc query-id callback)
    (send! [:query-init query-id query]))

  (query-destroy [this query-id]
    (swap! active-queries-ref dissoc query-id)
    (send! [:query-destroy query-id]))

  (transact! [this tx with-return?]
    (if-not with-return?
      (send! [:tx tx])
      (let [tx-id (util/next-id)]
        (send! [:tx-return tx-id tx])
        (js/Promise.
          (fn [resolve reject]
            (swap! pending-tx-ref assoc tx-id
              {:tx-id tx-id
               :tx tx
               :resolve resolve
               :reject reject})
            )))))

  gp/IStreamEngine
  (stream-init [this env stream-id stream-key opts callback]
    (swap! active-streams-ref assoc stream-id callback)
    (send! [:stream-sub-init stream-id stream-key opts]))

  (stream-destroy [this stream-id stream-key]
    (swap! active-streams-ref dissoc stream-id)
    (send! [:stream-sub-destroy stream-id stream-key]))

  (stream-clear [this stream-key]
    (send! [:stream-clear stream-key])))

(defn add-msg-handler [{::keys [msg-handlers-ref] :as env} msg-id handler-fn]
  (swap! msg-handlers-ref assoc msg-id handler-fn)
  env)

(defn init
  ([worker]
   (init worker ::gp/query-engine))
  ([worker engine-key]
   (fn [env]
     ;; FIXME: take handlers from env
     (let [tr (transit/reader :json)
           tw (transit/writer :json)

           transit-read
           (fn transit-read [data]
             (transit/read tr data))

           transit-str
           (fn transit-str [obj]
             (transit/write tw obj))

           active-queries-ref
           (atom {})

           active-streams-ref
           (atom {})

           pending-tx-ref
           (atom {})

           send!
           (fn send! [msg]
             ;; (js/console.log "to-worker" (first msg) msg)
             (.postMessage worker (transit-str msg)))

           msg-handlers-ref
           (atom {})

           env
           (assoc env
             ::worker worker
             engine-key (->WorkerEngine send! active-queries-ref active-streams-ref pending-tx-ref)
             ::msg-handlers-ref msg-handlers-ref
             ::transit-read transit-read
             ::transit-str transit-str)]

       (.addEventListener worker "message"
         (fn [e]
           (let [msg (transit-read (.-data e))
                 [op & args] msg]
             ;; (js/console.log "from-worker" op msg)
             (case op
               :worker-ready
               nil

               :query-result
               (let [[query-id result] args
                     ^function callback (get @active-queries-ref query-id)]
                 (when (some? callback)
                   ;; likely not worth delaying but we already were async
                   ;; so it really doesn't matter if we give the browser a chance to do other work
                   ;; inbetween transit-read and the actual query processing
                   (util/next-tick #(callback result))))

               :stream-msg
               (let [[stream-id result] args
                     ^function callback (get @active-streams-ref stream-id)]
                 (when (some? callback)
                   (util/next-tick #(callback result))))

               :tx-result
               (let [[tx-id tx-result] args
                     {:keys [resolve timeout]} (get @pending-tx-ref tx-id)]

                 (swap! pending-tx-ref dissoc tx-id)

                 (when timeout
                   (js/clearTimeout timeout))

                 (resolve tx-result))

               (let [handler-fn (get @msg-handlers-ref op)]
                 (if-not handler-fn
                   (js/console.warn "unhandled main msg" op msg)
                   (apply handler-fn args)))))))
       env))))