(ns dummy.app
  (:require
    [shadow.grove :as sg :refer (<< defc css)]
    [shadow.grove.local :as local-eng]
    [shadow.grove.runtime :as rt]))

(defn ui-test []
  (<< [:div {:class (css {:color "green"})} "hello world"]))

(defonce root-el
  (js/document.getElementById "root"))

(defonce data-ref
  (-> {}
      (atom)))

(defonce rt-ref
  (rt/prepare {} data-ref ::rt))

(defn ^:dev/after-load start []
  (sg/render rt-ref root-el (ui-test)))

(defn init []
  (local-eng/init! rt-ref)
  (start))
