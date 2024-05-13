(ns shadow.grove.devtools.relay-ws
  (:require
    [shadow.grove :as sg]
    [shadow.grove.kv :as kv]
    [shadow.grove.runtime :as rt]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools :as-alias m]
    [clojure.string :as str]))

(defonce rpc-id-seq (atom 0))
(defonce rpc-ref (atom {}))

(defmulti handle-msg (fn [env msg] (:op msg)) :default ::default)

(defmethod handle-msg ::default [env msg]
  (js/console.warn "unhandled websocket msg" msg env)
  env)

(defmethod handle-msg :welcome
  [env {:keys [client-id]}]

  ;; FIXME: call this via fx
  (let [{::keys [on-welcome]} @(::rt/runtime-ref env)]
    (on-welcome))

  (-> env
      (update :db assoc ::m/tool-id client-id ::m/relay-ws-connected true)
      (ev/queue-fx :relay-send
        {:op :request-clients
         :notify true
         :query [:eq :type :runtime]})))

(defmethod handle-msg :clients
  [env {:keys [clients]}]
  (-> env
      (kv/merge-seq ::m/target clients)
      (sg/queue-fx :relay-send
        {:op :request-supported-ops
         :to (into #{} (map :client-id) clients)})
      ))

(defmethod handle-msg :notify
  [env {:keys [event-op client-id] :as msg}]
  (case event-op
    :client-disconnect
    (-> env
        (update ::m/target dissoc client-id)
        (cond->
          (= client-id (get-in env [:db ::m/selected-target]))
          (update :db dissoc ::m/selected-target)))
    :client-connect
    (-> env
        (kv/add ::m/target (select-keys msg [:client-id :client-info]))
        (sg/queue-fx :relay-send
          {:op :request-supported-ops
           :to (:client-id msg)}))
    ))

(defmethod handle-msg :supported-ops
  [env {:keys [from ops] :as msg}]
  (-> env
      (assoc-in [::m/target from :supported-ops] ops)
      (cond->
        (contains? ops ::m/stream-sub)
        (sg/queue-fx :relay-send
          {:op ::m/stream-sub
           :to from})

        (contains? ops ::m/get-runtimes)
        (sg/queue-fx :relay-send
          {:op ::m/get-runtimes
           :to from})
        )))

(defmethod handle-msg ::m/stream-start
  [env {:keys [from events] :as msg}]
  (kv/merge-seq env ::m/event
    (mapv #(assoc % :event-id (random-uuid) :target-id from) events)
    (fn [env items]
      (assoc-in env [::m/target from :events] (into (list) items)))))

(defmethod handle-msg ::m/stream-update
  [env {:keys [from event] :as msg}]
  (let [event-id (random-uuid)]
    (-> env
        (kv/add ::m/event (assoc event :event-id event-id :target-id from))
        (update-in [::m/target from :events] conj event-id))))

(defmethod handle-msg ::m/work-finished
  [env {:keys [from snapshot] :as msg}]
  (assoc-in env [::m/target from :snapshot] snapshot))

(defmethod handle-msg ::m/runtimes
  [env {:keys [from runtimes] :as msg}]
  (assoc-in env [::m/target from :runtimes] runtimes))

(defmethod handle-msg ::m/focus-component
  [env {:keys [from component snapshot] :as msg}]
  (-> env
      (assoc-in [:db ::m/selected] #{component})
      (assoc-in [:db ::m/selected-target] from)
      (assoc-in [::m/target from :snapshot] snapshot)))

(defn cast! [rt-ref msg]
  (when ^boolean js/goog.DEBUG
    (when (not= :pong (:op msg))
      (js/console.log "[WS-SEND]" (:op msg) msg)))

  (let [{::keys [ws-ref] ::rt/keys [transit-str] :as env} @rt-ref]
    (.send @ws-ref (transit-str msg))))

(defn call! [rt-ref msg result-data]
  {:pre [(map? msg)
         (or (fn? result-data)
             (and (map? result-data)
                  (keyword? (:e result-data))))]}
  (let [mid (str (random-uuid))]
    (swap! rpc-ref assoc mid {:msg msg
                              :result-data result-data})
    (cast! rt-ref (assoc msg :call-id mid))))

(defn init [rt-ref server-token on-welcome]
  (let [socket (js/WebSocket.
                 (str (str/replace js/self.location.protocol "http" "ws")
                      "//" js/self.location.host
                      "/api/remote-relay"
                      "?server-token=" server-token))

        ws-ref (atom socket)]

    (ev/reg-fx rt-ref :relay-send
      (fn [env msg]
        (if-some [result (::result msg)]
          (call! (::rt/runtime-ref env) (dissoc msg ::result) result)
          (cast! (::rt/runtime-ref env) msg))))

    (sg/reg-event rt-ref
      ::m/relay-ws-close
      (fn [env _]
        (assoc-in env [:db ::m/relay-ws-connected] false)))

    (sg/reg-event rt-ref
      ::m/relay-ws
      (fn [env {:keys [msg]}]
        ;; (js/console.log ::m/relay-ws op msg)
        (handle-msg env msg)))

    (swap! rt-ref assoc
      ::ws-ref ws-ref
      ::socket socket
      ::server-token server-token
      ::on-welcome
      (fn []
        (cast! rt-ref {:op :hello
                       :client-info {:type :shadow.grove.devtools}})
        (on-welcome)))

    (let [{::rt/keys [^function transit-read]} @rt-ref]
      (.addEventListener socket "message"
        (fn [e]
          (let [{:keys [call-id op] :as msg} (transit-read (.-data e))]

            (cond
              call-id
              (let [{:keys [result-data] :as call-data} (get @rpc-ref call-id)]
                (if (fn? result-data)
                  (result-data msg)
                  (sg/run-tx! rt-ref (assoc result-data :call-result msg))))

              (= :ping op)
              (cast! rt-ref {:op :pong})

              :else
              (sg/run-tx! rt-ref {:e ::m/relay-ws :msg msg}))))))

    (.addEventListener socket "open"
      (fn [e]
        ;; (js/console.log "tool-open" e socket)
        ))

    (.addEventListener socket "close"
      (fn [e]
        (sg/run-tx! rt-ref {:e ::m/relay-ws-close})
        (js/console.log "tool-close" e)))

    (.addEventListener socket "error"
      (fn [e]
        (js/console.warn "tool-error" e)))))

