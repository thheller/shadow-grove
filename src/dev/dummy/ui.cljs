(ns dummy.ui
  (:require
    [shadow.experiments.grove.local :as local-eng]
    [shadow.experiments.grove.ui.dnd-sortable :as dnd]
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove :as sg :refer (defc <<)]
    ))

(defc ui-dnd []
  (bind items-ref
    (atom
      [{:id 1 :text "foo1"}
       {:id 2 :text "foo2"}
       {:id 3 :text "foo3"}
       {:id 4 :text "foo4"}]))

  (bind items
    (sg/watch items-ref))

  (event ::dnd/sorted! [env ev e]
    (reset! items-ref (:items-after ev))
    (js/console.log "sorted" ev))

  (render
    (<< [:div {:style "font-size: 30px;"}
         (dnd/keyed-seq
           items
           :id
           (fn [item {::dnd/keys [dragging hovering] :as opts}]
             (<< [:div {:style {:border (str "1px solid" (if hovering " blue" " green"))
                                :margin-top "10px"
                                :padding "9px"
                                :opacity (when dragging "1" "0.3")}
                        ::dnd/target opts}
                  [:span {:style {:cursor "move"} ::dnd/draggable opts} "drag-me"]
                  (:text item)
                  [:div
                   (pr-str opts)]])
             ))])))

(defonce root-el
  (js/document.getElementById "root"))

(defn ^:dev/after-load start []
  (sg/start ::ui (ui-dnd)))

(defonce data-ref
  (-> {}
      (atom)))

(defonce rt-ref
  (rt/prepare {} data-ref ::rt))

(defn init []
  (local-eng/init! rt-ref)
  (sg/init-root rt-ref ::ui root-el {})
  (start))