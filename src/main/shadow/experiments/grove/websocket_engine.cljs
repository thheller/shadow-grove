(ns shadow.experiments.grove.websocket-engine
  (:require
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.components :as comp]
    [cognitect.transit :as transit]))

(defn send-to-ws [ws ^function transit-str msg]
  ;; (js/console.log "worker-write" env msg)
  (.send ws (transit-str msg)))

(deftype Engine
  [websocket active-queries-ref transit-str]
  gp/IQueryEngine
  (query-init [this env query-id query config callback]
    (swap! active-queries-ref assoc query-id callback)
    (send-to-ws websocket transit-str [:query-init query-id query]))

  (query-destroy [this query-id]
    (send-to-ws websocket transit-str [:query-destroy query-id]))

  (transact! [this tx]
    (send-to-ws websocket transit-str [:tx tx])))

(defn init
  ([env ws]
   (init env ws ::gp/query-engine))
  ([env websocket engine-key]
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
           ::websocket websocket
           ::active-queries-ref active-queries-ref
           engine-key (->Engine websocket active-queries-ref transit-str)
           ::transit-read transit-read
           ::transit-str transit-str)]
     env)))

(defn on-open [{::keys [websocket transit-read active-queries-ref] :as env}]
  (.addEventListener websocket "message"
    (fn [e]
      (let [msg (transit-read (.-data e))]
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
  )