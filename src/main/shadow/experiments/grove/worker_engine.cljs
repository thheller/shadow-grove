(ns shadow.experiments.grove.worker-engine
  (:require
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.components :as comp]
    [cognitect.transit :as transit]
    [shadow.experiments.grove.ui.util :as util]))

(deftype WorkerEngine
  [^function send! active-queries-ref active-streams-ref]
  gp/IQueryEngine
  (query-init [this query-id query config callback]
    (swap! active-queries-ref assoc query-id callback)
    (send! [:query-init query-id query]))

  (query-destroy [this query-id]
    (swap! active-queries-ref dissoc query-id)
    (send! [:query-destroy query-id]))

  (transact! [this tx]
    (send! [:tx tx]))

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

           send!
           (fn send! [msg]
             ;; (js/console.log "to-worker" (first msg) msg)
             (.postMessage worker (transit-str msg)))

           msg-handlers-ref
           (atom {})

           env
           (assoc env
             ::worker worker
             engine-key (->WorkerEngine send! active-queries-ref active-streams-ref)
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

               (let [handler-fn (get @msg-handlers-ref op)]
                 (if-not handler-fn
                   (js/console.warn "unhandled main msg" op msg)
                   (apply handler-fn args)))))))
       env))))