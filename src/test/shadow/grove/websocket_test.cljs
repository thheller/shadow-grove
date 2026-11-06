(ns shadow.grove.websocket-test
  (:require
    [cljs.test :as ct :refer (deftest is use-fixtures)]
    [shadow.grove :as sg]
    [shadow.grove.edn :as edn]
    [shadow.grove.transit :as transit]
    [shadow.grove.websocket :as ws]))

;; node has no location, and its own WebSocket would try to connect. sockets are only driven
;; by the test, every constructed one lands in sockets-ref

(def sockets-ref (atom []))

(deftype FakeSocket [url listeners ^:mutable readyState sent]
  Object
  (addEventListener [this type f]
    (swap! listeners assoc type f))
  (send [this text]
    (swap! sent conj text))
  (close [this]
    (set! readyState 2)))

(defn fake-socket [url]
  (let [socket (FakeSocket. url (atom {}) 0 (atom []))]
    (swap! sockets-ref conj socket)
    socket))

(defn fire! [^js socket type e]
  ((get @(.-listeners socket) type) e))

(defn open! [^js socket]
  (set! (.-readyState socket) 1)
  (fire! socket "open" #js {}))

(defn receive! [socket text]
  (fire! socket "message" #js {:data text}))

(defn closed! [^js socket]
  (set! (.-readyState socket) 3)
  (fire! socket "close" #js {:code 1006 :reason "gone"}))

(defn sent [^js socket]
  @(.-sent socket))

(defn last-socket []
  (peek @sockets-ref))

(use-fixtures :each
  {:before
   (fn []
     (reset! sockets-ref [])
     (set! js/global -self #js {:location #js {:href "https://example.com/app/"}})
     (set! js/global -WebSocket
       (js/Object.assign
         (fn [url] (fake-socket url))
         #js {:CONNECTING 0 :OPEN 1 :CLOSING 2 :CLOSED 3})))})

;; every event lands in events-ref, so tests see what ran as a tx
(defn make-rt [id opts]
  (let [rt-ref (sg/get-runtime id)
        events-ref (atom [])]
    (edn/init! rt-ref {})
    (sg/reg-event rt-ref ::event
      (fn [tx-env ev e]
        (swap! events-ref conj ev)
        tx-env))
    (ws/init! rt-ref :ws (merge {:url "/ws" :format :edn} opts))
    [rt-ref events-ref]))

(defn run-fx!
  "runs the fx handler the way a queued fx would, without the tick in between"
  [rt-ref value]
  ((get-in @rt-ref [::sg/fx-config :ws]) {::sg/runtime-ref rt-ref} value))

(deftest url-follows-the-page
  (let [[rt-ref] (make-rt ::url {})]
    (ws/connect! rt-ref :ws)
    (is (= "wss://example.com/ws" (.-url (last-socket)))))

  (let [[rt-ref] (make-rt ::url-full {:url "ws://other.example.com/x?a=1"})]
    (ws/connect! rt-ref :ws)
    (is (= "ws://other.example.com/x?a=1" (.-url (last-socket))))))

(deftest format-needs-init
  (let [rt-ref (sg/get-runtime ::no-format)]
    (is (thrown-with-msg? js/Error #"needs :read and :write" (ws/init! rt-ref :ws {:url "/ws" :format :transit})))
    (is (thrown-with-msg? js/Error #"needs :read and :write" (ws/init! rt-ref :ws {:url "/ws"})))
    (transit/init! rt-ref)
    (is (ws/init! rt-ref :ws {:url "/ws" :format :transit}))))

(deftest custom-format
  (let [[rt-ref] (make-rt ::custom {:format {:read #(js->clj (js/JSON.parse %) :keywordize-keys true)
                                             :write #(js/JSON.stringify (clj->js %))}
                                    :on-message (fn [rt-ref msg] (sg/run-tx! rt-ref (assoc msg :e ::event)))})]
    (ws/connect! rt-ref :ws)
    (open! (last-socket))
    (ws/cast! rt-ref :ws {:a 1})
    (is (= ["{\"a\":1}"] (sent (last-socket))))))

(deftest cast-before-open-is-sent-on-open
  (let [[rt-ref] (make-rt ::queue {})]
    (ws/connect! rt-ref :ws)
    (ws/cast! rt-ref :ws {:n 1})
    (run-fx! rt-ref {:cast {:n 2}})
    (is (= [] (sent (last-socket))))

    (open! (last-socket))
    (is (= ["{:n 1}" "{:n 2}"] (sent (last-socket))))

    (ws/cast! rt-ref :ws {:n 3})
    (is (= ["{:n 1}" "{:n 2}" "{:n 3}"] (sent (last-socket))))))

(deftest messages-start-txs-by-op
  (let [[rt-ref events-ref] (make-rt ::by-op {})]
    (ws/connect! rt-ref :ws)
    (open! (last-socket))
    (receive! (last-socket) (pr-str {:op ::event :a 1}))
    (is (= [{:op ::event :e ::event :a 1}] @events-ref))))

(deftest on-message-replaces-the-default
  (let [seen-ref (atom [])
        [rt-ref events-ref] (make-rt ::custom-msg {:on-message (fn [rt-ref msg] (swap! seen-ref conj msg))})]
    (ws/connect! rt-ref :ws)
    (open! (last-socket))
    (receive! (last-socket) (pr-str {:op ::event}))
    (is (= [{:op ::event}] @seen-ref))
    (is (= [] @events-ref))))

(deftest call-gets-its-reply
  (let [[rt-ref events-ref] (make-rt ::call {})]
    (ws/connect! rt-ref :ws)
    (open! (last-socket))

    (run-fx! rt-ref {:call {:op :foo} :on-result {:e ::event :ctx 1}})
    (ws/call! rt-ref :ws {:op :bar} {:e ::event :ctx 2})
    (is (= [(pr-str {:op :foo :call-id 1})
            (pr-str {:op :bar :call-id 2})]
           (sent (last-socket))))

    ;; answers out of order, each goes to its own call
    (receive! (last-socket) (pr-str {:op :reply :call-id 2}))
    (receive! (last-socket) (pr-str {:op :reply :call-id 1}))
    (is (= [{:e ::event :ctx 2 :call-result {:op :reply :call-id 2}}
            {:e ::event :ctx 1 :call-result {:op :reply :call-id 1}}]
           @events-ref))))

(deftest unknown-call-id-is-a-message
  (let [seen-ref (atom [])
        [rt-ref] (make-rt ::server-call {:on-message (fn [rt-ref msg] (swap! seen-ref conj msg))})]
    (ws/connect! rt-ref :ws)
    (open! (last-socket))
    (receive! (last-socket) (pr-str {:op :server-call :call-id "x"}))
    (is (= [{:op :server-call :call-id "x"}] @seen-ref))))

(deftest close-fails-waiting-calls-then-runs-on-close
  (let [[rt-ref events-ref] (make-rt ::close {:on-close {:e ::event :closed true}})
        socket (do (ws/connect! rt-ref :ws) (last-socket))]
    (open! socket)
    (ws/call! rt-ref :ws {:op :foo} {:e ::event :ctx 1})
    (ws/call! rt-ref :ws {:op :foo} {:e ::event :ctx 2})
    (closed! socket)

    (is (= [{:e ::event :ctx 1 :call-result {:op ::ws/closed}}
            {:e ::event :ctx 2 :call-result {:op ::ws/closed}}
            {:e ::event :closed true :code 1006 :reason "gone"}]
           @events-ref))))

(deftest failed-connect-drops-the-queue-and-fails-its-calls
  (let [[rt-ref events-ref] (make-rt ::failed {})
        socket (do (ws/connect! rt-ref :ws) (last-socket))]
    (ws/cast! rt-ref :ws {:n 1})
    (ws/call! rt-ref :ws {:op :foo} {:e ::event})
    (closed! socket)
    (is (= [{:e ::event :call-result {:op ::ws/closed}}] @events-ref))

    (ws/connect! rt-ref :ws)
    (open! (last-socket))
    (is (= [] (sent (last-socket))))))

(deftest call-without-connection-fails-right-away
  (let [[rt-ref events-ref] (make-rt ::no-conn {})]
    (ws/call! rt-ref :ws {:op :foo} {:e ::event})
    (is (= [{:e ::event :call-result {:op ::ws/closed}}] @events-ref))))

(deftest connect-only-without-a-socket
  (let [[rt-ref] (make-rt ::connect {})]
    (run-fx! rt-ref {:connect true})
    (let [socket (last-socket)]
      (run-fx! rt-ref {:connect true})
      (is (= 1 (count @sockets-ref)))
      (open! socket)
      (ws/connect! rt-ref :ws)
      (is (= 1 (count @sockets-ref)))

      (closed! socket)
      (ws/connect! rt-ref :ws)
      (is (= 2 (count @sockets-ref))))))

(deftest close-goes-through-on-close
  (let [[rt-ref events-ref] (make-rt ::closing {:on-close {:e ::event}})
        socket (do (ws/connect! rt-ref :ws) (last-socket))]
    (open! socket)
    (run-fx! rt-ref {:close true})
    (is (= 2 (.-readyState socket)))
    (closed! socket)
    (is (= [{:e ::event :code 1006 :reason "gone"}] @events-ref))))

(deftest init-keeps-the-connection
  (let [[rt-ref events-ref] (make-rt ::reinit {})
        socket (do (ws/connect! rt-ref :ws) (last-socket))]
    (open! socket)
    (ws/call! rt-ref :ws {:op :foo} {:e ::event})

    (ws/init! rt-ref :ws {:url "/ws" :format :edn})
    (ws/cast! rt-ref :ws {:n 1})
    (is (= 2 (count (sent socket))))

    (receive! socket (pr-str {:call-id 1}))
    (is (= 1 (count @events-ref)))))

(deftest bad-fx-value
  (let [[rt-ref] (make-rt ::bad-fx {})]
    (is (thrown-with-msg? js/Error #"websocket fx needs" (run-fx! rt-ref {:send {:op :foo}})))))

(deftest unknown-id
  (is (thrown-with-msg? js/Error #"unknown websocket" (ws/cast! (sg/get-runtime ::unknown) :nope {}))))
