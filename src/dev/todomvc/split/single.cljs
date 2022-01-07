(ns todomvc.split.single
  (:require
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.local :as local]
    [todomvc.split.env :as env]
    [todomvc.split.views :as views]
    [todomvc.split.db]
    [shadow.experiments.grove.local :as local-eng]))

;; this is only using the main thread (no worker)
;; but the logic is still separated
;; views and db logic are identical to the split worker version
;; only the initialization changes a tiny bit

(defonce root-el (js/document.getElementById "app"))

(defn ^:dev/after-load start []
  (sg/render env/rt-ref root-el (views/ui-root)))

(defn init []
  (local/init! env/rt-ref)
  (start))