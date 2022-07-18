(ns dummy.app
  (:require
    [shadow.grove :as sg :refer (<< defc css)]
    [shadow.grove.local :as local-eng]
    [shadow.grove.runtime :as rt]))

(def a-def (css :green ["&:hover" :red {:foo "bar"}]))

(defn ui-test []
  (<< [:div {:class (css :px-4 {:color "green"})} "hello world"]
      [:div {:class (css :text-lg)} "yo"]))

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
