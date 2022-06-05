(ns shadow.grove.examples.env
  (:require
    [cljs.env :as cljs-env]
    [shadow.grove.runtime :as gr]
    [shadow.grove.db :as db]
    [shadow.grove.examples.model :as m]))

(defonce data-ref
  (-> {::m/example-tab :result
       ::m/example-result "No Result yet."}
      (db/configure m/schema)
      (atom)))

(defonce rt-ref
  (-> {::m/compile-state-ref (cljs-env/default-compiler-env)}
      (gr/prepare data-ref ::app)))