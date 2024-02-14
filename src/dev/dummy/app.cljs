(ns dummy.app
  (:require
    [shadow.grove :as sg :refer (<< defc css)]
    [shadow.arborist.interpreted]))

(def dialog-in
  (sg/prepare-animation
    [{:opacity "0"
      :transform "scale(0.9)"}
     {:opacity "1"
      :transform "scale(1) translateX(0)"}]
    1000))

(def dialog-out
  (sg/prepare-animation
    {:opacity "0" :transform "scale(0.9)"}
    1000))

(def shake
  (sg/prepare-animation
    {:transform ["translateX(0)" "translateX(-5px)" "translateX(5px)" "translateX(0)"]}
    250))

(defc ui-dialog []
  (bind node-ref (sg/ref))

  (event ::out! [env ev e origin]
    (dialog-out @node-ref {:on-finish #(sg/dispatch-up! env {:e ::close!})}))

  (effect :mount [_]
    (dialog-in @node-ref))

  (render
    (sg/portal
      (<< [:div {:on-click ::out!
                 :dom/ref node-ref
                 :style {:position "fixed"
                         :top 0
                         :left 0
                         :width "100%"
                         :height "100%"
                         :background-color "red"}}
           "Hello World, click me"])
      )))

(def some-hiccup
  [:<>
   [:h1 "Hello Hiccup!"]
   [:ul
    (for [x ["hello" "simple" "seq"]]
      [:li x])]
   [:ul
    (for [x ["hello" "keyed" "seq"]]
      [:li {:dom/key x} x])]])

(defc ui-root []
  (bind state-ref
    (atom false))

  (bind visible?
    (sg/watch state-ref))

  (event ::show! [env ev e]
    (reset! state-ref true))

  (event ::close! [env ev e]
    (reset! state-ref false))

  (bind test-ref (atom 0))

  (event ::inc! [_ _ _]
    (swap! test-ref inc))

  (bind test (sg/watch test-ref))

  (bind num-ref (sg/ref))

  ;; triggers when `test` or `num-ref` change
  ;; `num-ref` never changes, so actually only `test`
  ;; doing this in effect to ensure it happens after render
  (effect :auto [_]
    (when (pos? test)
      (shake @num-ref)))

  (render
    (<< [:button
         {:class (css :p-4 :text-lg :border)
          :on-click ::show!}
         "click me to open dialog"]

        [:div {:style/margin-top "10px"}

         [:button {:on-click ::inc! :data-test (zero? (mod test 3))} "click me to inc"]
         [:div {:style/padding "10px"}
          [:div {:dom/ref num-ref} test " - " (mod test 3)]]]

        some-hiccup

        [:div {:x "foo"}]
        [:svg
         [:g {:x "foo"}]]

        [:form#test
         {:on-submit (fn [e]
                       (js/console.log e)
                       (.preventDefault e))}
         [:div "form test"]]

        [:button {:form "test" :type "submit"} "button outside form"]

        (when visible?
          (ui-dialog)))))

(defonce root-el
  (js/document.getElementById "root"))

(defonce data-ref
  (-> {}
      (atom)))

(defonce rt-ref
  (sg/prepare {} data-ref ::rt))

(defn ^:dev/after-load start []
  (sg/render rt-ref root-el (ui-root)))

(defn init []
  (start))
