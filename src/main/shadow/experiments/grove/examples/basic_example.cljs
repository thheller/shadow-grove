(ns shadow.experiments.grove.examples.basic-example
  (:require
    [shadow.experiments.grove :as sg :refer (defc <<)]))

(defc ui-counter []
  ;; create a local state atom once on mount
  (bind state-ref
    (atom 0))

  ;; watch state-ref and bind deref result to val
  (bind val
    (sg/watch state-ref))

  ;; renders the component, re-rendered whenever val changes
  ;; fragment is mostly static
  ;; will only update val in DOM, no VDOM diffing for the rest
  (render
    (<< [:div.p-2
         [:button
          {:class "inline border shadow p-2"
           :on-click ::inc!}
          "click me"]
         [:div.inline.text-xl.p-2
          "counter: " val]]))

  ;; handle click event in declarative way
  ;; keeps :on-click (fn [dom-event] ...) out of the HTML structure
  (event ::inc! [env e dom-event]
    (swap! state-ref inc)))

(defn example []
  (ui-counter))

