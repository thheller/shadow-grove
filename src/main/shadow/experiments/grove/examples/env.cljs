(ns shadow.experiments.grove.examples.env
  (:require
    [cljs.env :as cljs-env]
    [shadow.experiments.grove.runtime :as gr]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.examples.model :as m]))

(defonce data-ref
  (-> {::m/example-tab :result
       ::m/example-result "No Result yet."}
      (db/configure m/schema)
      (atom)))

(defonce rt-ref
  (-> {::m/compile-state-ref (cljs-env/default-compiler-env)}
      (gr/prepare data-ref ::app)))