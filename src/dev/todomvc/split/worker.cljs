(ns todomvc.split.worker
  (:require
    [shadow.grove.worker :as sw]
    [todomvc.split.env :as env]
    [todomvc.split.db]))

;; this is running in the worker thread

(defn ^:dev/after-load after-load []
  (sw/refresh-all-queries! env/rt-ref))

(defn init []
  (sw/init! env/rt-ref))