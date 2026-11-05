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
;; | any other value | meant as "when that value becomes `not=` to the previous one" |
;;
;; Watch the log: `a + 1` runs `:render`, `:auto` and the `a` effect, each after the
;; cleanup of its previous run. `b + 1` runs only `:render`... and the `a` effect too:
;; currently the previous deps aren't stored, so an effect with a deps value runs
;; whenever its slot runs again, which is when `deps` **or any bind its body uses**
;; changes, like `:auto`. The body here uses `b`. Don't rely on value deps to filter
;; more than `:auto` does.
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

;; ## sg/mount-effect, sg/render-effect and hook
;;
;; `sg/mount-effect` and `sg/render-effect` are the function forms, callable inside a
;; `bind` or `hook`. They claim the slot. Like other claims, a `when` that turns false
;; later doesn't stop one that already registered, it stays until unmount
;; ([bind-helpers](/bind-helpers/)).
;;
;; `hook` is the older step form: it runs during the computation and its value isn't
;; usable. The remaining legitimate uses are subscriptions like the `sg/effect` below,
;; and gating a subtree on a flag: `(hook (when-not ready? (sg/suspend!)))`
;; ([suspense](/suspense/)).
;;
;; `a + 1` in the first demo also renders this one.

(defc ui-effect-fns [n]
  (hook (sg/mount-effect (fn [env] (c/log! "sg/mount-effect ran"))))
  (hook (sg/render-effect (fn [env] (c/log! "sg/render-effect ran, n =" n))))
  (render
    (<< [:div "n = " n])))

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

;; ## track-change and sg/effect: keep the active item in view
;;
;; `(sg/track-change val (fn [env old new prev-result] ...))` runs the fn when `val`
;; changes and gives it the previous value and result. `(sg/effect val f)` in a hook runs
;; `f` after render whenever `val` changed. Together: "scroll the active item into view
;; when it changes", with the read batched through the dom scheduler.

(def items (vec (for [i (range 40)] (str "item " i))))

(defc ui-scroll-active [active]
  (ref container)

  (bind _
    (sg/track-change active
      (fn [env old new prev-result]
        (c/log! "track-change: active" old "->" new)
        new)))

  (hook
    (sg/effect active
      (fn [env]
        (ds/read!
          (when-some [el (.querySelector @container "[data-active]")]
            (.scrollIntoView el #js {:block "nearest"}))))))

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

       "sg/mount-effect, sg/render-effect and hook"
       (ui-effect-fns a)

       "measuring with ds/read! and ds/write!"
       (<< (ui-measure text)
           [:button {:class [c/$btn (css :mt-2)] :on-click ::text!} "longer text"])

       "track-change and sg/effect: keep the active item in view"
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
