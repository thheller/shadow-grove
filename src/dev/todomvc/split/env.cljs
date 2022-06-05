(ns todomvc.split.env
  (:require
    [shadow.grove.db :as db]
    [shadow.grove.runtime :as rt]
    [todomvc.model :as m]))

(defonce data-ref
  (-> {::m/id-seq 101
       ::m/editing nil
       ::m/current-filter :all}
      (db/configure m/schema)
      (atom)))

(defonce rt-ref
  (-> {}
      (rt/prepare data-ref ::db)))

