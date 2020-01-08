(ns todomvc.split.main
  (:require
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.grove :as sg]
    [todomvc.split.views :as views]
    ))

(defonce root-el (js/document.getElementById "app"))

(defonce app-env (sa/init {}))

(defn ^:dev/after-load start []
  (sg/start app-env root-el (views/ui-root)))

(defn init []
  (set! app-env (sg/init app-env ::todomvc js/SHADOW_WORKER))
  (start))