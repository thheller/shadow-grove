(ns examples.slot-provider
  "Tracked reads inside your own managed node. A node that runs user code (a read fn, a
   query) can let that code use `sg/kv-lookup`, `sg/query` and the other claiming helpers
   by being their **slot provider**, the role a `defc` plays for its binds. When the data
   changes, the node is told and runs the read again. `vlist2` works this way. The
   protocols are in [widget-node](/widget-node/), claiming in
   [bind-helpers](/bind-helpers/)."
  (:require
    [examples.common :as c]
    [shadow.arborist.common :as common]
    [shadow.arborist.protocols :as ap]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.protocols :as gp]
    [shadow.grove.runtime :as rt]))

(defonce app (sg/get-runtime :app))

;; ## a node that runs a tracked read
;;
;; `(live-read read-fn render-fn)` runs `read-fn` the way a `defc` runs a bind, and
;; renders `(render-fn result)` into a `managed-root`. The functions for this are in
;; `shadow.grove.runtime`: `rt/slot-ref`, `rt/run-slot` and `rt/cleanup-slot-ref!`.
;;
;; - The node implements `gp/IProvideSlot`. `-init-slot-ref` returns the one ref it
;;   owns, made with `(rt/slot-ref node idx)`: that is what `rt/claim-slot!` hands the
;;   helper.
;;   `-invalidate-slot!` is called when tracked data changed: run the read again.
;;   `-provide-new-value!` only matters for helpers that use it (`vgrid` does). The
;;   ref implements `gp/IInvalidateSlot` (`invalidate!`, `provide-new-value!`), which is
;;   what claiming helpers call, and forwards to these.
;; - One ref means one claim per run, the rule of a `bind`. The read here does one
;;   `kv-lookup`. Several values come from one query.
;; - `(rt/run-slot node idx env prev-value f)` calls `f` with the slot bound. It returns
;;   `::rt/suspended` when `f` called `sg/suspend!`, and the node then keeps its previous
;;   result until the next invalidation. `prev-value` is what `suspend!` returns, here the
;;   previous result. (`vlist2` predates this and checks for a suspend before calling its
;;   read fn, so it never sees one. Don't copy that part.)
;; - `destroy!` calls `rt/cleanup-slot-ref!`, which runs the cleanup the claiming helper
;;   registered and unsubscribes the read. Without it the subscription leaks.
;;
;; Try it: "n + 1" runs the read again (the log says so), "other + 1" changes a key the
;; read didn't touch and runs nothing. The read suspends while `n` is odd: the node keeps
;; showing the last even value. Hide it, change `n`: no read runs, the cleanup released it.

(declare ReadSeed)

(deftype ReadNode
  [env
   ^:mutable slot-ref
   root
   ^:mutable read-fn
   ^:mutable render-fn
   ^:mutable last-result
   ^:mutable destroyed?]

  gp/IProvideSlot
  (-init-slot-ref [this idx] slot-ref)
  (-invalidate-slot! [this idx] (.run-read! this))
  (-provide-new-value! [this idx v])

  ap/IManaged
  (supports? [this next] (instance? ReadSeed next))
  (dom-sync! [this ^ReadSeed next]
    (let [new-read (.-read-fn next)
          new-render (.-render-fn next)]
      (cond
        (not (identical? read-fn new-read))
        (do (set! read-fn new-read)
            (set! render-fn new-render)
            (.run-read! this))

        (not (identical? render-fn new-render))
        (do (set! render-fn new-render)
            (ap/update! root (render-fn last-result))))))
  (dom-insert [this parent anchor] (ap/dom-insert root parent anchor))
  (dom-first [this] (ap/dom-first root))
  (dom-entered! [this] (ap/dom-entered! root))
  (destroy! [this dom-remove?]
    (set! destroyed? true)
    (rt/cleanup-slot-ref! slot-ref)
    (ap/destroy! root dom-remove?))

  Object
  (run-read! [this]
    (when-not destroyed?
      (let [result (rt/run-slot this 0 env last-result read-fn)]
        (when (not= ::rt/suspended result)
          (set! last-result result)
          (ap/update! root (render-fn result)))))))

(deftype ReadSeed [read-fn render-fn]
  ap/IConstruct
  (as-managed [this env]
    (let [node (ReadNode. env nil (common/managed-root env) read-fn render-fn nil false)]
      (set! (.-slot-ref node) (rt/slot-ref node 0))
      (.run-read! node)
      node)))

(defn live-read [read-fn render-fn]
  (ReadSeed. read-fn render-fn))

;; top-level fns, so dom-sync! sees the identical fns and does nothing
(defn read-n []
  (c/log! "read-n ran")
  (let [n (sg/kv-lookup :counter :n)]
    (if (odd? n)
      (sg/suspend!)
      n)))

(defn render-n [n]
  (<< [:span {:class c/$badge :data-live ""} "even n, read by the node: " n]))

;; # page

(deftx inc! [k]
  [tx-env ev e]
  (update-in tx-env [:counter k] inc))

(defc ui-counter []
  (bind {:keys [n other]} (sg/kv-lookup :counter))
  (render (<< [:span {:class c/$muted} "n = " n ", other = " other])))

(defc ui-root []
  (ref state (atom true))
  (bind show? (sg/watch state))
  (event ::show! [env ev e] (swap! state not))
  (render
    (c/page "slot provider"
      {"a node that runs a tracked read"
       (<< [:div {:class [c/$row (css :mb-2)]}
            [:button {:class c/$btn :on-click (inc! :n)} "n + 1"]
            [:button {:class c/$btn :on-click (inc! :other)} "other + 1"]
            [:button {:class c/$btn :on-click ::show!} (if show? "hide" "show")]
            (ui-counter)]
           (when show?
             (live-read read-n render-n)))})))

(defn init []
  (sg/add-kv-table app :counter {} {:n 0 :other 0})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
