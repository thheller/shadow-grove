(ns shadow.experiments.grove.worker-engine
  (:require
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.components :as comp]
    [cognitect.transit :as transit]))

;; batch?
(defn send-to-worker [worker ^function transit-str msg]
  ;; (js/console.log "worker-write" env msg)
  (.postMessage worker (transit-str msg)))

(deftype WorkerEngine
  [worker active-queries-ref transit-str]
  gp/IQueryEngine
  (register-query [this env query-id query config callback]
    (swap! active-queries-ref assoc query-id callback)
    (send-to-worker worker transit-str [:query-init query-id query]))

  (unregister-query [this query-id]
    (send-to-worker worker transit-str [:query-destroy query-id]))

  (transact! [this env tx]
    (send-to-worker worker transit-str [:tx tx])))

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

         env
         (assoc env
           ::worker worker
           engine-key (->WorkerEngine worker active-queries-ref transit-str)
           ::transit-read transit-read
           ::transit-str transit-str)]

     (.addEventListener worker "message"
       (fn [e]
         (js/performance.mark "transit-read-start")
         (let [msg (transit-read (.-data e))]
           (js/performance.measure "transit-read" "transit-read-start")
           (let [[op & args] msg]

             ;; (js/console.log "main read took" (- t start))
             (case op
               :worker-ready
               (js/console.log "worker is ready")

               :query-result
               (let [[query-id result] args
                     ^function callback (get @active-queries-ref query-id)]
                 (when (some? callback)
                   (callback result)))

               (js/console.warn "unhandled main msg" op msg))))))
     env)))