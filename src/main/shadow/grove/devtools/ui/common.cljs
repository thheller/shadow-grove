(ns shadow.grove.devtools.ui.common
  (:require
    [shadow.grove.devtools :as-alias m]
    [shadow.grove :as sg :refer (defc << css)]))


(defc expandable [content]
  (bind content-ref (sg/ref))

  (bind state-ref
    (atom {:expanded false
           :needs-expand false}))

  (bind {:keys [expanded needs-expand]}
    (sg/watch state-ref))

  (effect content [env]
    (let [el @content-ref]
      (when (not= (.-scrollHeight el) (.-clientHeight el))
        (swap! state-ref assoc :needs-expand true)
        )))

  (render
    (<< [:div {:class (css :relative
                        ["& > .controls" :hidden]
                        ["&:hover > .controls" :block])}

         (when needs-expand
           (<< [:div {:class (css "controls"
                               :absolute :p-2 :bg-white
                               :border :shadow-lg
                               {:top "0px"
                                :z-index "1"})
                      :style/cursor (if expanded "zoom-out" "zoom-in")
                      :on-click #(swap! state-ref update :expanded not)}
                (if expanded "-" "+")]))

         [:div {:dom/ref content-ref
                :style/max-height (when-not expanded "129px")
                :class (css :flex-1 :overflow-auto)}
          content]])))

(def icon-close
  ;; https://github.com/sschoger/heroicons-ui/blob/master/svg/icon-x-square.svg
  (<< [:svg
       {:xmlns "http://www.w3.org/2000/svg"
        :viewBox "0 0 24 24"
        :width "24"
        :height "24"}
       [:path
        {:d "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5c0-1.1.9-2 2-2zm0 2v14h14V5H5zm8.41 7l1.42 1.41a1 1 0 1 1-1.42 1.42L12 13.4l-1.41 1.42a1 1 0 1 1-1.42-1.42L10.6 12l-1.42-1.41a1 1 0 1 1 1.42-1.42L12 10.6l1.41-1.42a1 1 0 1 1 1.42 1.42L13.4 12z"}]]))

