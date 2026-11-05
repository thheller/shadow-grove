(ns examples.http-fx
  "`shadow.grove.http-fx` is an fx handler around `XMLHttpRequest`. A transaction queues
   a request like any other fx ([fx](/fx/)), and the response comes back as a new
   transaction. It is the tool for **user actions**: login, save, publish. Data a screen
   needs before it can render is a pull instead ([http-pull](/http-pull/)).

   The server side of this example is `src/dev/examples/server.clj`, it adds some latency
   so you can see what is in flight."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.edn :as edn]
    [shadow.grove.http-fx :as http-fx]
    [shadow.grove.transit :as transit]))

(defonce app (sg/get-runtime :app))

(declare request-error!)

;; ## setup
;;
;; - Response parsing isn't built in. For an `application/edn` body http-fx calls the
;;   reader the runtime holds, and throws if there is none. `(edn/init! app {})`
;;   installs it (`shadow.grove.edn`), `(transit/init! app)` the transit one. Do it during
;;   init, before any request. They set `::sg/edn-read` / `::sg/edn-str` and
;;   `::sg/transit-read` on the runtime; setting those yourself is the same thing, if
;;   something else already owns the reader.
;; - `(http-fx/make-handler config)` returns the fx handler, register it under any id
;;   with `reg-fx`. The id is yours, there is no built-in `:http`. Don't hand-roll one.
;; - The config: `:base-url` (prepended to every `:uri`, which is relative to it, not to
;;   the history path prefix), `:request-format` (`:edn`, `:transit` or a fn) and a shared
;;   `:on-error`. A request with a `:body` needs a request format, else it throws "no
;;   request-format configured but :body provided".
;; - Pick one transport per app. This one registers a second handler for transit only to
;;   show it.

(defn setup! []
  (edn/init! app {})
  (transit/init! app)

  (sg/reg-fx app :api
    (http-fx/make-handler
      {:base-url "/http-fx/api"
       :request-format :edn
       :on-error (request-error!)}))

  (sg/reg-fx app :api-transit
    (http-fx/make-handler
      {:base-url "/http-fx/api"
       :request-format :transit
       :on-error (request-error!)})))

;; ## requests and results
;;
;; Queue `{:request ... :on-success ev}` under the handler's fx id. The fx runs after
;; commit, the result never comes back into this `tx-env`.
;;
;; `:request` is one of:
;;
;; - a string: GET that path.
;; - a map: `{:uri "/items" :method :post :body data}`. A `:body` without `:method` is a
;;   POST, no body a GET. `:uri` defaults to `""`, the handler's base url.
;; - a vector: `[:GET uri body opts]`, the low-level form. A vector uri is joined with
;;   `/`.
;;
;; `:on-success` is an event map (a deftx call), transacted as
;; `(assoc on-success :result body)`. Read the body from `ev`, not from a closed-over
;; atom. A status of 400 or more is an error: `:on-error` from the request, else the
;; handler's, is transacted with `:result`, `:status` and `:sent-request` added. With no
;; error handler at all there is a `console.warn` and no tx.
;;
;; The create form is **pessimistic**: the item is written in the success tx, so a
;; rejected request (labels starting with `!`) has nothing to undo.

(deftx items-loaded! []
  [tx-env {:keys [result]} e]
  (assoc-in tx-env [:ui :items] result))

(deftx request-error! []
  [tx-env {:keys [status result sent-request]} e]
  (c/log! "request-error!" status (:uri sent-request))
  (assoc-in tx-env [:ui :error] (str status ": " (:error result))))

(deftx load-items! []
  [tx-env ev e]
  (sg/queue-fx tx-env :api {:request "/items" :on-success (items-loaded!)}))

(deftx item-created! []
  [tx-env {:keys [result]} e]
  (-> tx-env
      (update-in [:ui :items] conj result)
      (assoc-in [:ui :error] nil)))

(deftx create-item! []
  {:e/prevent-default true}
  [tx-env ev e]
  (let [form (.-target e)
        label (.-value (.. form -elements -label))]
    (.reset form)
    (sg/queue-fx tx-env :api
      {:request {:uri "/items" :body {:label label}}
       :on-success (item-created!)})))

(deftx status! [code]
  [tx-env ev e]
  (sg/queue-fx tx-env :api {:request [:GET ["/status" (str code)]] :on-success (items-loaded!)}))

;; ## optimistic writes
;;
;; Write in the tx that queues the request, so the screen updates before the response.
;; The success tx then confirms (here it swaps in the server's copy). If the server
;; rejects it, the optimistic entry has to go again: this request's own `:on-error`
;; replaces the handler's and rolls back. When you don't want a lie on screen, write only
;; in the success tx, like the form above.
;;
;; The optimistic entry and the server's answer must agree on an identity. This example
;; matches by label. A real app allocates a client id, sends it along, and has the server
;; echo it, so the success handler knows which entry to reconcile.

(deftx confirmed! [label]
  [tx-env {:keys [result]} e]
  (update-in tx-env [:ui :items]
    (fn [items] (mapv #(if (= label (:label %)) result %) items))))

(deftx rolled-back! [label]
  [tx-env {:keys [result]} e]
  (-> tx-env
      (update-in [:ui :items] (fn [items] (vec (remove #(= label (:label %)) items))))
      (assoc-in [:ui :error] (str "rolled back: " (:error result)))))

(deftx create-optimistic! [label]
  [tx-env ev e]
  (-> tx-env
      (update-in [:ui :items] conj {:id "pending" :label label})
      (sg/queue-fx :api
        {:request {:uri "/items" :body {:label label}}
         :on-success (confirmed! label)
         :on-error (rolled-back! label)})))

;; ## :trace
;;
;; `:trace id` on the request records its progress in an atom, not in KV.
;; `(http-fx/trace-state id)` reads it in a bind (it is a `sg/watch`, so it claims the
;; bind): `:status` is `:started`, `:completed` or `:error`, plus `:duration`. Here it
;; disables the button while the request runs.

(deftx slow-done! []
  [tx-env {:keys [result]} e]
  (assoc-in tx-env [:ui :slow] result))

(deftx slow! []
  [tx-env ev e]
  (sg/queue-fx tx-env :api {:request {:uri "/slow" :trace ::slow} :on-success (slow-done!)}))

(defc ui-slow []
  (bind req (http-fx/trace-state ::slow))
  (render
    (<< [:div {:class c/$row}
         [:button#slow {:class c/$btn :on-click (slow!) :disabled (= :started (:status req))}
          (if (= :started (:status req)) "running ..." "slow request (1.5s)")]
         [:span#slow-trace {:class c/$muted} (pr-str (select-keys req [:status :duration]))]])))

;; ## :timeout
;;
;; `:timeout ms` on the request map aborts it after `ms`. Known issue: currently a
;; timeout only logs to the console. Neither `:on-error` nor `:on-success` runs, and the
;; trace stays `:started`, so the button below never re-enables. Don't build on
;; `:timeout` yet.

(deftx slow-with-timeout! []
  [tx-env ev e]
  (sg/queue-fx tx-env :api {:request {:uri "/slow" :timeout 500 :trace ::slow-timeout} :on-success (slow-done!)}))

(defc ui-slow-timeout []
  (bind req (http-fx/trace-state ::slow-timeout))
  (render
    (<< [:div {:class c/$row}
         [:button {:class c/$btn :on-click (slow-with-timeout!)} "same with :timeout 500"]
         [:span#timeout-trace {:class c/$muted} (pr-str (select-keys req [:status :duration]))]])))

;; ## :request-many
;;
;; `:request-many` instead of `:request`: a map of key to request. The success `:result`
;; is the map of bodies. Don't pass both.
;;
;; Several `queue-fx` calls in one handler are fine too, e.g. one save writing two
;; different endpoints.

(deftx many-done! []
  [tx-env {:keys [result]} e]
  (assoc-in tx-env [:ui :many] result))

(deftx many! []
  [tx-env ev e]
  (sg/queue-fx tx-env :api {:request-many {:items "/items" :count "/count"} :on-success (many-done!)}))

;; ## transit
;;
;; The mirror image of EDN: `(transit/init! app)` and a handler with
;; `:request-format :transit`. Keywords, sets, dates and UUIDs survive the roundtrip.
;; `:with-credentials` defaults to true, set it false on the request or the handler
;; config to disable.

(deftx transit-done! []
  [tx-env {:keys [result]} e]
  (assoc-in tx-env [:ui :transit] result))

(deftx transit! []
  [tx-env ev e]
  (sg/queue-fx tx-env :api-transit
    {:request {:uri "/transit" :body {:kw :a-keyword :set #{1 2} :date (js/Date. 0) :uuid #uuid "00000000-0000-0000-0000-000000000001"}}
     :on-success (transit-done!)}))

;; # page

(defn error-line [error]
  (when error
    (<< [:div {:class (css :p-2 :mb-2 :rounded :bg-red-50 :text-red-800) :data-error ""} error])))

(defc ui-root []
  (bind {:keys [items error slow many transit]} (sg/kv-lookup :ui))
  (render
    (c/page "http-fx"
      {"requests and results"
       (<< (error-line error)
           [:div {:class c/$row}
            [:button {:class c/$btn :on-click (load-items!)} "load items"]
            [:button {:class c/$btn :on-click (status! 404)} "GET /status/404"]]
           [:form {:class [c/$row (css :mt-2)] :on-submit (create-item!)}
            [:input {:class c/$input :name "label" :placeholder "label, ! makes the server reject it"}]
            [:button {:class c/$btn :type "submit"} "create"]]
           [:div {:class (css :mt-2) :data-items ""} "items: " (pr-str (mapv :label items))])

       "optimistic writes"
       (<< (error-line error)
           [:div {:class c/$row}
            [:button {:class c/$btn :on-click (create-optimistic! "optimistic")} "add optimistically"]
            [:button {:class c/$btn :on-click (create-optimistic! "!optimistic")} "add optimistically (server rejects)"]]
           [:div {:class (css :mt-2) :data-items ""} "items: " (pr-str (mapv :label items))])

       ":trace"
       (<< (ui-slow)
           [:div#slow-result {:class c/$muted} "result: " (pr-str slow)])

       ":timeout"
       (ui-slow-timeout)

       ":request-many"
       (<< [:button {:class c/$btn :on-click (many!)} "items + count"]
           [:div#many {:class c/$muted} (pr-str many)])

       "transit"
       (<< [:button {:class c/$btn :on-click (transit!)} "transit roundtrip"]
           [:pre#transit {:class (css :text-xs :whitespace-pre-wrap)} (pr-str transit)])})))

(defn init []
  (sg/add-kv-table app :ui {} {:items []})
  (setup!)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
