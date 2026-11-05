(ns examples.refs
  "`ref` declares a value the component instance owns: a DOM node, an atom, a list
   handle. It is created once, when the instance is constructed (before the first bind
   runs, also offscreen under `suspense`), and the name is the same value for the
   instance's whole life. A ref is not a slot: it is never dirty and doesn't count
   toward the 30 steps. A remount (another definition, a changed `^:stable` arg, a new
   `keyed-seq` key, hot-reload) creates new refs.

   ```
   (ref dom-node)                       ;; volatile for :dom/ref
   (ref state (atom {:open? false}))    ;; local state, see local-state
   (ref items (vlist3/prepare opts q))  ;; a list handle, see vlist
   ```"
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.protocols :as gp]))

(defonce app (sg/get-runtime :app))

;; ## (ref name) for :dom/ref
;;
;; `(ref name)` alone is a `(volatile! nil)`. `:dom/ref` fills it with the DOM node after
;; mount. It is nil during `render`, and set by the time `:mount` and `:auto` effects
;; run, which is where you touch the node: `.focus`, `.showModal`, measuring, an
;; uncontrolled input's initial value ([effects](/effects/)). Event slots can use it too.
;;
;; `(bind name (sg/ref))` is the older form of the same. Prefer `(ref name)`, it is
;; created once instead of in a slot.

(defc ui-dom-ref []
  (ref input)
  (effect :mount [env]
    (c/log! "dom-ref: in the effect @input is a" (.-tagName @input))
    (.focus @input))
  (event ::measure! [env ev e]
    (c/log! "dom-ref: input is" (.-offsetWidth @input) "px wide, value" (.-value @input)))
  (render
    (<< [:div {:class c/$row}
         [:input {:dom/ref input :class c/$input :placeholder "focused on mount"}]
         [:button {:class c/$btn :on-click ::measure!} "measure"]])))

;; ## (ref name init) is created once
;;
;; The init runs once per instance. `n + 1` updates the instance and the refs stay. The
;; seed is `^:stable`, so a new seed remounts and creates new refs (the log shows the
;; init running again).
;;
;; The init may only use `^:stable` args and earlier refs. A stable arg is safe because
;; changing it remounts. Using `n` here would be a compile error: "ref x can only use
;; ^:stable args and previously declared refs in its init, but uses: n". Combine a ref
;; with values that change in a bind or hook instead, e.g.
;; `(hook (vlist3/set-opts! items {:tab-index (if active? 0 -1)}))`.
;;
;; Refs may go anywhere among the steps, put them next to what uses them.

(defc ui-created-once [^:stable seed n]
  (ref created-at (do (c/log! "created-once: ref init ran for seed" seed) (c/time-str)))
  (ref list-ref (atom [(str "seed " seed)]))
  (render
    (<< [:div {:class c/$row}
         "seed " seed ", n " n ", ref created at " created-at ", atom " (pr-str @list-ref)
         (c/render-count ::created-once)])))

;; ## ref protocols
;;
;; Init values can implement protocols from `shadow.grove.protocols`. Values that
;; implement none of them (atoms, volatiles, maps) are used as they are.
;;
;; | protocol | called |
;; |---|---|
;; | `IRefInit` `(-ref-init [this env])` | once at construction with the component env. The name is bound to the return value, so the init can be a cheap value that builds the real thing from `env`. |
;; | `IRefDestroy` `(-ref-destroy! [this])` | on unmount, after the subtree is destroyed, in reverse declaration order. |
;; | `IRefRender` `(-ref-before-render! [this env])`, `(-ref-after-render! [this env])` | around each actual render, not when the render was skipped. After-render may still be offscreen, use an effect for anything that needs the document. |
;;
;; A ref value that implements `ap/IConstruct` can be rendered directly,
;; `(render items)`. That is how `vlist3` handles work ([vlist](/vlist/)).
;;
;; Try it: `n + 2` keeps `even?` the same, the render is skipped and `IRefRender` doesn't
;; log. Hiding unmounts: `IRefDestroy`, the last ref first.

(deftype RuntimeInfo [label]
  gp/IRefInit
  (-ref-init [this env]
    (let [rt-ref (::sg/runtime-ref env)]
      (c/log! "IRefInit: building" label "with env")
      {:label label
       :app-id (::sg/app-id @rt-ref)})))

(deftype Resource [label]
  gp/IRefDestroy
  (-ref-destroy! [this]
    (c/log! "IRefDestroy:" label)))

(deftype RenderTimer [^:mutable start]
  gp/IRefRender
  (-ref-before-render! [this env]
    (set! start (js/performance.now)))
  (-ref-after-render! [this env]
    (c/log! "IRefRender: render took" (.toFixed (- (js/performance.now) start) 2) "ms")))

(defc ui-protocols [n]
  (ref info (RuntimeInfo. "runtime info"))
  (ref first-resource (Resource. "first ref, destroyed last"))
  (ref second-resource (Resource. "second ref, destroyed first"))
  (ref timer (RenderTimer. 0))
  (bind even? (even? n))
  (render
    (<< [:div {:class c/$row}
         "app-id from the IRefInit ref: " (pr-str (:app-id info))
         ", even? " (pr-str even?)])))

;; # page

(defc ui-root []
  (ref state (atom {:seed 1 :n 0 :show? true}))
  (bind {:keys [seed n show?]} (sg/watch state))

  (event ::n! [env ev e] (swap! state update :n inc))
  (event ::n2! [env ev e] (swap! state update :n + 2))
  (event ::seed! [env ev e] (swap! state update :seed inc))
  (event ::show! [env ev e] (swap! state update :show? not))

  (render
    (c/page "refs"
      {"(ref name) for :dom/ref"
       (ui-dom-ref)

       "(ref name init) is created once"
       (<< (ui-created-once seed n)
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click ::n!} "n + 1"]
            [:button {:class c/$btn :on-click ::seed!} "new seed"]])

       "ref protocols"
       (<< (when show?
             (ui-protocols n))
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click ::n!} "n + 1"]
            [:button {:class c/$btn :on-click ::n2!} "n + 2"]
            [:button {:class c/$btn :on-click ::show!} (if show? "hide" "show")]])})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
