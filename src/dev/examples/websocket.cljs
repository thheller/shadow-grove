(ns examples.websocket
  "A connection that stays open: one websocket for the whole app, the pattern the
   shadow-cljs UI uses. `shadow.grove.websocket` keeps the socket on the runtime. What
   comes in starts a tx ([external-events](/external-events/)), what goes out is an fx
   ([fx](/fx/)), so tx handlers never touch the socket.

   This page talks to a real socket in `src/dev/examples/server.clj`. Every open tab of
   it is one client, so open it a second time to see messages arrive. For a plain request
   and response use [http-fx](/http-fx/)."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.kv :as kv]
    [shadow.grove.transit :as transit]
    [shadow.grove.websocket :as ws]))

(defonce app (sg/get-runtime :app))

(defonce tab-id (subs (str (random-uuid)) 0 4))

;; ## one socket
;;
;; `(ws/init! app :ws opts)` adds a connection to the runtime. The id is yours, and an app
;; with two sockets uses two.
;; `(ws/connect! app :ws)` opens the socket, it can run later than `init!`, after a login
;; for example. The options:
;;
;; | option | for |
;; |---|---|
;; | `:url` | relative to the page or a full url, `ws://` or `wss://` follows the page |
;; | `:format` | `:transit` or `:edn`, or `{:read fn :write fn}` for anything else |
;; | `:on-message` | `(fn [rt-ref msg])` for every message that isn't a reply, [see below](#incoming-messages) |
;; | `:on-open` | event map, runs when the socket opens |
;; | `:on-close` | event map, runs when the socket closes or fails to connect, with `:code` and `:reason` |
;;
;; `:format` reuses what is on the runtime already. `(transit/init! app)` puts
;; `::sg/transit-str` and `::sg/transit-read` there, `init!` throws if they are missing.
;; Transit keeps keywords intact. A message can be any map, `shadow.grove.websocket` only
;; ever looks at `:call-id`. On this page every message has an `:op`. These are its ops,
;; the following chapters go through them:
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

(defn setup! []
  (ws/init! app :ws
    {:url "/websocket/api/ws"
     :format :transit
     :on-message on-message
     :on-close {:e :ws/closed}}))

;; ## sending
;;
;; `ws/cast!` sends one message and expects nothing back. Its first argument is a `tx-env`
;; or a `rt-ref`, and that decides when it happens:
;;
;; - With a `tx-env` it queues an fx ([fx](/fx/)) and returns the `tx-env`, so it threads
;;   like `sg/queue-fx`. Sending is a side effect, the message goes out after the tx
;;   committed. Several go out in the order they were queued.
;; - With a `rt-ref` it sends right away, for code that isn't a tx. The next chapter does.
;;
;; `ws/call!`, `ws/connect!` and `ws/close!` work the same way.
;;
;; "say hello" sends `:ws/say`, and the tx writes nothing. The server pushes `:ws/said` to
;; every client, this tab included, and that write is the one that shows the message.

(deftx say! [text]
  [tx-env ev e]
  (let [msg {:op :ws/say :text text}]
    (c/log! "send" msg)
    (ws/cast! tx-env :ws msg)))

;; ## incoming messages
;;
;; A reply to a call goes to that call, [see below](#calls). Every other message lands in
;; `:on-message`. Without that option each message starts a tx, and the `:op` is the event
;; id. Each op gets its own `reg-event` ([transactions](/transactions/)), which is what
;; the event log in the devtools then shows. One event with a `case` inside would work
;; too, but every entry in that log would have the same name.
;;
;; This page needs the option for `:ws/ping`, which is answered on the spot. A server can
;; use it to tell a live client from a dead one (this one only counts the answers). It is
;; an op because browsers don't expose protocol level pings. A tx here would change
;; nothing and fill the event log, which is also why the log below doesn't show them.

(defn on-message [rt-ref {:keys [op] :as msg}]
  (if (= :ws/ping op)
    (ws/cast! rt-ref :ws {:op :ws/pong})
    (do (c/log! "received" msg)
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
      (update :ui assoc :client-id client-id :connected? true)
      (ws/cast! :ws {:op :ws/hello :client-info {:tab tab-id}})
      (ws/cast! :ws {:op :ws/request-clients :notify true})))

(defn on-clients [tx-env {:keys [clients]} e]
  (-> tx-env
      (kv/remove-all :client)
      (kv/merge-seq :client clients)))

(defn on-notify [tx-env {:keys [event-op client-id client-info]} e]
  (case event-op
    :client-connect
    (kv/add tx-env :client {:client-id client-id :client-info client-info})

    :client-disconnect
    (assoc-in tx-env [:client client-id :disconnected] true)))

(defn on-said [tx-env {:keys [from text]} e]
  (update-in tx-env [:ui :messages] conj [from text]))

(defn on-closed [tx-env ev e]
  (update tx-env :ui assoc :connected? false :client-id nil))

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
;; A call is a message that wants a reply. `(ws/call! tx-env :ws msg ev)` sends `msg` with
;; a `:call-id` and keeps the event map `ev`, usually from a deftx. The server sends the
;; `:call-id` back with its reply, which then runs `ev` with the reply under
;; `:call-result`. The reply doesn't reach `:on-message`.
;;
;; - Whatever the handler needs from the moment of asking goes into that event map, like
;;   the `client-id` here. The server doesn't have to send it back.
;; - One handler gets every outcome, the reply's `:op` says which one it is. Here
;;   describing a disconnected client gets `:ws/client-not-found`. Losing the connection
;;   is one more outcome, [see below](#losing-the-connection).
;; - "describe" also shows the count of `:ws/pong` the server received, which grows
;;   without a tx ever running for it.

(deftx describe-result! [client-id]
  [tx-env {:keys [call-result]} e]
  (let [{:keys [op pongs said]} call-result]
    (assoc-in tx-env [:client client-id :description]
      (case op
        :ws/client-description (str "pongs: " pongs ", said: " said)
        :ws/client-not-found "the server doesn't know this client"
        ::ws/closed "connection lost, no reply"))))

(deftx describe! [client-id]
  [tx-env ev e]
  (ws/call! tx-env :ws
    {:op :ws/describe-client :client-id client-id}
    (describe-result! client-id)))

(defc ui-describe [{:keys [client-id description]} idx]
  (bind connected? (sg/kv-lookup :ui :connected?))
  (render
    (<< [:div {:class c/$row :data-describe client-id}
         [:button {:class c/$btn :disabled (not connected?) :on-click (describe! client-id)}
          "describe client " client-id]
         [:span description]])))

;; ## losing the connection
;;
;; When the socket closes, `:on-close` runs `:ws/closed`, and the banner reads
;; `:connected?` like any other data. It starts as `true`: the first connect is assumed to
;; work, otherwise the banner would flash on every page load until the socket is up.
;; A connect that fails ends the same way, it just never opened.
;;
;; - Nothing reconnects by itself, when and how often to try is up to the app. "reconnect"
;;   calls `ws/connect!` from a tx, `ws/close!` is the other direction. The `:on-close` tx
;;   could also do it right away, or after a delay with `sg/fx-timeout` ([fx](/fx/)).
;; - A new socket gets a new `:ws/welcome`, and its handler requests everything again.
;;   The server has a new `:client-id` for it, so `on-clients` replaces the table.
;; - A message queued while the socket is connecting is sent once it is open.
;; - A cast without a connection is lost with a warning in the console. The buttons here
;;   are disabled for that reason.
;; - A call without a connection doesn't get lost. Every call that is still waiting when
;;   the socket closes runs its `:on-result` with `{:op ::ws/closed}` as `:call-result`,
;;   and so does a new one while there is no socket. There is no timeout for a server that
;;   stays connected and never replies.
;;
;; `:ws/close-me` below is such a call. The server closes the socket instead of replying.

(deftx close-me-result! []
  [tx-env {:keys [call-result]} e]
  (assoc-in tx-env [:ui :close-result] (:op call-result)))

(deftx close-me! []
  [tx-env ev e]
  (ws/call! tx-env :ws {:op :ws/close-me} (close-me-result!)))

(deftx reconnect! []
  [tx-env ev e]
  (ws/connect! tx-env :ws))

(defn ui-banner []
  (<< [:div#banner {:class (css :p-2 :mb-2 :rounded :bg-red-700 :text-white :font-semibold)}
       "websocket not connected"]))

;; # page

(defc ui-root []
  (bind {:keys [connected? client-id messages close-result]} (sg/kv-lookup :ui))
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
           [:div {:class c/$row}
            [:button {:class c/$btn :disabled (not ready?) :on-click (close-me!)} "server closes this socket"]
            [:button {:class c/$btn :disabled connected? :on-click (reconnect!)} "reconnect"]
            [:span#close-result (when close-result (str "call result: " close-result))]])})))

(defn init []
  (sg/add-kv-table app :ui {} {:connected? true :client-id nil :messages []})
  (sg/add-kv-table app :client {:primary-key :client-id} {})
  (transit/init! app)
  (setup!)
  (register-events!)
  (ws/connect! app :ws)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
