(ns examples.websocket
  "A connection that stays open: one websocket for the whole app, the pattern the
   shadow-cljs UI uses. grove has nothing websocket specific, and it needs nothing. What
   comes in starts a tx ([external-events](/external-events/)), what goes out is an fx
   ([fx](/fx/)), so tx handlers never touch the socket.

   This page talks to a real socket in `src/dev/examples/server.clj`. Every open tab of
   it is one client, so open it a second time to see messages arrive. For a plain request
   and response use [http-fx](/http-fx/)."
  (:require
    [clojure.string :as str]
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.kv :as kv]
    [shadow.grove.transit :as transit]))

(defonce app (sg/get-runtime :app))

(defonce tab-id (subs (str (random-uuid)) 0 4))

;; ## one socket
;;
;; `connect!` runs once in `init` and keeps the socket on the runtime, so everything that
;; has the `rt-ref` can reach it. The listeners have no `tx-env`, they only turn socket
;; events into txs.
;;
;; Every message is a map with an `:op`. They travel as transit, which keeps keywords
;; intact: `(transit/init! app)` puts `::sg/transit-str` and `::sg/transit-read` on the
;; runtime. These are the ops of this page, the following chapters go through them:
;;
;; | op | sent by | for |
;; |---|---|---|
;; | `:ws/welcome` | server | first message of every connection, assigns the `:client-id` |
;; | `:ws/hello` | client | introduces the client to the others |
;; | `:ws/request-clients` | client | asks for `:ws/clients`, `:notify` subscribes to `:ws/notify` |
;; | `:ws/clients`, `:ws/notify` | server | all clients, then each one that comes or goes |
;; | `:ws/say`, `:ws/said` | both | a message from a client, pushed to all clients |
;; | `:ws/describe-client` | client | a call, answered by `:ws/client-description` or `:ws/client-not-found` |
;; | `:ws/ping`, `:ws/pong` | both | keeps the connection alive |
;; | `:ws/close-me` | client | only for this page, makes the server close the socket |

(declare on-message)

(defn cast! [rt-ref msg]
  (let [{::keys [socket] ::sg/keys [transit-str]} @rt-ref]
    (when (not= :ws/pong (:op msg))
      (c/log! "send" msg))
    (.send socket (transit-str msg))))

(defn connect! [rt-ref]
  (let [socket
        (js/WebSocket.
          (str (str/replace js/self.location.protocol "http" "ws")
               "//" js/self.location.host
               "/websocket/api/ws"))]

    (swap! rt-ref assoc ::socket socket)

    (.addEventListener socket "message"
      (fn [e]
        (on-message rt-ref (.-data e))))

    (.addEventListener socket "close"
      (fn [e]
        (sg/run-tx! rt-ref {:e :ws/closed})))))

;; ## sending
;;
;; `cast!` above sends one message and expects nothing back. It is a plain fn for code
;; that isn't a tx. A tx handler doesn't call it, sending is a side effect. It queues the
;; `:ws-send` fx instead, with a vector of messages sent in order.
;;
;; "say hello" sends `:ws/say`, and the tx writes nothing. The server pushes `:ws/said` to
;; every client, this tab included, and that write is the one that shows the message.
;;
;; A message with a `::result` is a call instead of a cast, [see below](#calls). Only
;; `:ws-send` knows about it. `::result` never goes over the wire.

(defonce call-id-seq (atom 0))

(defonce calls (atom {}))

(defn call! [rt-ref msg result]
  (let [call-id (swap! call-id-seq inc)]
    (swap! calls assoc call-id result)
    (cast! rt-ref (assoc msg :call-id call-id))))

(defn register-fx! []
  (sg/reg-fx app :ws-send
    (fn [fx-env messages]
      (let [rt-ref (::sg/runtime-ref fx-env)]
        (doseq [msg messages]
          (if-some [result (::result msg)]
            (call! rt-ref (dissoc msg ::result) result)
            (cast! rt-ref msg)))))))

(deftx say! [text]
  [tx-env ev e]
  (sg/queue-fx tx-env :ws-send [{:op :ws/say :text text}]))

;; ## incoming messages
;;
;; Every message lands in `on-message`. There are three kinds:
;;
;; - A `:call-id` marks a reply. It runs the event map kept by `call!`, with the reply
;;   added as `:call-result`.
;; - `:ws/ping` is answered on the spot. A server can use it to tell a live client from
;;   a dead one (this one only counts the answers). It is an op because browsers don't
;;   expose protocol level pings. A tx here would change nothing and fill the event log,
;;   which is also why the log below doesn't show them.
;; - Everything else starts a tx, and the `:op` is the event id. Each op gets its own
;;   `reg-event` ([transactions](/transactions/)), which is what the event log in the
;;   devtools then shows. One `::message` event with a `case` inside would work too, but
;;   every entry in that log would have the same name.

(defn on-message [rt-ref data]
  (let [{::sg/keys [transit-read]} @rt-ref
        {:keys [op call-id] :as msg} (transit-read data)]

    (when (not= :ws/ping op)
      (c/log! "received" msg))

    (cond
      call-id
      (let [result (get @calls call-id)]
        (swap! calls dissoc call-id)
        (sg/run-tx! rt-ref (assoc result :call-result msg)))

      (= :ws/ping op)
      (cast! rt-ref {:op :ws/pong})

      :else
      (sg/run-tx! rt-ref (assoc msg :e op)))))

;; ## welcome and server push
;;
;; The socket's `open` event only says the connection is up. The server speaks first:
;; `:ws/welcome` assigns the `:client-id`, and its handler queues whatever has to happen
;; on every new connection. Here that is saying hello and subscribing to the other
;; clients. It is a tx like any other message, so there is no callback to pass around.
;;
;; `:ws/clients` is the state at that moment, `:ws/notify` keeps it current from then on.
;; Both are ordinary writes to the `:client` table, so components read them like any
;; other data ([kv-reads](/kv-reads/)). A tab that goes away stays in the table, marked
;; `:disconnected`. Close a second tab to see it.

(defn on-welcome [tx-env {:keys [client-id]} e]
  (-> tx-env
      (assoc-in [:ui :client-id] client-id)
      (sg/queue-fx :ws-send
        [{:op :ws/hello :client-info {:tab tab-id}}
         {:op :ws/request-clients :notify true}])))

(defn on-clients [tx-env {:keys [clients]} e]
  (kv/merge-seq tx-env :client clients))

(defn on-notify [tx-env {:keys [event-op client-id client-info]} e]
  (case event-op
    :client-connect
    (kv/add tx-env :client {:client-id client-id :client-info client-info})

    :client-disconnect
    (assoc-in tx-env [:client client-id :disconnected] true)))

(defn on-said [tx-env {:keys [from text]} e]
  (update-in tx-env [:ui :messages] conj [from text]))

(defn on-closed [tx-env ev e]
  (assoc-in tx-env [:ui :connected?] false))

(defn register-events! []
  (sg/reg-event app :ws/welcome on-welcome)
  (sg/reg-event app :ws/clients on-clients)
  (sg/reg-event app :ws/notify on-notify)
  (sg/reg-event app :ws/said on-said)
  (sg/reg-event app :ws/closed on-closed))

(defn ?clients [env]
  (->> (vals (:client env))
       (sort-by :client-id)
       (vec)))

(defc ui-client [{:keys [client-id client-info disconnected]} idx]
  (bind own-id (sg/kv-lookup :ui :client-id))
  (render
    (<< [:li {:data-client client-id :class (when disconnected c/$muted)}
         "client " client-id " (tab " (:tab client-info) ")"
         (when (= own-id client-id) " this tab")
         (when disconnected " disconnected")])))

;; ## calls
;;
;; A call is a message that wants a reply. The `::result` of a queued message is an event
;; map, usually from a deftx. `call!` above gives the message a `:call-id` and keeps the
;; event map. The server sends the `:call-id` back with its reply, and `on-message` runs
;; the event map with the reply under `:call-result`.
;;
;; - Whatever the handler needs from the moment of asking goes into that event map, like
;;   the `client-id` here. The server doesn't have to send it back.
;; - One handler gets every outcome, the reply's `:op` says which one it is. Here
;;   describing a disconnected client gets `:ws/client-not-found`.
;; - "describe" also shows the count of `:ws/pong` the server received, which grows
;;   without a tx ever running for it.

(deftx describe-result! [client-id]
  [tx-env {:keys [call-result]} e]
  (let [{:keys [op pongs said]} call-result]
    (assoc-in tx-env [:client client-id :description]
      (case op
        :ws/client-description (str "pongs: " pongs ", said: " said)
        :ws/client-not-found "the server doesn't know this client"))))

(deftx describe! [client-id]
  [tx-env ev e]
  (sg/queue-fx tx-env :ws-send
    [{:op :ws/describe-client
      :client-id client-id
      ::result (describe-result! client-id)}]))

(defc ui-describe [{:keys [client-id description]} idx]
  (bind connected? (sg/kv-lookup :ui :connected?))
  (render
    (<< [:div {:class c/$row :data-describe client-id}
         [:button {:class c/$btn :disabled (not connected?) :on-click (describe! client-id)}
          "describe client " client-id]
         [:span description]])))

;; ## losing the connection
;;
;; When the socket closes, `:ws/closed` sets `:connected?`, and the banner reads that like
;; any other data. `:connected?` starts as `true`: the first connect is assumed to work,
;; otherwise the banner would flash on every page load until the socket is up.
;;
;; - Nothing reconnects by itself. A reload opens a new socket, gets a new `:ws/welcome`,
;;   and its handler requests everything again.
;; - The `.send` of a closed socket doesn't fail, the message is just lost. The buttons
;;   here are disabled for that reason. A socket that is still connecting throws instead,
;;   so they also wait for `:client-id`.
;; - A call still waiting when the socket closes gets no reply, its result tx never runs.
;;   Its entry stays in `calls`, which matters if the UI waits for it.

(deftx close-me! []
  [tx-env ev e]
  (sg/queue-fx tx-env :ws-send [{:op :ws/close-me}]))

(defn ui-banner []
  (<< [:div#banner {:class (css :p-2 :mb-2 :rounded :bg-red-700 :text-white :font-semibold)}
       "websocket not connected, reload the page to reconnect"]))

;; # page

(defc ui-root []
  (bind {:keys [connected? client-id messages]} (sg/kv-lookup :ui))
  (bind clients (sg/query ?clients))
  (bind ready? (and connected? (some? client-id)))
  (render
    (c/page "websocket"
      {"one socket"
       [:div#status
        (cond
          (not connected?) "not connected"
          client-id (str "connected as client " client-id)
          :else "connecting ...")]

       "sending"
       (<< [:button {:class c/$btn :disabled (not ready?) :on-click (say! (str "hello from " tab-id))} "say hello"]
           [:div#messages {:class c/$muted} "received: " (pr-str messages)])

       "welcome and server push"
       [:ul#clients (sg/keyed-seq clients :client-id ui-client)]

       "calls"
       [:div#describe (sg/keyed-seq clients :client-id ui-describe)]

       "losing the connection"
       (<< (when-not connected?
             (ui-banner))
           [:button {:class c/$btn :disabled (not ready?) :on-click (close-me!)} "server closes this socket"])})))

(defn init []
  (sg/add-kv-table app :ui {} {:connected? true :client-id nil :messages []})
  (sg/add-kv-table app :client {:primary-key :client-id} {})
  (transit/init! app)
  (register-fx!)
  (register-events!)
  (connect! app)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
