(ns examples.keyboard
  "`shadow.grove.keyboard`: `{::keyboard/listen true}` on a focusable node makes key
   presses on it call the `::keyboard/<key>` event slots of the component that owns the
   node. Keyboard input stays in components, like other local work. A slot that should
   change data continues with `sg/run-tx` or `sg/dispatch-up!`
   ([component-events](/component-events/)).

   - Slot ids are `::keyboard/` plus the key, lowercased, with modifiers in the order
     `ctrl+alt+meta+shift`: `::keyboard/escape`, `::keyboard/arrowdown`,
     `::keyboard/ctrl+enter`, `::keyboard/alt+t`.
   - The slot's `ev` is the vector `[event-id]`, not a map. `e` is the goog key event.
   - It only works inside a `defc`. Without an owning component the attribute does
     nothing.
   - The key handler is disposed when the attribute goes away, also when its fragment
     is destroyed."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.keyboard :as keyboard]))

(defonce app (sg/get-runtime :app))

;; ## ctrl+enter in a textarea
;;
;; The slot reads the text from the node, clears it, and records the entry. Prevent the
;; default when you consume a key, here so ctrl+enter doesn't also insert a newline.

(defc ui-code-input [entries]
  (ref input)
  (event ::keyboard/ctrl+enter [env ev e]
    (.preventDefault e)
    (c/log! "ctrl+enter, ev =" (pr-str ev))
    (let [val (.-value @input)]
      (set! @input -value "")
      (swap! entries conj val)))
  (render
    (<< [:textarea#code {:dom/ref input ::keyboard/listen true :class [c/$input (css :w-full :font-mono)] :rows 3
                         :placeholder "type, then ctrl+enter"}])))

;; ## arrow keys on focusable panels
;;
;; Keys only arrive while the node has focus. A `div` needs `:tab-index`: `0` makes it
;; reachable with tab, `-1` only by click or script. With several listening panels, give
;; only the active one `0`. Click a panel, then use arrow up/down and escape.
;;
;; If a listening node contains text inputs, check `(.-target e)` and ignore the arrows
;; that come from them.

(defc ui-panel [label active? on-activate]
  (ref state (atom {:focus 0}))
  (bind focus (sg/watch state [:focus]))
  (event ::keyboard/arrowdown [env ev e]
    (.preventDefault e)
    (swap! state update :focus #(min 4 (inc %))))
  (event ::keyboard/arrowup [env ev e]
    (.preventDefault e)
    (swap! state update :focus #(max 0 (dec %))))
  (event ::keyboard/escape [env ev e]
    (swap! state assoc :focus 0))
  (render
    (<< [:div {::keyboard/listen true
               :tab-index (if active? 0 -1)
               :data-panel label
               :on-focus on-activate
               :class [(css :flex-1 :border :rounded :p-2 [:focus :outline-none :border-blue-500])
                       (when active? (css :bg-blue-50))]}
         [:div (css :text-sm :font-semibold) label (when active? " (active)")]
         (sg/simple-seq (range 5)
           (fn [i]
             (<< [:div {:class (when (= i focus) (css :bg-blue-200)) :data-focus (when (= i focus) "true")}
                  "row " i])))])))

;; ## nested listeners
;;
;; Only the slots of the component owning the listening node are checked. There is no
;; bubbling through components and no "Unhandled Event" for keys without a slot. The DOM
;; event itself still bubbles: an ancestor node with its own `::keyboard/listen` sees the
;; key too, and its owner's slots run. Enter in the inner input logs both. Escape logs
;; only the inner one, because its slot stops propagation. Stop it (or
;; `(.preventDefault e)` and check `defaultPrevented`) when you consume a key.

(defc ui-inner []
  (event ::keyboard/enter [env ev e]
    (c/log! "inner: enter"))
  (event ::keyboard/escape [env ev e]
    (c/log! "inner: escape, stopping it")
    (.stopPropagation e))
  (render
    (<< [:input#inner {:class c/$input ::keyboard/listen true :placeholder "focus me, press enter / escape"}])))

(defc ui-outer []
  (event ::keyboard/enter [env ev e]
    (c/log! "outer: enter"))
  (event ::keyboard/escape [env ev e]
    (c/log! "outer: escape"))
  (render
    (<< [:div#outer {::keyboard/listen true :class (css :p-2 :bg-gray-50 :rounded)}
         "outer listens too: "
         (ui-inner)])))

;; # page

(defc ui-root []
  (ref entries (atom []))
  (ref panels (atom :a))
  (bind submitted (sg/watch entries))
  (bind active (sg/watch panels))
  (event ::activate! [env {:keys [panel]} e]
    (reset! panels panel))

  (render
    (c/page "keyboard"
      {"ctrl+enter in a textarea"
       (<< (ui-code-input entries)
           [:div#submitted {:class c/$muted} "submitted: " (pr-str submitted)])

       "arrow keys on focusable panels"
       [:div (css :flex :gap-4)
        (ui-panel "a" (= active :a) {:e ::activate! :panel :a})
        (ui-panel "b" (= active :b) {:e ::activate! :panel :b})]

       "nested listeners"
       (ui-outer)})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
