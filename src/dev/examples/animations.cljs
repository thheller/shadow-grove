(ns examples.animations
  "`sg/prepare-animation` wraps the Web Animations API. Keyframes are a map of CSS
   property to a vector of values, the second arg is ms or an options map. The result is
   called as `(anim node)` or `(anim node {:on-finish f :on-cancel f :on-remove f})`.
   `sg/animate` is the one-shot form. Never animate from `render`: animations start from
   effects and event slots, where the DOM node exists."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; ## enter and leave
;;
;; Toasts slide in from a `:mount` effect. Leaving is the interesting part: the record
;; must stay in KV until the animation is done, or removing it destroys the DOM and cuts
;; the animation off.
;;
;; The dismiss button carries the `(toast-kill! id)` event map. Its `:e` matches the
;; component's `::toast-kill!` slot, so the slot gets it first and the tx doesn't run
;; ([component-events](/component-events/)). The slot plays the leave animation and in
;; `:on-finish` sends the same map on with `sg/dispatch-up!`, which reaches the root and
;; runs the tx. "remove now" uses another event id that nothing intercepts: the record is
;; gone at once, no animation.
;;
;; The toasts render beside the page, like other global chrome ([suspense](/suspense/)
;; says why that matters). Their table id `::toast` is namespaced, which keeps a helper
;; namespace's table apart from app tables.

(def toast-in
  (sg/prepare-animation {:transform ["translateX(120%)" "translateX(0)"]} 300))

(def toast-out
  (sg/prepare-animation {:transform ["translateX(0)" "translateX(120%)"]}
    {:duration 300 :fill "forwards"}))

(deftx toast-add! []
  [tx-env ev e]
  (let [id (random-uuid)]
    (assoc-in tx-env [::toast id] {:id id :created (js/Date.now) :text (str "toast " (count (::toast tx-env)))})))

(deftx toast-kill! [id]
  [tx-env ev e]
  (c/log! "tx toast-kill! ran, removing" (subs (str id) 0 8))
  (update tx-env ::toast dissoc id))

;; the same removal under another event id, nothing intercepts it
(deftx toast-kill-now! [id]
  [tx-env ev e]
  (c/log! "tx toast-kill-now! ran")
  (update tx-env ::toast dissoc id))

(defc ui-toast [id]
  (ref el)
  (bind {:keys [text]} (sg/kv-lookup ::toast id))

  (effect :mount [env]
    (toast-in @el))

  ;; gets (toast-kill! id) before the root does
  (event ::toast-kill! [env ev e]
    (c/log! "slot ::toast-kill! intercepted, animating")
    (toast-out @el {:on-finish #(sg/dispatch-up! env ev)}))

  (render
    (<< [:div {:dom/ref el :class (css :flex :items-center :gap-2 :p-3 :mb-2 :rounded :shadow-md :bg-gray-800 :text-white :text-sm)}
         text
         [:button {:class [c/$btn (css :ml-auto)] :on-click (toast-kill! id)} "dismiss"]
         [:button {:class c/$btn :on-click (toast-kill-now! id)} "remove now"]])))

(defn ?toast-ids [env]
  (->> (vals (::toast env)) (sort-by :created) (mapv :id)))

(defc ui-toasts []
  (bind ids (sg/query ?toast-ids))
  (render
    (<< [:div (css :fixed :right-4 :bottom-4 :w-80 :z-50)
         (sg/keyed-seq ids identity ui-toast)])))

;; ## sg/animate
;;
;; The one-shot form takes the node, keyframes, options and the callbacks in one call.

(defc ui-shake []
  (ref box)
  (event ::shake! [env ev e]
    (sg/animate @box
      {:transform ["translateX(0)" "translateX(-8px)" "translateX(8px)" "translateX(0)"]}
      {:duration 200 :iterations 2}
      {:on-finish #(c/log! "shake finished")}))
  (render
    (<< [:div {:class c/$row}
         [:div {:dom/ref box :class (css :w-16 :h-16 :rounded :bg-blue-500)}]
         [:button {:class c/$btn :on-click ::shake!} "shake"]])))

;; # page

(defc ui-root []
  (render
    (<< (c/page "animations"
          {"enter and leave"
           [:button {:class c/$btn :on-click (toast-add!)} "add toast"]

           "sg/animate"
           (ui-shake)})
        ;; beside the page, like global chrome
        (ui-toasts))))

(defn init []
  (sg/add-kv-table app ::toast {:primary-key :id} {})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
