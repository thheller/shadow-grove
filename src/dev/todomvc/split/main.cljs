(ns todomvc.split.main
  (:require
    [shadow.grove :as sg]
    [shadow.grove.worker-engine :as worker]
    [todomvc.split.views :as views]))

;; this is running in the main thread

(defonce root-el (js/document.getElementById "app"))

(defn ^:dev/after-load start []
  (sg/start ::ui root-el (views/ui-root)))

(defn init []
  (sg/init ::ui
    {}
    ;; SHADOW_WORKER is created in the .html file to be started as early as possible
    ;; could be started here but this is saving a couple ms when doing it in the HTML
    ;; see examples/todomvc-split/index.html
    [(worker/init js/SHADOW_WORKER)])
  (start))