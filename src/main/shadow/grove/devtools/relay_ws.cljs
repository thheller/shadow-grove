(ns shadow.grove.devtools.relay-ws
  (:require
    [shadow.grove :as sg]
    [shadow.grove.db :as db]
    [shadow.grove.runtime :as rt]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools.env :as env]
    [shadow.grove.devtools :as-alias m]
    [clojure.string :as str]))

(defonce rpc-id-seq (atom 0))
(defonce rpc-ref (atom {}))

(defmulti handle-msg (fn [env msg] (:op msg)) :default ::default)

(defmethod handle-msg ::default [env msg]
  (js/console.warn "unhandled websocket msg" msg env)
  env)

(defmethod handle-msg :welcome
  [{::keys [on-welcome] :as env} {:keys [client-id]}]

  ;; FIXME: call this via fx
  (on-welcome)

  (-> env
      (update :db assoc ::m/tool-id client-id ::m/relay-ws-connected true)
      (ev/queue-fx :relay-send
        [{:op :request-clients
          :notify true
          :query [:eq :type :runtime]}])))

(defmethod handle-msg :clients
  [env {:keys [clients]}]
  (-> env
      (update :db db/merge-seq ::m/runtime clients)
      (sg/queue-fx :relay-send
        [{:op :request-supported-ops
          :to (into #{} (map :client-id) clients)}])
      ))

(defmethod handle-msg :notify
  [env {:keys [event-op] :as msg}]
  (case event-op
    :client-disconnect
    (let [ident (db/make-ident ::m/runtime (:client-id msg))]
      (-> env
          (update :db dissoc ident)
          (cond->
            (= ident (get-in env [:db ::m/selected-runtime]))
            (update :db dissoc ::m/selected-runtime))))
    :client-connect
    (-> env
        (update :db db/add ::m/runtime (select-keys msg [:client-id :client-info]))
        (sg/queue-fx :relay-send
          [{:op :request-supported-ops
            :to (:client-id msg)}]))
    ))

(defmethod handle-msg :supported-ops
  [env {:keys [from ops] :as msg}]
  (assoc-in env [:db (db/make-ident ::m/runtime from) :supported-ops] ops))

(defmethod handle-msg :shadow.grove.preload/work-finished
  [env {:keys [from snapshot] :as msg}]
  (assoc-in env [:db (db/make-ident ::m/runtime from) :snapshot] snapshot))

(defmethod handle-msg ::m/focus-component
  [env {:keys [from component snapshot] :as msg}]
  (let [runtime-ident (db/make-ident ::m/runtime from)]
    (-> env
        (assoc-in [:db ::m/selected] #{component})
        (assoc-in [:db ::m/selected-runtime] runtime-ident)
        (update-in [:db runtime-ident] merge {:snapshot snapshot}))))

(defn cast! [{::keys [ws-ref] ::rt/keys [transit-str] :as env} msg]
  (when ^boolean js/goog.DEBUG
    (when (not= :pong (:op msg))
      (js/console.log "[WS-SEND]" (:op msg) msg)))
  (.send @ws-ref (transit-str msg)))

(defn call! [env msg result-data]
  {:pre [(map? msg)
         (or (fn? result-data)
             (and (map? result-data)
                  (keyword? (:e result-data))))]}
  (let [mid (swap! rpc-id-seq inc)]
    (swap! rpc-ref assoc mid {:msg msg
                              :result-data result-data})
    (cast! env (assoc msg :call-id mid))))

(ev/reg-fx env/rt-ref :relay-send
  (fn [env messages]
    (doseq [msg messages
            :when msg]
      (if-some [result (::result msg)]
        (call! env (dissoc msg ::result) result)
        (cast! env msg)))))

(defn init [rt-ref server-token on-welcome]
  (let [socket (js/WebSocket.
                 (str (str/replace js/self.location.protocol "http" "ws")
                      "//" js/self.location.host
                      "/api/remote-relay"
                      "?server-token=" server-token))
        ws-ref (atom socket)]

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
        (cast! @rt-ref {:op :hello
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
                  (sg/run-tx! env/rt-ref (assoc result-data :call-result msg))))

              (= :ping op)
              (cast! @rt-ref {:op :pong})

              :else
              (sg/run-tx! env/rt-ref {:e ::m/relay-ws :msg msg}))))))

    (.addEventListener socket "open"
      (fn [e]
        ;; (js/console.log "tool-open" e socket)
        ))

    (.addEventListener socket "close"
      (fn [e]
        (sg/run-tx! env/rt-ref {:e ::m/relay-ws-close})
        (js/console.log "tool-close" e)))

    (.addEventListener socket "error"
      (fn [e]
        (js/console.warn "tool-error" e)))))

