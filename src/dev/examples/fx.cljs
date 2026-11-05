(ns examples.fx
  "Side effects don't happen in tx handlers. A handler queues them with
   `(sg/queue-fx tx-env fx-id value)`, and they run after the tx committed and the
   render happened. That keeps handlers pure and testable: the queued fx are just data
   under `::sg/fx` in the returned env ([transactions](/transactions/)). HTTP requests
   are fx too ([http-fx](/http-fx/))."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; ## order: tx, render, fx, follow-up tx
;;
;; - `(sg/reg-fx rt-ref fx-id (fn [fx-env value] ...))` registers the handler during
;;   init. The return value is ignored.
;; - `queue-fx` appends `[fx-id value]` to `tx-env`. The fx runs after commit, on the next
;;   tick, and can't affect the current render.
;; - Results go back into state as a new tx: `((:transact! fx-env) ev-map)` or
;;   `(sg/run-tx! app ev-map)`.
;;
;; The log shows the order: the tx, the render with the new `n`, the fx, and 200ms later
;; the follow-up tx and its render.

(deftx fx-done! [result]
  [tx-env ev e]
  (c/log! "tx fx-done! got" (pr-str result))
  (assoc-in tx-env [:ui :fx-result] result))

(deftx with-fx! []
  [tx-env ev e]
  (c/log! "tx with-fx! ran, queueing ::work")
  (-> tx-env
      (update-in [:ui :n] inc)
      (sg/queue-fx ::work {:at (c/time-str)})))

(defn register-work! []
  (sg/reg-fx app ::work
    (fn [{:keys [transact!] :as fx-env} value]
      (c/log! "fx ::work ran with" (pr-str value))
      (js/setTimeout #(transact! (fx-done! (assoc value :done (c/time-str)))) 200))))

;; ## fx-timeout and fx-timeout-clear
;;
;; Built in: `(sg/fx-timeout tx-env id ms ev)` runs the event map `ev` as a tx after `ms`.
;; Another `fx-timeout` with the same id replaces a pending one, and
;; `(sg/fx-timeout-clear tx-env id)` cancels it. The other built-in fx is `:ui/redirect!`,
;; registered by `history/init!` ([routing](/routing/)).

(deftx notice-hide! []
  [tx-env ev e]
  (c/log! "tx notice-hide! ran")
  (assoc-in tx-env [:ui :notice] nil))

(deftx notice-show! []
  [tx-env ev e]
  (-> tx-env
      (assoc-in [:ui :notice] "this notice hides itself after 2s")
      (sg/fx-timeout ::notice 2000 (notice-hide!))))

(deftx notice-keep! []
  [tx-env ev e]
  (sg/fx-timeout-clear tx-env ::notice))

;; ## fx that need the DOM
;;
;; Fx run after the render, so an fx can focus an element the same tx made appear. Side
;; effects that aren't KV writes (focus, `localStorage`, scrolling) go here instead of the
;; handler body. A component that owns the element can also do it in an effect
;; ([effects](/effects/)).

(deftx focus-later! []
  [tx-env ev e]
  (-> tx-env
      (assoc-in [:ui :show-input?] true)
      (sg/queue-fx ::focus "#late-input")))

(defn register-focus! []
  (sg/reg-fx app ::focus
    (fn [fx-env selector]
      (when-some [el (js/document.querySelector selector)]
        (.focus el)))))

;; ## unknown fx
;;
;; An fx id without `reg-fx` throws "unknown fx" when the fx runs, after the tx already
;; committed.

(deftx unknown-fx! []
  [tx-env ev e]
  (sg/queue-fx tx-env ::not-registered true))

;; # page

(defc ui-root []
  (bind {:keys [n fx-result notice show-input?]} (sg/kv-lookup :ui))

  (render
    (c/log! "render, n =" n)
    (c/page "fx"
      {"order: tx, render, fx, follow-up tx"
       [:div {:class c/$row}
        [:button {:class c/$btn :on-click (with-fx!)} "run tx with fx"]
        "n = " n ", fx result: " (pr-str fx-result)]

       "fx-timeout and fx-timeout-clear"
       (<< [:div {:class c/$row}
            [:button {:class c/$btn :on-click (notice-show!)} "show notice"]
            [:button {:class c/$btn :on-click (notice-keep!)} "keep it"]]
           (when notice
             (<< [:div#notice {:class (css :mt-2 :p-2 :bg-yellow-50 :rounded)} notice])))

       "fx that need the DOM"
       (<< [:button {:class c/$btn :on-click (focus-later!)} "show and focus an input"]
           (when show-input?
             (<< [:input#late-input {:class [c/$input (css :ml-2)] :placeholder "focused by an fx"}])))

       "unknown fx"
       [:button {:class c/$btn :on-click (unknown-fx!)} "queue an unregistered fx"]})))

(defn init []
  (sg/add-kv-table app :ui {} {:n 0})
  (register-work!)
  (register-focus!)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
