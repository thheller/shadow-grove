(ns todomvc.split.worker
  (:require
    [shadow.experiments.grove-worker :as sg]
    [shadow.experiments.grove.db :as db]
    [todomvc.model :as m]
    [todomvc.split.tx :as tx]))

(def init-todos
  (->> (range 10)
       (map (fn [i]
              {::m/todo-id i
               ::m/todo-text (str "item #" i)
               ::m/completed? false}))
       (vec)))

(defonce data-ref
  (-> {::m/id-seq 11
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
      (sg/prepare data-ref)))

;; FIXME: need to figure out hot-reload in worker. thats currently disabled.
(defn ^:dev/after-load start []
  (tx/configure app-env))

(defn init []
  (set! app-env (sg/init app-env))
  (start))