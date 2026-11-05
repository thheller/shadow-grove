(ns examples.external-events
  "Data that arrives from outside any component: other tabs, a server push (SSE, a
   websocket), window events, timers, the response of an async API. The callback never
   holds on to a `tx-env` or touches tables. It captures what it needs (the runtime, ids,
   the payload) and starts a new transaction with `(sg/run-tx! rt-ref ev-map)`.

   `e` is nil in those handlers, there is no DOM event ([transactions](/transactions/)).
   For data the UI must wait for, see [loading-sentinel](/loading-sentinel/)."
  (:require
    [cljs.reader :as reader]
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; ## other tabs
;;
;; A `BroadcastChannel` between open tabs of this page stands in for a server push.
;; Sending is a side effect, so the tx queues an fx ([fx](/fx/)). Receiving is a listener
;; installed at init that starts a tx per message. Open the page in a second tab and send
;; from either one.

(defonce channel (js/BroadcastChannel. "grove-examples-external-events"))

(defonce tab-id (subs (str (random-uuid)) 0 4))

(deftx message-received! [msg]
  [tx-env ev e]
  (update-in tx-env [:ui :messages] conj msg))

(deftx send! []
  [tx-env ev e]
  (let [msg {:from tab-id :text (str "hello " (count (get-in tx-env [:ui :sent])))}]
    (-> tx-env
        (update-in [:ui :sent] conj msg)
        (sg/queue-fx ::broadcast msg))))

(defn setup-channel! []
  (sg/reg-fx app ::broadcast
    (fn [fx-env msg]
      (.postMessage channel (pr-str msg))))

  (set! (.-onmessage channel)
    (fn [^js e]
      (sg/run-tx! app (message-received! (reader/read-string (.-data e)))))))

;; ## window events and timers
;;
;; The same shape for anything else with a callback.

(deftx window-resized! [width]
  [tx-env ev e]
  (assoc-in tx-env [:ui :width] width))

(deftx tick! []
  [tx-env ev e]
  (update-in tx-env [:ui :ticks] inc))

(defn setup-listeners! []
  (js/window.addEventListener "resize"
    (fn [e]
      (sg/run-tx! app (window-resized! js/window.innerWidth))))

  (js/setInterval #(sg/run-tx! app (tick!)) 1000))

;; # page

(defc ui-root []
  (bind {:keys [messages sent width ticks]} (sg/kv-lookup :ui))
  (render
    (c/page "external events"
      {"other tabs"
       (<< (c/note (str "This tab is " tab-id "."))
           [:button {:class c/$btn :on-click (send!)} "send"]
           [:div#sent {:class c/$muted} "sent: " (pr-str (mapv :text sent))]
           [:div#received "received: " (pr-str (mapv (juxt :from :text) messages))])

       "window events and timers"
       (<< [:div#width "window width (resize listener → run-tx!): " width]
           [:div#ticks "ticks (setInterval → run-tx!): " ticks])})))

(defn init []
  (sg/add-kv-table app :ui {} {:messages [] :sent [] :width js/window.innerWidth :ticks 0})
  (setup-channel!)
  (setup-listeners!)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
