(ns shadow.grove.websocket
  "a websocket that lives on the runtime. what comes in starts a tx, what goes out is an fx,
   so tx handlers never touch the socket. see the websocket example in src/dev/examples

   (transit/init! rt-ref)
   (ws/init! rt-ref :ws {:url \"/api/ws\" :format :transit})
   (ws/connect! rt-ref :ws)

   the id is yours, it names this connection. cast!, call!, connect! and close! all take a
   tx-env or a rt-ref as their first arg. a tx-env queues an fx and returns the tx-env, so
   they thread like sg/queue-fx. anything else runs right away.

   (-> tx-env
       (ws/cast! :ws {:op :hello})
       (ws/call! :ws {:op :load} (loaded!)))

   (ws/cast! rt-ref :ws {:op :pong})

   messages are maps. the only key this ns adds or looks at is :call-id, the rest is up to
   the app and its server. a server answers a call by sending the :call-id back in its reply."
  (:require
    [clojure.string :as str]
    [shadow.grove :as sg]))

;; an fx-env is the tx-env of a finished tx with :transact! added. queueing on it would go nowhere
(defn- tx-env? [env-or-ref]
  (and (map? env-or-ref)
       (some? (::sg/fx env-or-ref))
       (not (:transact! env-or-ref))))

(defn- get-rt-ref [env-or-ref]
  (sg/get-runtime
    (if (map? env-or-ref)
      (::sg/runtime-ref env-or-ref)
      env-or-ref)))

;; taking the read and write fns from the runtime, so this ns doesn't depend on either
;; cljs.reader or transit. only the app requires the one it actually uses
(defn- format-fns [rt-ref format]
  (let [rt @rt-ref

        {:keys [read write] :as fns}
        (case format
          :transit {:read (::sg/transit-read rt) :write (::sg/transit-str rt)}
          :edn {:read (::sg/edn-read rt) :write (::sg/edn-str rt)}
          format)]

    (when-not (and (fn? read) (fn? write))
      (throw (ex-info
               "websocket :format needs :read and :write fns, :transit and :edn need transit/init! or edn/init! first"
               {:format format})))

    fns))

(defn- ws-url
  "/api/ws and http urls become ws urls for the current page"
  [url]
  (let [url (js/URL. url js/self.location.href)]
    (set! url -protocol (str/replace (.-protocol url) "http" "ws"))
    (.-href url)))

(defn- get-conn [rt-ref ws-id]
  (or (get-in @rt-ref [::connections ws-id])
      (throw (ex-info "unknown websocket, call init! first" {:ws-id ws-id}))))

(defn- swap-conn! [rt-ref ws-id update-fn & args]
  (swap! rt-ref update-in [::connections ws-id] #(apply update-fn % args)))

(defn- call-result! [rt-ref on-result reply]
  (sg/run-tx! rt-ref (assoc on-result :call-result reply)))

;; returns true if the message is on its way, false if the socket can't take it
;; a closed socket doesn't fail, the message would just be lost. a connecting one would throw
(defn- send! [rt-ref ws-id msg]
  (let [{:keys [^js socket write]} (get-conn rt-ref ws-id)
        ready-state (some-> socket (.-readyState))]
    (cond
      (= ready-state js/WebSocket.OPEN)
      (do (.send socket (write msg))
          true)

      (= ready-state js/WebSocket.CONNECTING)
      (do (swap-conn! rt-ref ws-id update :queue conj (write msg))
          true)

      :else
      false)))

(defn cast!
  "sends msg and expects nothing back. takes a tx-env or rt-ref, see the ns docstring.

   sent once the socket is open if it is still connecting.
   lost with a warning if there is no connection."
  [env-or-ref ws-id msg]
  (if (tx-env? env-or-ref)
    (sg/queue-fx env-or-ref ::fx [:cast ws-id msg])
    (when-not (send! (get-rt-ref env-or-ref) ws-id msg)
      (js/console.warn "websocket not connected, message lost" ws-id msg))))

(defn call!
  "sends msg with a :call-id. runs the event map on-result as a tx when the reply with that
   :call-id arrives, the reply is added to it as :call-result. takes a tx-env or rt-ref, see
   the ns docstring.

   without a connection, or when it closes before the reply, on-result still runs, with
   {:op ::closed} as :call-result. there is no timeout, a call stays waiting for as long as
   the connection is open and the server doesn't reply."
  [env-or-ref ws-id msg on-result]
  {:pre [(map? msg)
         (map? on-result)
         (keyword? (:e on-result))]}
  (if (tx-env? env-or-ref)
    (sg/queue-fx env-or-ref ::fx [:call ws-id msg on-result])
    (let [rt-ref (get-rt-ref env-or-ref)
          call-id (-> (swap-conn! rt-ref ws-id update :call-id-seq inc)
                      (get-in [::connections ws-id :call-id-seq]))]

      (if (send! rt-ref ws-id (assoc msg :call-id call-id))
        (swap-conn! rt-ref ws-id assoc-in [:calls call-id] on-result)
        (call-result! rt-ref on-result {:op ::closed})))))

(defn- handle-open [rt-ref ws-id]
  (let [{:keys [^js socket queue on-open]} (get-conn rt-ref ws-id)]
    (swap-conn! rt-ref ws-id assoc :queue [])

    (doseq [text queue]
      (.send socket text))

    (when on-open
      (sg/run-tx! rt-ref on-open))))

;; a :call-id that isn't ours is not a reply. it might be the server calling this client
(defn- handle-message [rt-ref ws-id data]
  (let [{:keys [read calls on-message]} (get-conn rt-ref ws-id)
        {:keys [call-id] :as msg} (read data)]

    (if-some [on-result (get calls call-id)]
      (do (swap-conn! rt-ref ws-id update :calls dissoc call-id)
          (call-result! rt-ref on-result msg))
      (on-message rt-ref msg))))

;; browsers follow a failed connect with close, so this is also the only thing to handle that.
;; the error event in between carries no information
(defn- handle-close [rt-ref ws-id ^js e]
  (let [{:keys [calls on-close]} (get-conn rt-ref ws-id)]
    ;; first, so that all handlers below can already connect! again
    (swap-conn! rt-ref ws-id assoc :socket nil :queue [] :calls {})

    (doseq [[_ on-result] (sort-by key calls)]
      (call-result! rt-ref on-result {:op ::closed}))

    (when on-close
      (sg/run-tx! rt-ref (assoc on-close :code (.-code e) :reason (.-reason e))))))

(defn connect!
  "opens the socket. does nothing if there is one already, connected or not. to connect again
   use :on-close to tell when the previous socket is gone. takes a tx-env or rt-ref, see the
   ns docstring."
  [env-or-ref ws-id]
  (if (tx-env? env-or-ref)
    (sg/queue-fx env-or-ref ::fx [:connect ws-id])
    (let [rt-ref (get-rt-ref env-or-ref)
          {:keys [url socket]} (get-conn rt-ref ws-id)]
      (when-not socket
        (let [^js socket (js/WebSocket. (ws-url url))]
          (swap-conn! rt-ref ws-id assoc :socket socket)

          (.addEventListener socket "open"
            (fn [e]
              (handle-open rt-ref ws-id)))

          (.addEventListener socket "message"
            (fn [^js e]
              (handle-message rt-ref ws-id (.-data e))))

          (.addEventListener socket "close"
            (fn [e]
              (handle-close rt-ref ws-id e))))))))

(defn close!
  "closes the socket, :on-close runs like for any other close. takes a tx-env or rt-ref, see
   the ns docstring."
  [env-or-ref ws-id]
  (if (tx-env? env-or-ref)
    (sg/queue-fx env-or-ref ::fx [:close ws-id])
    (let [{:keys [^js socket]} (get-conn (get-rt-ref env-or-ref) ws-id)]
      (when socket
        (.close socket)))))

(defn- fx-handler [{::sg/keys [runtime-ref]} [action ws-id msg on-result]]
  (case action
    :cast (cast! runtime-ref ws-id msg)
    :call (call! runtime-ref ws-id msg on-result)
    :connect (connect! runtime-ref ws-id)
    :close (close! runtime-ref ws-id)))

(defn- run-op-tx! [rt-ref {:keys [op] :as msg}]
  (sg/run-tx! rt-ref (assoc msg :e op)))

(defn init!
  "adds the connection ws-id to the runtime. connect! opens the socket. call this during
   init, after transit/init! or edn/init!.

   opts:
   :url         relative to the page, or a full url
   :format      :transit or :edn use the fns transit/init! and edn/init! put on the runtime,
                {:read (fn [string]) :write (fn [msg])} for anything else
   :on-message  (fn [rt-ref msg]) gets every message that isn't a reply to a call
                default starts a tx with the :op of the message as its event id
   :on-open     event map, runs as a tx when the socket opens
   :on-close    event map, runs as a tx when the socket closes or fails to connect,
                with :code and :reason added"
  [rt-ref ws-id {:keys [url format on-message] :as opts}]
  {:pre [(keyword? ws-id)
         (string? url)]}
  (let [{:keys [read write]} (format-fns rt-ref format)]

    (swap-conn! rt-ref ws-id
      (fn [conn]
        ;; keeps the socket and calls, so this can run again after a hot-reload
        (merge
          {:queue []
           :calls {}
           :call-id-seq 0}
          conn
          (dissoc opts :format)
          {:read read
           :write write
           :on-message (or on-message run-op-tx!)})))

    (sg/reg-fx rt-ref ::fx fx-handler)

    rt-ref))
