(ns todomvc.simple.main
  (:require
    [shadow.experiments.arborist :as sa :refer (<< defc)]
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.db :as db]
    [todomvc.model :as m]
    [todomvc.simple.views :as views]
    [todomvc.simple.tx :as tx]
    ))

(defonce root-el (js/document.getElementById "app"))

(def init-todos
  (->> (range 100)
       (map (fn [i]
              {::m/todo-id i
               ::m/todo-text (str "item #" i)
               ::m/completed? false}))
       (vec)))

(defonce data-ref
  (-> {::m/id-seq 101
       ::m/editing nil
       ::m/current-filter :all}
      (with-meta {::db/schema (db/configure
                                {::m/todo
                                 {:type :entity
                                  :attrs {::m/todo-id [:primary-key number?]}}})})
      (db/merge-seq ::m/todo init-todos [::m/todos])
      (atom)))

(defonce app-env
  (-> {}
      (sa/init)
      (sg/env ::todomvc data-ref)))

(defn ^:dev/after-load start []
  (-> app-env
      (tx/configure) ;; this sucks but need to avoid global at all cost
      (sg/start root-el (views/ui-root))))

(defn init []
  (start))