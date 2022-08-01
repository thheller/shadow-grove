(ns dummy.app
  (:require
    [shadow.grove :as sg :refer (<< defc css)]
    [shadow.grove.css-fx :as fx]))

(def $fade
  (css
    ["&-enter"
     {:opacity "0"
      :transform "scale(0.9)"}]
    ["&-enter-active"
     {:opacity "1"
      :transform "translateX(0)"
      :transition "opacity 300ms, transform 300ms"}]
    ["&-exit"
     {:opacity "1"}]
    ["&-exit-active"
     {:opacity "0"
      :transform "scale(0.9)"
      :transition "opacity 300ms, transform 300ms"}]))

(defc ui-dialog []
  (event ::out! [env ev e origin]
    (fx/trigger-out! origin
      (fn []
        (sg/dispatch-up! env {:e ::close!})
        )))

  (render
    (sg/portal
      (fx/transition-group
        {:class $fade :timeout 300}
        (<< [:div {:on-click ::out!
                   :class (css :fixed :inset-0 :bg-red-700)}
             "Hello World, click me"])))))

(defc ui-root []
  (bind state-ref
    (atom false))

  (bind visible?
    (sg/watch state-ref))

  (event ::show! [env ev e]
    (reset! state-ref true))

  (event ::close! [env ev e]
    (reset! state-ref false))

  (render
    (<< [:div
         {:class (css :p-4 :text-lg :border)
          :on-click ::show!}
         "click me"]

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
