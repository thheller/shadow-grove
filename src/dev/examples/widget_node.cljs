(ns examples.widget-node
  "Every renderable in grove (text, fragments, components, `simple-seq` / `keyed-seq`,
   `suspense`, `portal`, `lazy/use`, a `vlist3` handle) implements the protocols in
   `shadow.arborist.protocols`. They are a public extension point, not internals.
   Writing your own is encouraged when the built-ins are a poor fit:

   - **specialized collections**: variable-height or bidirectional virtualization,
     append-only logs, grids, lists with enter and leave animations, drag and drop.
   - **third-party widgets that own their DOM** (editors, maps, charts), this page.
   - **subtree context**: a node that puts itself into `env` for its descendants
     ([child-host](/child-host/)).
   - **custom async boundaries** that cooperate with `suspense`
     ([async-node](/async-node/)).

   Not good reasons: anything a `defc` with `bind`, `effect` and `ref` already does
   cleanly, or a list where `keyed-seq` is fast enough. A custom node opts out of the
   `defc` machinery (slots, the equality short-circuit, event slots) and takes on its
   DOM bookkeeping itself."
  (:require
    [examples.common :as c]
    [shadow.arborist.common :as common]
    [shadow.arborist.dom-scheduler :as ds]
    [shadow.arborist.protocols :as ap]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.components :as comp]
    [shadow.grove.protocols :as gp]))

(defonce app (sg/get-runtime :app))

;; the "library": draws bars into a canvas sized to its element, emits "select".
;; stands in for a chart/map/editor lib with an imperative API

(deftype BarWidget [^js el ^js canvas ^:mutable data ^:mutable handlers]
  Object
  (draw [this]
    (let [w (.-clientWidth el)
          h 60
          ctx (.getContext canvas "2d")
          n (max 1 (count data))
          top (max 1 (apply max 1 data))]
      (set! (.-width canvas) w)
      (set! (.-height canvas) h)
      (set! (.-fillStyle ctx) "#3b82f6")
      (doseq [[i v] (map-indexed vector data)]
        (.fillRect ctx (* i (/ w n)) (- h (* h (/ v top))) (- (/ w n) 2) (* h (/ v top))))))
  (setData [this new-data]
    (set! data new-data)
    (.draw this))
  (on [this event f]
    (set! handlers (assoc handlers event f)))
  (destroy [this]
    (set! handlers {})
    (.remove canvas)))

(defn make-bar-widget [^js el data]
  (let [canvas (js/document.createElement "canvas")
        w (BarWidget. el canvas data {})]
    (.append el canvas)
    ;; measuring: 0 if the element isn't in the document yet
    (c/log! "widget constructed, element width" (.-clientWidth el))
    (.addEventListener canvas "click"
      (fn [^js e]
        (let [n (max 1 (count (.-data w)))
              idx (js/Math.floor (/ (* n (.-offsetX e)) (.-clientWidth el)))]
          (when-some [f (get (.-handlers w) "select")]
            (f idx)))))
    (.draw w)
    w))

;; ## the model
;;
;; ```
;; seed (cheap value) --ap/as-managed env--> managed node (owns DOM nodes)
;;
;; the parent calls, in order:  dom-insert parent anchor   (maybe again later, to move it)
;;                              dom-entered!               (once, when actually in the document)
;; on every parent update:      supports? next-seed  -> dom-sync! next-seed
;;                                                   -> else a new node replaces this one
;; on removal:                  destroy! dom-remove?
;; ```
;;
;; - A **seed** is what render code returns, `(bar-widget opts)`. It must be cheap to
;;   create and carry only data. It implements `ap/IConstruct`.
;; - The **managed node** is created by `as-managed` with the parent's `env`. It creates
;;   its DOM nodes detached, gets inserted, and later updates itself in place from new
;;   seeds. It implements `ap/IManaged`.
;; - Replacement is generic: build the new node, `dom-insert` it before the old node's
;;   `dom-first`, then `(destroy! old true)`.
;;
;; | method | contract |
;; |---|---|
;; | `as-managed env` (on the seed) | return a new managed node. Create DOM nodes, don't attach them, don't measure. May create and fully render children. |
;; | `dom-insert parent anchor` | insert **all** DOM nodes you own into `parent` before `anchor` (nil = append). Must be repeatable: `keyed-seq` calls it again to move you. |
;; | `dom-first` | the first DOM node you own, the anchor for replacing or moving you. Never nil once constructed: a node without a fixed element owns a marker (`common/dom-marker`). |
;; | `dom-entered!` | called once, after your nodes are in the live document. May be later than `dom-insert` (`suspense` builds offscreen). Measure and initialize third-party libs here. **Propagate it** to your children, also to children created after you entered. |
;; | `supports? next` | true if `dom-sync!` can turn you into `next`. Usually `(instance? MySeed next)` plus identity checks on what you can't change in place. Keep it cheap. |
;; | `dom-sync! next` | update in place from the new seed. Early-out when nothing relevant changed. |
;; | `destroy! dom-remove?` | always release resources (listeners, timers, third-party instances). Remove your DOM nodes only when `dom-remove?` is true. When you remove a container holding your children, destroy them with `false`. |
;;
;; Roots also implement `ap/IDirectUpdate` (`update!`). Grove-side protocols
;; (`shadow.grove.protocols`) a node uses: `IScheduleWork` (`did-suspend!`,
;; `did-finish!`) to report async loading to `suspense` ([async-node](/async-node/)),
;; and `IHandleEvents` (`handle-event!`) to send an event map into the component tree.

;; ## wrapping a widget that owns its DOM
;;
;; `BarWidget` stands in for a chart library: it takes an element, has to be in the
;; document to measure it, and emits "select". Click a bar. "reverse" moves the widgets
;; (`dom-insert` again), "add a value" goes through `dom-sync!`, "hide" destroys them.
;; The log shows the element width at construction: never 0, because construction waits
;; for `dom-entered!`.
;;
;; Points worth copying:
;;
;; - Construction is deferred to `dom-entered!` and batched with `ds/write!`
;;   ([effects](/effects/)). Deferred work checks for an intervening `destroy!`.
;; - Events go to `(::comp/event-target env)` with
;;   `(gp/handle-event! target ev-map dom-event origin-env)`: the same path as a DOM
;;   `:on-click`, so the owning component's slots can intercept them and unhandled maps
;;   become transactions ([component-events](/component-events/)). Don't call `run-tx!`
;;   directly. `(comp/get-component env)` is the owning component, nil outside any
;;   `defc`.
;; - `opts` arrive fresh on every parent render. Compare before touching the widget.
;; - A `deftype` seed compares by identity. Add `IEquiv` if seeds are passed as `defc`
;;   args or bind values where `=` should hold.

(declare WidgetSeed)

(deftype WidgetNode [env ^js el ^:mutable opts ^BarWidget ^:mutable widget ^:mutable destroyed?]
  ap/IManaged
  (supports? [this next]
    (instance? WidgetSeed next))

  ;; opts arrive fresh on every parent render, only touch the widget when they changed
  (dom-sync! [this ^WidgetSeed next]
    (let [next-opts (.-opts next)]
      (when (not= opts next-opts)
        (set! opts next-opts)
        (when widget
          (.setData widget (:data opts))))))

  ;; must handle being called again: keyed-seq moves nodes this way
  (dom-insert [this parent anchor]
    (.insertBefore parent el anchor))

  (dom-first [this] el)

  ;; once in the document. may be later than dom-insert (suspense renders offscreen)
  (dom-entered! [this]
    (ds/write!
      ;; destroy! may have happened before this runs
      (when-not destroyed?
        (set! widget (make-bar-widget el (:data opts)))
        (.on widget "select"
          (fn [idx]
            (when-some [ev (:on-select opts)]
              ;; same path as a DOM :on-click: component slots first, else a tx
              (gp/handle-event! (::comp/event-target env) (assoc ev :idx idx) nil env)))))))

  (destroy! [this dom-remove?]
    (set! destroyed? true)
    (c/log! "widget destroyed, dom-remove?" dom-remove?)
    (when widget
      (.destroy widget))
    (when dom-remove?
      (.remove el))))

(deftype WidgetSeed [opts]
  ap/IConstruct
  (as-managed [this env]
    (let [el (js/document.createElement "div")]
      (.setAttribute el "class" "bar-widget")
      (WidgetNode. env el opts nil false))))

(defn bar-widget [opts]
  (WidgetSeed. opts))

(defc ui-chart [id data]
  (event ::bar-selected! [env {:keys [idx]} e]
    (c/log! "chart" id "bar" idx "selected (handled by the component slot)"))
  (render
    (<< [:div {:class (css :border :rounded :p-2 :mb-2) :data-chart id}
         [:div (css :text-sm) "chart " id]
         (bar-widget {:data data :on-select {:e ::bar-selected!}})])))

;; ## IConstruct via metadata
;;
;; `IConstruct` can be implemented through metadata, which saves a seed type for one-off
;; wrappers: `with-meta` with the syntax-quoted `ap/as-managed` symbol as the key, as
;; `label` does. The matching `supports?` is `ap/identical-creator?`, true when both values
;; have the same `as-managed` fn.

(deftype LabelNode [env ^js el ^:mutable opts]
  ap/IManaged
  (supports? [this next] (ap/identical-creator? opts next))
  (dom-sync! [this next]
    (when (not= (:text opts) (:text next))
      (set! (.-textContent el) (:text next)))
    (set! opts next))
  (dom-insert [this parent anchor] (.insertBefore parent el anchor))
  (dom-first [this] el)
  (dom-entered! [this])
  (destroy! [this dom-remove?] (when dom-remove? (.remove el))))

(defn make-label [opts env]
  (let [el (js/document.createElement "output")]
    (set! (.-textContent el) (:text opts))
    (LabelNode. env el opts)))

(defn label [opts]
  (with-meta opts {`ap/as-managed make-label}))

;; ## building blocks
;;
;; - `common/dom-marker`: an empty text node (a comment in dev) to serve as `dom-first`
;;   and an insertion anchor.
;; - `common/managed-root env`: a swappable child container, `(ap/update! root seed)`
;;   with any renderable ([child-host](/child-host/)).
;; - `common/replace-managed env old seed` and `common/fragment-replace old new`: the
;;   generic replace. Call `dom-entered!` on the result yourself if you are entered.
;; - `common/native-el node`: an existing DOM node as a renderable. It only supports the
;;   identical node. Often enough for "render this element I already built", no custom
;;   type needed.
;; - `shadow.arborist.collections`: `simple-seq`, `keyed-seq`, `diff-seq` for rendering
;;   slices inside your node ([lists](/lists/)).
;; - `shadow.arborist.dom-scheduler`: `ds/read!`, `ds/write!`, `ds/after!` for measuring
;;   and heavy third-party init.
;; - `shadow.arborist.interpreted`: runtime hiccup vectors as renderables. Not loaded by
;;   grove, require it only if you must render vectors built at runtime.
;; - Devtools: extend `shadow.grove.devtools.protocols/ISnapshot` under `goog.DEBUG` so
;;   the devtools tree shows your node's children (see the end of `vlist2.cljs`).

;; ## checklist
;;
;; - `dom-first` is never nil, own a marker if you have no fixed element.
;; - `dom-insert` moves **every** node you own and works when called twice.
;; - No measuring, focusing or third-party init before `dom-entered!`. Propagate it to
;;   children, including ones created later.
;; - `destroy!` frees everything, even when `dom-remove?` is false.
;; - Children are created with `ap/as-managed` using your env (or an extended copy) and
;;   managed through `managed-root` or collections, not by hand-diffing.
;; - Seeds are immutable data.
;; - Events flow through `gp/handle-event!`, not `run-tx!`, so components can intercept
;;   them.
;; - Updating your own children from your own DOM callbacks (scroll, resize) with
;;   `ap/update!` is fine.

;; ## reference implementations
;;
;; | file | shows |
;; |---|---|
;; | `arborist/common.cljs` | `ManagedText`, `ManagedRoot`, `NativeElement`: the minimal shapes |
;; | `arborist/collections.cljs` | keyed diffing with repeated `dom-insert` moves |
;; | `grove/ui/portal.cljs` | children in a different DOM parent |
;; | `grove/ui/lazy.cljs` | async content, `did-suspend!` / `did-finish!` |
;; | `grove/ui/suspense.cljs` | offscreen rendering, replacing `::sg/scheduler` in env |
;; | `grove/ui/vlist2.cljs` | owned DOM, `IProvideSlot`, keyboard, events |
;; | `grove/ui/vlist3.cljs` | a `ref` handle that is renderable: `IConstruct` on the handle |
;; | `grove/ui/vgrid.cljs` | the 2D variant of vlist2 |
;; | `grove/ui/dnd_sortable.cljs` | env context and custom attributes |

;; # page

(defc ui-root []
  (ref state (atom {:charts [{:id "a" :data [3 1 4 1 5]} {:id "b" :data [2 7 1 8 2]}] :show? true :n 0}))
  (bind {:keys [charts show? n]} (sg/watch state))

  (event ::reverse! [env ev e] (swap! state update :charts #(vec (reverse %))))
  (event ::more! [env ev e] (swap! state update-in [:charts 0 :data] conj (rand-int 9)))
  (event ::show! [env ev e] (swap! state update :show? not))
  (event ::n! [env ev e] (swap! state update :n inc))

  (render
    (c/page "widget node"
      {"wrapping a widget that owns its DOM"
       (<< [:div {:class [c/$row (css :mb-2)]}
            [:button {:class c/$btn :on-click ::reverse!} "reverse"]
            [:button {:class c/$btn :on-click ::more!} "add a value to the first"]
            [:button {:class c/$btn :on-click ::show!} (if show? "hide" "show")]]
           (when show?
             (sg/keyed-seq charts :id (fn [{:keys [id data]}] (ui-chart id data)))))

       "IConstruct via metadata"
       [:div {:class c/$row}
        (label {:text (str "label " n)})
        [:button {:class c/$btn :on-click ::n!} "n + 1"]]})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
