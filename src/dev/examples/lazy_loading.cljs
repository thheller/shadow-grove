(ns examples.lazy-loading
  "`(lazy/use \"name\" args...)` from `shadow.grove.ui.lazy` renders a function marked
   `^{:lazy-loadable \"name\"}` that lives in another module, loading that module on first
   use. Use it for heavy things (editors, charts), not for normal components."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.ui.lazy :as lazy]))

(defonce app (sg/get-runtime :app))

;; ## a component from a module loaded on demand
;;
;; The chart lives in `examples.lazy-loading.heavy`, which this namespace never requires:
;;
;; ```
;; (defn ^{:lazy-loadable "bar-chart"} bar-chart [{:keys [values]}]
;;   (<< [:svg ...]))
;; ```
;;
;; The build puts it in its own module that depends on this one (`shadow-cljs.edn`,
;; `:lazy-loading-heavy`), and shadow-cljs records which module provides which
;; lazy-loadable name. The page loads only `lazy-loading.js`; "show chart" loads
;; `lazy-loading-heavy.js`.
;;
;; - `lazy/use` suspends until the module is loaded, so put a `suspense` above it
;;   ([suspense](/suspense/)).
;; - The function gets the args given to `lazy/use` and returns any renderable.
;; - Another `lazy/use` with the same name updates the node in place: "add a value"
;;   calls the function again with the new args.

(defn chart [values]
  (sg/suspense {:fallback (<< [:div#chart-fallback "loading the chart module ..."]) :timeout 0}
    (lazy/use "bar-chart" {:values values})))

;; # page

(defc ui-root []
  (ref state (atom {:show? false :values [3 5 2 8 6]}))
  (bind {:keys [show? values]} (sg/watch state))

  (event ::show! [env ev e]
    (c/log! "showing the chart, the module loads now")
    (swap! state assoc :show? true))

  (event ::more! [env ev e]
    (swap! state update :values conj (inc (rand-int 10))))

  (render
    (c/page "lazy loading"
      {"a component from a module loaded on demand"
       (<< [:div {:class [c/$row (css :mb-2)]}
            [:button {:class c/$btn :on-click ::show!} "show chart"]
            [:button {:class c/$btn :on-click ::more!} "add a value"]]
           (when show?
             (chart values)))})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
