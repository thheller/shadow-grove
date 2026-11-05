(ns examples.transactions
  "One pipeline handles everything that changes KV data:

   ```
   markup (:on-click EV-MAP)
     -> the owning component's event slot, if it has one for :e
     -> else parent components, then the root
     -> tx handler (tx-env ev e) -> tx-env'
     -> commit -> re-render what read changed data -> queued fx run
   ```

   An event map reaches the root (or `sg/run-tx!` starts one from outside), the runtime
   finds its handler, the handler gets `tx-env` (every KV table plus framework keys) and
   returns it updated. The runtime commits, re-renders invalidated components, then runs
   queued side effects ([fx](/fx/)). Slots on the way: [component-events](/component-events/).
   The handlers are plain functions, `examples.transactions-test` runs them without a
   runtime or DOM."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; ## deftx: event maps are data
;;
;; ```
;; (deftx add! [n]       ;; constructor args
;;   [tx-env ev e]       ;; handler args
;;   (update-in tx-env [:counter :value] + n))
;; ```
;;
;; - Calling the constructor, `(add! 5)`, builds a plain event map
;;   `{:e :examples.transactions/add! :args [5]}`. `:e` is namespaced to where the deftx
;;   is defined. The handler rides in the map's metadata (`::sg/tx`), so no registration
;;   is needed: `[:button {:on-click (add! 5)}]`.
;; - The handler gets `tx-env`, the event map `ev`, and the DOM event `e` (nil when the tx
;;   didn't come from the DOM). The constructor args are in scope too.
;; - Destructuring in the constructor args is delayed until the handler runs.
;; - An optional base map between the two vectors adds static keys to every map the
;;   constructor builds, e.g. `:e/*` listener options ([dom-events](/dom-events/)) or
;;   data the handler reads from `ev`.
;; - The body updates `tx-env` with plain map functions or the `kv/*` helpers
;;   ([kv-writes](/kv-writes/)) and returns it.

(deftx add! [n]
  [tx-env ev e]
  (update-in tx-env [:counter :value] + n))

(deftx add-tagged! [n]
  {:source :tagged-button}
  [tx-env {:keys [source] :as ev} e]
  (c/log! "add-tagged! sees :source" source)
  (update-in tx-env [:counter :value] + n))

(deftx add-point! [{:keys [x y]}]
  [tx-env ev e]
  (update-in tx-env [:counter :value] + x y))

(defn event-maps []
  (<< [:pre#event-map {:class (css :text-xs :bg-gray-50 :p-2 :rounded :whitespace-pre-wrap)}
       (str "(add! 5)              => " (pr-str (add! 5)) "\n"
            "(add-tagged! 1)       => " (pr-str (add-tagged! 1)) "\n"
            "(meta (add! 5))       => " (pr-str (keys (meta (add! 5)))))]))

;; ## chaining with sg/run
;;
;; `(sg/run tx-env ev)` runs another deftx map synchronously inside the current handler,
;; threading `tx-env` through, and records it under `::sg/chain` for the devtools.

(deftx add-twice! [n]
  [tx-env ev e]
  (-> tx-env
      (sg/run (add! n))
      (sg/run (add! n))
      (as-> env (do (c/log! "chain:" (pr-str (mapv :e (::sg/chain env)))) env))))

;; ## reg-event
;;
;; For events without a natural constructor: routing (`:ui/route!`, [routing](/routing/)),
;; messages from a server push, boot data. Any namespace can register on the shared
;; runtime, during init. The handler shape is the same `(fn [tx-env ev e])`, and the
;; markup carries a plain map, `{:e ::reset! :to 100}`. deftx maps and reg-event maps are
;; interchangeable: both are `{:e keyword ...}`, deftx just brings its fn along.

(defn reset-counter [tx-env {:keys [to] :as ev} e]
  (assoc-in tx-env [:counter :value] (or to 0)))

(defn register-events! []
  (sg/reg-event app ::reset! reset-counter))

;; ## handler results
;;
;; A handler must return the `tx-env` it got, updated. Returning it unchanged, or nil, is
;; a legal no-op: nothing commits, nothing renders. Returning some other map is an error,
;; the runtime logs it and nothing commits. The classic bug is a last expression that
;; isn't the env: a log call after the update returns nil, and the change is dropped.

(deftx noop-nil! []
  [tx-env ev e]
  nil)

(deftx noop-unchanged! []
  [tx-env ev e]
  tx-env)

(deftx wrong-result! []
  [tx-env ev e]
  {:counter {:value 0}})

;; ## tx-env and the DOM event
;;
;; `tx-env` holds every table under its id plus namespaced framework keys: the fx queue
;; (`::sg/fx`), `::sg/runtime-ref`, `::sg/dom-event` and others. The log shows them.
;;
;; - `e` is the browser's event when the tx came from an `:on-*` attribute. Read input
;;   values (`(.. e -target -value)`), form data (`(js/FormData. (.-target e))`), keys
;;   (`(.-key e)`). Call `(.preventDefault e)` in the body when it is conditional (only for
;;   Enter); `shadow.dom/ev-stop` prevents and stops propagation in one call.
;; - `e` is nil when the tx was started from code: `sg/run-tx!`, `sg/run`, or
;;   `sg/dispatch-up!` from a slot. A handler used both ways guards with `(when e ...)`.
;; - Never mutate tables, never keep `tx-env` for later. Async continuations (a timer, a
;;   callback, a response) start a new tx with `sg/run-tx!` and a fresh env
;;   ([external-events](/external-events/)). The second button does that from a timeout.

(deftx inspect-env! []
  [tx-env ev e]
  (c/log! "tables:" (pr-str (sort (filter simple-keyword? (keys tx-env)))))
  (c/log! "framework keys:" (pr-str (sort (filter qualified-keyword? (keys tx-env)))))
  (c/log! "dom event:" (if e (.-type e) "nil"))
  tx-env)

;; ## testing handlers and queries
;;
;; deftx handlers and queries are functions over maps, test them without a runtime.
;; Build tables with `kv/init` so `kv/add` / `kv/merge-seq` find their config. A deftx
;; handler sits in the event map's metadata under `::sg/tx`. From
;; `src/dev/examples/transactions_test.cljs`:
;;
;; ```
;; (defn run-ev [env ev]
;;   ((::sg/tx (meta ev)) env ev nil))
;;
;; (run-ev {:counter {:value 1}} (tx/add! 5))  ;; => {:counter {:value 6}}
;;
;; (let [env {:item (kv/init :item {:primary-key :id} {1 {:id 1 :color :red ...}})}]
;;   (reads/?items-by-color env :red))
;; ```
;;
;; Queued fx land under `::sg/fx` in the returned env, assert on them instead of running
;; them. `sg/dev-log` (and `sg/dev-only` for whole blocks) log in dev builds and compile
;; away in release. Run the tests with
;; `npx shadow-cljs compile examples-test && node out/examples-test/test.js`.

;; ## shadow.grove.events is deprecated
;;
;; Older code registers handlers through metadata on a plain `defn`
;; (`{::ev/handle [...]}`) collected by `ev/register-events!`, and queues fx with
;; `ev/queue-fx`. Don't copy that. Use `deftx` (or `sg/reg-event` for events without a
;; natural constructor) and `sg/queue-fx`. When touching such code, turn the handler into
;; a deftx and call its constructor where the keyword was dispatched.

;; ## debugging
;;
;; - "Unhandled Event" in the console: no slot, no deftx in the map, no reg-event for its
;;   `:e`. A typo, a missing `reg-event`, or a quoted keyword instead of calling the
;;   constructor ([component-events](/component-events/)). "unknown fx": a missing
;;   `reg-fx` ([fx](/fx/)).
;; - A click runs the component slot but not the deftx: the slot swallowed it,
;;   `dispatch-up!` or `run-tx` the same map.
;; - The handler runs but the UI doesn't change: it didn't return the updated `tx-env`, or
;;   wrote to a path nobody reads.
;; - Stale dialog state after navigating: route changes clear ephemeral `:ui` keys in one
;;   place, `:ui/route!` ([routing](/routing/)).
;; - An async callback touching `tx-env`: capture ids in the closure and
;;   `(sg/run-tx! rt-ref ev-map)` instead.

;; # page

(defn value-line [value]
  (<< [:p (css :text-lg :mb-2) "value: " [:b {:data-value ""} value]]))

(defc ui-root []
  (bind value (sg/kv-lookup :counter :value))

  (event ::later! [env ev e]
    (js/setTimeout #(sg/run-tx! app (inspect-env!)) 100))

  (render
    (c/page "transactions"
      {"deftx: event maps are data"
       (<< (value-line value)
           (event-maps)
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click (add! 5)} "add 5"]
            [:button {:class c/$btn :on-click (add-tagged! 1)} "add 1 (tagged)"]
            [:button {:class c/$btn :on-click (add-point! {:x 1 :y 2})} "add {:x 1 :y 2}"]])

       "chaining with sg/run"
       (<< (value-line value)
           [:button {:class c/$btn :on-click (add-twice! 10)} "add 10 twice (sg/run)"])

       "reg-event"
       (<< (value-line value)
           [:button {:class c/$btn :on-click {:e ::reset! :to 100}} "reg-event ::reset! to 100"])

       "handler results"
       [:div {:class c/$row}
        [:button {:class c/$btn :on-click (noop-nil!)} "return nil"]
        [:button {:class c/$btn :on-click (noop-unchanged!)} "return tx-env"]
        [:button {:class c/$btn :on-click (wrong-result!)} "return another map"]]

       "tx-env and the DOM event"
       [:div {:class c/$row}
        [:button {:class c/$btn :on-click (inspect-env!)} "log tx-env keys (from a click)"]
        [:button {:class c/$btn :on-click ::later!} "same via run-tx! from a timeout"]]})))

(defn init []
  (sg/add-kv-table app :counter {} {:value 0})
  (sg/add-kv-table app :other {} {})
  (register-events!)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
