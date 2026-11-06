(ns examples.effects
  "`(effect deps [env] body...)` runs **after render**, once the DOM is updated. If the
   body returns a function, it is called as cleanup before the next run and on unmount.
   Effects are where you touch DOM nodes (`.focus`, `.showModal`, measuring). They don't
   write data directly: send results through `sg/run-tx` ([component-events](/component-events/))."
  (:require
    [examples.common :as c]
    [shadow.arborist.dom-scheduler :as ds]
    [shadow.grove :as sg :refer (defc << css)]))

(defonce app (sg/get-runtime :app))

;; ## effect deps and cleanup
;;
;; | deps | runs |
;; |---|---|
;; | `:mount` | once, after the instance is attached |
;; | `:render` | after every render |
;; | `:auto` | when binds (or args) used in the body changed |
;; | any other value | when that value becomes `not=` to the previous one |
;;
;; Watch the log: `a + 1` runs `:render`, `:auto` and the `a` effect, each after the
;; cleanup of its previous run. `b + 1` runs only `:render`. The `a` effect stays quiet
;; even though its body uses `b`: with a deps value only `deps` decides, unlike `:auto`.
;;
;; Unmounting runs every cleanup.

(defc ui-effect-kinds [a b]

  (effect :mount [env]
    (c/log! "effect :mount ran")
    (fn [] (c/log! "cleanup :mount (unmount)")))

  (effect :render [env]
    (c/log! "effect :render ran")
    (fn [] (c/log! "cleanup :render")))

  ;; uses a, not b
  (effect :auto [env]
    (c/log! "effect :auto ran, a =" a)
    (fn [] (c/log! "cleanup :auto")))

  ;; deps a, but the body also uses b
  (effect a [env]
    (c/log! "effect [a] ran, a =" a "b =" b)
    (fn [] (c/log! "cleanup [a]")))

  (render
    (<< [:div {:class c/$row} "a = " a ", b = " b (c/render-count ::effect-kinds)])))

;; ## hook, and the deprecated fn forms
;;
;; `hook` is a step without a usable value. It runs during the computation, before
;; `render`, and again when something it uses changed. What it is for:
;;
;; - gating a subtree on a flag: `(hook (when-not ready? (sg/suspend!)))`
;;   ([suspense](/suspense/)).
;; - pushing changing values into a `ref` handle before it renders:
;;   `(hook (vlist3/set-query! items ?window text))` ([vlist](/vlist/)).
;;
;; Anything that should happen after render is an `effect`. `sg/mount-effect`,
;; `sg/render-effect` and `sg/effect` are the older function forms, called from a
;; `hook`. The `effect` step compiles to the same thing, don't use them in new code:
;;
;; ```
;; (hook (sg/mount-effect (fn [env] ...)))   ;; (effect :mount [env] ...)
;; (hook (sg/render-effect (fn [env] ...)))  ;; (effect :render [env] ...)
;; (hook (sg/effect deps (fn [env] ...)))    ;; (effect deps [env] ...)
;; ```

;; ## measuring with ds/read! and ds/write!
;;
;; `shadow.arborist.dom-scheduler` batches layout reads and DOM writes: all reads run
;; first, then all writes, so the browser lays out once instead of once per component.
;; `ds/read!` and `ds/write!` are macros taking a body.

(defc ui-measure [text]
  (ref box)
  (ref label)
  (effect :auto [env]
    (when (seq text)
      (ds/read!
        (let [w (.-width (.getBoundingClientRect @box))]
          (ds/write!
            (set! (.-textContent @label) (str "box is " (js/Math.round w) "px wide")))))))
  (render
    (<< [:div {:class c/$row}
         [:span {:dom/ref box :class (css :inline-block :px-2 :bg-yellow-100)} text]
         [:span {:dom/ref label :class c/$muted}]])))

;; ## track-change and effect deps: keep the active item in view
;;
;; `(sg/track-change val (fn [env old new prev-result] ...))` runs the fn when `val`
;; changes and gives it the previous value and result. `(effect active [env] ...)` runs
;; after render when `active` changed. Together: "scroll the active item into view when
;; it changes", with the read batched through the dom scheduler.

(def items (vec (for [i (range 40)] (str "item " i))))

(defc ui-scroll-active [active]
  (ref container)

  (bind _
    (sg/track-change active
      (fn [env old new prev-result]
        (c/log! "track-change: active" old "->" new)
        new)))

  (effect active [env]
    (ds/read!
      (when-some [el (.querySelector @container "[data-active]")]
        (.scrollIntoView el #js {:block "nearest"}))))

  (render
    (<< [:div {:dom/ref container :class (css :h-32 :overflow-y-auto :border :border-gray-200 :rounded)}
         (sg/simple-seq items
           (fn [label idx]
             (<< [:div {:class [(css :px-2) (when (= idx active) (css :bg-blue-100))]
                        :data-active (when (= idx active) "true")}
                  label])))])))

;; # page

(defc ui-root []
  (ref state (atom {:a 0 :b 0 :show? true :text "resize me" :active 0}))
  (bind {:keys [a b show? text active]} (sg/watch state))

  (event ::a! [env ev e] (swap! state update :a inc))
  (event ::b! [env ev e] (swap! state update :b inc))
  (event ::show! [env ev e] (swap! state update :show? not))
  (event ::text! [env ev e] (swap! state update :text #(str % " and more")))
  (event ::active! [env {:keys [by]} e] (swap! state update :active #(-> % (+ by) (max 0) (min 39))))

  (render
    (c/page "effects"
      {"effect deps and cleanup"
       (<< (when show?
             (ui-effect-kinds a b))
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click ::a!} "a + 1"]
            [:button {:class c/$btn :on-click ::b!} "b + 1"]
            [:button {:class c/$btn :on-click ::show!} (if show? "unmount" "mount")]])

       "measuring with ds/read! and ds/write!"
       (<< (ui-measure text)
           [:button {:class [c/$btn (css :mt-2)] :on-click ::text!} "longer text"])

       "track-change and effect deps: keep the active item in view"
       (<< (ui-scroll-active active)
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click {:e ::active! :by -1}} "up"]
            [:button {:class c/$btn :on-click {:e ::active! :by 1}} "down"]
            [:button {:class c/$btn :on-click {:e ::active! :by 10}} "down 10"]
            "active " active])})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
