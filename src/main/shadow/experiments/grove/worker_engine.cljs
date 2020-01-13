(ns shadow.experiments.grove.worker-engine
  (:require
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.components :as comp]
    [cognitect.transit :as transit]))

(deftype WorkerEngine
  [^function send! active-queries-ref active-streams-ref]
  gp/IQueryEngine
  (register-query [this env query-id query config callback]
    (swap! active-queries-ref assoc query-id callback)
    (send! [:query-init query-id query]))

  (unregister-query [this query-id]
    (send! [:query-destroy query-id]))

  (transact! [this env tx]
    (send! [:tx tx]))

  gp/IStreamEngine
  (stream-init [this env stream-id stream-key opts callback]
    (swap! active-streams-ref assoc stream-id callback)
    (send! [:stream-init stream-id stream-key opts])
    ))

(defn init
  ([env worker]
   (init env worker ::gp/query-engine))
  ([env worker engine-key]
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

         send!
         (fn send! [msg]
           (.postMessage worker (transit-str msg)))

         env
         (assoc env
           ::worker worker
           engine-key (->WorkerEngine send! active-queries-ref active-streams-ref)
           ::transit-read transit-read
           ::transit-str transit-str)]

     (.addEventListener worker "message"
       (fn [e]
         (js/performance.mark "transit-read-start")
         (let [msg (transit-read (.-data e))]
           (js/performance.measure "transit-read" "transit-read-start")
           (let [[op & args] msg]

             (js/console.log "main read" op msg)

             ;; (js/console.log "main read took" (- t start))
             (case op
               :worker-ready
               (js/console.log "worker is ready")

               :query-result
               (let [[query-id result] args
                     ^function callback (get @active-queries-ref query-id)]
                 (when (some? callback)
                   (callback result)))

               :stream-msg
               (let [[stream-id result] args
                     ^function callback (get @active-streams-ref stream-id)]
                 (when (some? callback)
                   (callback result)))

               (js/console.warn "unhandled main msg" op msg))))))
     env)))