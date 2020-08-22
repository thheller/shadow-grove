(ns todomvc.split.main
  (:require
    [shadow.experiments.grove :as sg]
    [todomvc.split.views :as views]
    [shadow.experiments.grove.worker-engine :as worker-eng]))

(defonce root-el (js/document.getElementById "app"))

(defn ^:dev/after-load start []
  (sg/start ::ui root-el (views/ui-root)))

(defn init []
  (sg/init ::ui
    {}
    [(worker-eng/init js/SHADOW_WORKER)])
  (start))