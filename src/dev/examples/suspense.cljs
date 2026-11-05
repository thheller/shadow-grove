(ns examples.suspense
  "A component that can't render yet calls `(sg/suspend!)`: a pending
   [http-pull](/http-pull/), data that hasn't arrived ([loading-sentinel](/loading-sentinel/)),
   a lazy component ([lazy-loading](/lazy-loading/)). That stops its computation, later
   steps and `render` don't run, and the nearest `sg/suspense` boundary waits for it.

   ```
   (sg/suspense {:fallback (<< [:div \"Loading ...\"]) :timeout 500}
     (ui-page id))
   ```

   `:fallback` is a string or a fragment (the option is `:fallback`, not `:placeholder`),
   `:timeout` defaults to 500ms.

   The pages here suspend until their data is in KV. A `fx-timeout` delivers it after a
   delay, standing in for a request. The badge clocks show what stays visible."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; a clock, to show what stays visible
(defonce clock (atom 0))

(defonce clock-interval
  (js/setInterval #(swap! clock inc) 250))

(defc ui-clock [where]
  (bind n (sg/watch clock))
  (render
    (<< [:span {:class c/$badge :data-clock where} where " clock " n])))

;; ## the page is the boundary's direct child
;;
;; When the boundary's child changes in a way that can't update in place, a different
;; component or a changed `^:stable` arg ([stable-args](/stable-args/)), the boundary
;; mounts the new child **offscreen** and keeps showing the current one. Once the new
;; child stops suspending, it is swapped in. If that takes longer than `:timeout`, the
;; fallback replaces the current content in the meantime.
;;
;; Try it: "fast" (200ms) keeps the home page up until the fast page is ready, no
;; fallback. "slow" (1500ms) shows the old page for 500ms, then the fallback, then the
;; slow page. "forget loaded data" makes the pages suspend again.
;;
;; Known issue: the fallback swap after `:timeout` runs from a timer outside any
;; devtools trace, so with `shadow.grove.preload` loaded the console shows
;; `"not tracing?"`. It is harmless.

(def pages
  {:home {:delay 0}
   :fast {:delay 200}
   :slow {:delay 1500}})

(deftx page-loaded! [page]
  [tx-env ev e]
  (c/log! "data for" page "arrived")
  (assoc-in tx-env [:data page] {:loaded-at (c/time-str)}))

(deftx goto! [page]
  [tx-env ev e]
  (cond-> (assoc-in tx-env [:ui :page] page)
    (nil? (get-in tx-env [:data page]))
    (sg/fx-timeout [::load page] (get-in pages [page :delay]) (page-loaded! page))))

;; suspends until its data is in KV. the page is ^:stable, a new page remounts
(defc ui-page [where ^:stable page]
  (bind data (sg/kv-lookup :data page))
  (hook (when-not data (sg/suspend!)))
  (render
    (<< [:div {:class (css :p-4 :bg-green-50 :rounded) :data-page where}
         "page " [:b (name page)] ", data loaded at " (:loaded-at data)])))

(defn direct-boundary [page]
  (sg/suspense {:fallback (<< [:div {:class (css :p-4 :bg-yellow-50 :rounded) :data-fallback "direct"} "Loading ..."])
                :timeout 500}
    (ui-page "direct" page)))

;; ## a fragment as the direct child
;;
;; The same page, but the boundary's child is a `<<` fragment holding a clock and the
;; page. A fragment with the same shape always updates in place, so nothing is rendered
;; offscreen: the old page is destroyed right away, the new one renders in its place but
;; shows nothing until it stops suspending. There is no fallback either, the timeout only
;; applies to offscreen work.
;;
;; So for "keep the current page until the next one is ready", the page component itself
;; must be the boundary's child, with its identity in a `^:stable` arg or a `case`
;; switching components.

(defn wrapped-boundary [page]
  (sg/suspense {:fallback (<< [:div {:class (css :p-4 :bg-yellow-50 :rounded) :data-fallback "wrapped"} "Loading ..."])
                :timeout 500}
    (<< [:div {:class c/$row} (ui-clock "inside")]
        (ui-page "wrapped" page))))

;; ## placing boundaries
;;
;; - At the page switch: `(sg/suspense {...} (case (:id page) :list (ui-list) ...))`.
;; - Around a root that suspends itself, e.g. waiting for the session
;;   ([loading-sentinel](/loading-sentinel/)).
;; - Chrome that must survive a page load (toasts, confirm dialogs, the nav) goes
;;   **beside** the boundary, not inside it, or it gets swapped for the fallback:
;;
;; ```
;; (sg/render rt-ref root-el
;;   (<< (sg/suspense {:fallback "Loading ..."} (ui-root))
;;       (ui-toasts)))
;; ```
;;
;; The "beside" clock in the demos above is outside both boundaries and never stops.

;; ## suspend! with a default
;;
;; `(sg/suspend!)` returns the slot's previous value, or `default` from
;; `(sg/suspend! default)` on the first run. The component still suspends, but code
;; after the call in the same bind (or a query calling it) gets something to work with.
;; A [vlist](/vlist-remote/) query needs that: it returns `{:item-count 0}` while rows
;; load.

(defc ui-suspend-default []
  (bind ready? (sg/kv-lookup :ui :ready?))
  (bind value
    (let [v (if ready?
              {:status "ready"}
              (sg/suspend! {:status "default from suspend!"}))]
      (c/log! "suspend-default: bind got" v)
      v))
  (render
    (<< [:div "value: " (pr-str value)])))

(deftx toggle-ready! []
  [tx-env ev e]
  (update-in tx-env [:ui :ready?] not))

;; ## no boundary
;;
;; A suspending component with no `suspense` ancestor stays blank while it suspends, and
;; mounts once its data is there. Here it suspends for 1s. Without a boundary there is no
;; fallback and no "keep the old content", and a suspending root renders an empty page
;; until it is ready.

(defc ui-late []
  (bind data (sg/kv-lookup :data :late))
  (hook (when-not data (sg/suspend!)))
  (render
    (<< [:div#late "late data loaded at " (:loaded-at data)])))

(deftx show-late! []
  [tx-env ev e]
  (-> tx-env
      (assoc-in [:ui :show-late?] true)
      (sg/fx-timeout ::late 1000 (page-loaded! :late))))

;; # page

(deftx reset-data! []
  [tx-env ev e]
  (-> tx-env
      (assoc :data {:home {:loaded-at "start"}})
      (assoc-in [:ui :page] :home)))

(defn nav []
  (<< [:div {:class [c/$row (css :mb-2)]}
       [:button {:class c/$btn :on-click (goto! :home)} "home"]
       [:button {:class c/$btn :on-click (goto! :fast)} "fast"]
       [:button {:class c/$btn :on-click (goto! :slow)} "slow"]
       [:button {:class c/$btn :on-click (reset-data!)} "forget loaded data"]
       (ui-clock "beside")]))

(defc ui-root []
  (bind page (sg/kv-lookup :ui :page))
  (bind show-late? (sg/kv-lookup :ui :show-late?))

  (render
    (c/page "suspense"
      {"the page is the boundary's direct child"
       (<< (nav) (direct-boundary page))

       "a fragment as the direct child"
       (<< (nav) (wrapped-boundary page))

       "suspend! with a default"
       (<< (sg/suspense {:fallback "suspended" :timeout 0}
             (ui-suspend-default))
           [:button {:class [c/$btn (css :mt-2)] :on-click (toggle-ready!)} "toggle ready?"])

       "no boundary"
       (<< [:button {:class c/$btn :on-click (show-late!)} "mount a suspending component"]
           (when show-late?
             (ui-late)))})))

(defn init []
  (sg/add-kv-table app :ui {} {:page :home :ready? false})
  (sg/add-kv-table app :data {} {:home {:loaded-at "start"}})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
