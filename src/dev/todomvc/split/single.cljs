(ns todomvc.split.single
  (:require
    [shadow.grove :as sg]
    [shadow.grove.local :as local]
    [todomvc.split.env :as env]
    [todomvc.split.views :as views]
    [todomvc.split.db]
    [shadow.grove.local :as local-eng]))

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