(ns examples.dom-events
  "`:on-click`, `:on-input`, `:on-submit` and every other `:on-*` attribute take an
   event map, a keyword, a function or nil. Event maps are the normal case: they are
   data, go through component `event` slots and end in a transaction
   ([component-events](/component-events/), [transactions](/transactions/))."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; ## what :on-* accepts
;;
;; - **A deftx map**, `(clicked! :deftx)`: the most common. The constructor builds
;;   `{:e ::clicked! :args [...]}` with the handler in metadata, so the root can run it.
;; - **A map** `{:e ::local! :extra 1}`: routes to a component slot `(event ::local! ...)`.
;;   Extra keys ride along in the map, the DOM event arrives as the slot's third arg.
;; - **A keyword** `::local!` is short for `{:e ::local!}`.
;; - **A function** is called as `(f dom-event env)`. It bypasses event slots and the tx
;;   pipeline, and is opaque to anything that wants to intercept it. Fragments install one
;;   listener that calls the latest value, so an inline `(fn ...)` that is new on every
;;   render doesn't stack listeners: click "re-render" a few times, then "inline fn". It
;;   runs once and sees the latest `n`. Prefer event maps anyway.
;; - **nil** means no handler, the event is ignored: `:on-click (when armed? ev)`.
;;
;; Anything else throws in dev.

(deftx clicked! [label]
  [tx-env ev e]
  (c/log! "tx clicked!" label)
  (update-in tx-env [:ui :clicks label] (fnil inc 0)))

(defn plain-handler [dom-event env]
  (c/log! "plain fn called with a" (.-type dom-event)))

(defc ui-value-forms []
  (ref state (atom {:armed? true :n 0}))
  (bind {:keys [armed? n]} (sg/watch state))

  (event ::local! [env ev e]
    (c/log! "slot ::local! got" (pr-str (dissoc ev :e))))

  (event ::rerender! [env ev e]
    (swap! state update :n inc))

  (event ::arm! [env ev e]
    (swap! state update :armed? not))

  (render
    (<< [:div {:class c/$row}
         [:button {:class c/$btn :on-click (clicked! :deftx)} "deftx map"]
         [:button {:class c/$btn :on-click {:e ::local! :extra 1}} "{:e ::local!}"]
         [:button {:class c/$btn :on-click ::local!} "::local! keyword"]
         [:button {:class c/$btn :on-click plain-handler} "top-level fn"]
         [:button {:class c/$btn :on-click (fn [e env] (c/log! "inline fn called, n =" n))} "inline fn"]
         [:button {:class c/$btn :on-click (when armed? (clicked! :maybe))} (if armed? "(when x ev): armed" "(when x ev): nil")]]
        [:div {:class [c/$row (css :mt-2)]}
         [:button {:class c/$btn :on-click ::rerender!} "re-render"]
         [:button {:class c/$btn :on-click ::arm!} "arm/disarm"]
         (c/render-count ::value-forms)])))

;; ## :e/* options
;;
;; `:e/*` keys on an event map configure the DOM listener. Put them in the deftx base map
;; (every use gets them, like `search!` below) or `assoc` them onto a map in markup.
;;
;; | key | effect |
;; |---|---|
;; | `:e/prevent-default true` | `(.preventDefault e)` |
;; | `:e/stop true` | `(.stopPropagation e)` |
;; | `:e/stop! true` | both of the above |
;; | `:e/debounce ms` | run after `ms` of quiet (`goog.functions/debounce`) |
;; | `:e/throttle ms` | at most once per `ms`, trailing edge included |
;; | `:e/rate-limit ms` | run now, ignore further events for `ms` |
;; | `:e/once true` | listener option `{once: true}` |
;; | `:e/passive true` | listener option `{passive: true}` |
;; | `:e/capture true` | listener option `{capture: true}` |
;;
;; - The three DOM-control keys run synchronously when the event fires, even when the
;;   handler itself is debounced or throttled.
;; - They only apply when the map is bound to an `:on-*` attribute.
;;   `(sg/run-tx! app (assoc (clicked! :code) :e/throttle 1000))` runs the handler at
;;   once, every time, the throttle is ignored.
;; - When preventing is conditional (only for Enter), call `(.preventDefault e)` in the
;;   handler instead.
;; - `:e/signal` exists in the source but sets `signal: true`, which is not an
;;   `AbortSignal`. Don't use it.

(deftx search! []
  {:e/debounce 300}
  [tx-env ev e]
  (let [v (.. e -target -value)]
    (c/log! "tx search! ran with" v)
    (assoc-in tx-env [:ui :query] v)))

(defc ui-options []
  (bind query (sg/kv-lookup :ui :query))

  (event ::outer! [env ev e]
    (c/log! "outer div click"))

  (event ::follow! [env ev e]
    (c/log! "link click, defaultPrevented =" (.-defaultPrevented e)))

  (event ::inner! [env ev e]
    (c/log! "inner click"))

  (event ::passive! [env ev e]
    (c/log! "wheel listener, passive"))

  (render
    (<< [:div {:class c/$row}
         [:a {:class c/$link :href "#not-followed" :on-click {:e ::follow! :e/prevent-default true}} ":e/prevent-default"]]
        [:div {:class [c/$row (css :p-2 :mt-2 :bg-gray-50 :rounded)] :on-click ::outer!}
         "outer handler"
         [:button {:class c/$btn :on-click {:e ::inner!}} "bubbles"]
         [:button {:class c/$btn :on-click {:e ::inner! :e/stop true}} ":e/stop"]]
        [:div {:class [c/$row (css :mt-2)]}
         [:input {:class c/$input :placeholder ":e/debounce 300 (deftx base map)" :on-input (search!)}]
         "query: " (pr-str query)]
        [:div {:class [c/$row (css :mt-2)]}
         [:button {:class c/$btn :on-click (assoc (clicked! :throttle) :e/throttle 1000)} ":e/throttle 1000"]
         [:button {:class c/$btn :on-click (assoc (clicked! :rate-limit) :e/rate-limit 1000)} ":e/rate-limit 1000"]
         [:button {:class c/$btn :on-click (assoc (clicked! :once) :e/once true)} ":e/once"]
         [:div {:class [c/$badge (css :p-2)] :on-wheel {:e ::passive! :e/passive true}} "wheel here (:e/passive)"]])))

;; # page

(defn clicks-line [clicks]
  (<< [:p {:class [c/$muted (css :mb-2)]} "clicks per label (from the clicked! tx): " (pr-str clicks)]))

(defc ui-root []
  (bind clicks (sg/kv-lookup :ui :clicks))
  (render
    (c/page "dom events"
      {"what :on-* accepts"
       (<< (clicks-line clicks)
           (ui-value-forms))

       ":e/* options"
       (<< (clicks-line clicks)
           (ui-options))})))

(defn init []
  (sg/add-kv-table app :ui {} {:clicks {} :query ""})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
