(ns examples.child-host
  "A managed node hosting children: it owns a `managed-root`, forwards the lifecycle to
   it, and can give the subtree its own env. The protocols are in
   [widget-node](/widget-node/)."
  (:require
    [examples.common :as c]
    [shadow.arborist.common :as common]
    [shadow.arborist.protocols :as ap]
    [shadow.grove :as sg :refer (defc << css)]))

(defonce app (sg/get-runtime :app))

;; ## hosting children with a managed-root
;;
;; `(common/managed-root env)` is a swappable child container: `(ap/update! root seed)`
;; with any renderable, also before the root is inserted. It handles `supports?`,
;; replacing and `dom-entered!` for whatever it holds. `portal`, `dnd_sortable` and `defc`
;; itself host their children this way.
;;
;; `FrameNode` just forwards every lifecycle call to its root. Children that live in a
;; different DOM parent than the node (`portal` renders into `document.body`) must always
;; remove their DOM on `destroy!`: `portal` passes `true` regardless of `dom-remove?`.

(declare FrameSeed)

(deftype FrameNode [env root]
  ap/IManaged
  (supports? [this next] (instance? FrameSeed next))
  (dom-sync! [this ^FrameSeed next] (ap/update! root (.-body next)))
  (dom-insert [this parent anchor] (ap/dom-insert root parent anchor))
  (dom-first [this] (ap/dom-first root))
  (dom-entered! [this] (ap/dom-entered! root))
  (destroy! [this dom-remove?] (ap/destroy! root dom-remove?)))

;; ## the env
;;
;; `env` is an immutable map passed from parent to child at `as-managed` time. It never
;; changes for a mounted node. To give a subtree context, `assoc` onto it before creating
;; the children: here each frame puts its theme atom under `::frame-theme`, and any
;; component below reads it with `(sg/env-watch ::frame-theme [:dense?] default)`.
;;
;; `sg/env-watch` throws when its key isn't in the env at all, the default only covers the
;; path inside the atom. So the app provides a default theme for everything outside a
;; frame (next chapter). Toggle frame a: only its rows change.
;;
;; Keys you may read from `env`:
;;
;; | key | value |
;; |---|---|
;; | `::sg/runtime-ref` | the runtime atom (`run-tx!`, KV) |
;; | `::sg/scheduler` | the nearest `IScheduleWork`: a component, a suspense boundary or the root ([async-node](/async-node/)) |
;; | `::comp/event-target` | the nearest `IHandleEvents`: a component, else the root tx target ([widget-node](/widget-node/)) |
;; | `::comp/component` | the nearest component, nil at the root (`comp/get-component`) |
;; | `::ap/dom-event-handler` | the nearest `IHandleDOMEvents`, receives `:on-*` keyword and map values from fragments ([dom-events](/dom-events/)) |
;; | `::sg/root-el` | the element passed to `sg/render` |
;; | `:dom/svg`, `:dom/element-fn` | set by fragments inside `[:svg ...]` |
;;
;; Existing overrides: `suspense` swaps `::sg/scheduler` to collect suspensions, `defc`
;; sets the component, event target, scheduler and DOM event handler for its subtree.
;; A node can install itself as `::ap/dom-event-handler` to intercept `:on-*` values, but
;; the next `defc` below it takes over again. Rarely needed: a node emitting events from
;; its own callbacks sends them to `(::comp/event-target env)` ([widget-node](/widget-node/)).

(deftype FrameSeed [theme body]
  ap/IConstruct
  (as-managed [this env]
    (let [child-env (assoc env ::frame-theme theme)]
      (FrameNode. env (doto (common/managed-root child-env) (ap/update! body))))))

(defn frame
  "theme is an atom, so descendants can watch it with sg/env-watch"
  [theme body]
  (FrameSeed. theme body))

(defc ui-themed-row [label]
  (bind dense? (sg/env-watch ::frame-theme [:dense?] false))
  (bind accent (sg/env-watch ::app-config [:accent] "black"))
  (render
    (<< [:div {:class (if dense? (css :py-0 :text-sm) (css :py-2))
               :style {:color accent}
               :data-row label
               :data-dense (str dense?)}
         label (if dense? " (dense)" " (roomy)")])))

(defonce theme-a (atom {:dense? false}))
(defonce theme-b (atom {:dense? true}))

(defn frames []
  (<< [:div (css :grid :grid-cols-2 :gap-4)
       [:div#frame-a (css :border :rounded :p-2)
        (frame theme-a (<< (ui-themed-row "a1") (ui-themed-row "a2")))]
       [:div#frame-b (css :border :rounded :p-2)
        (frame theme-b (<< (ui-themed-row "b1") (ui-themed-row "b2")))]]
      [:div#outside (css :mt-2) (ui-themed-row "outside any frame")]))

;; ## app-wide env entries: ::sg/env-init
;;
;; Entries for the whole app go in before the first render, through the runtime's
;; `::sg/env-init` fns. They run when a root renders for the first time, which is also
;; how `history/init!` installs its click listener ([routing](/routing/)). Here they
;; provide `::app-config` (the accent color) and the default `::frame-theme`.

(defonce app-config (atom {:accent "#1d4ed8"}))
(defonce default-theme (atom {:dense? false}))

(defn install-env! []
  (swap! app update ::sg/env-init conj
    (fn [env] (assoc env ::app-config app-config ::frame-theme default-theme))))

;; # page

(defc ui-root []
  (event ::toggle-a! [env ev e] (swap! theme-a update :dense? not))
  (event ::accent! [env ev e] (swap! app-config update :accent #(if (= % "#1d4ed8") "#b91c1c" "#1d4ed8")))

  (render
    (c/page "child host"
      {"the env"
       (<< [:div {:class [c/$row (css :mb-2)]}
            [:button {:class c/$btn :on-click ::toggle-a!} "toggle frame a dense"]
            [:button {:class c/$btn :on-click ::accent!} "toggle app accent (env-init)"]]
           (frames))})))

(defn init []
  (install-env!)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
