(ns shadow.grove.examples.env
  (:require
    [cljs.env :as cljs-env]
    [shadow.resource :as rc]
    [shadow.css.build :as cb]
    [shadow.grove.runtime :as gr]
    [shadow.grove.db :as db]
    [shadow.grove.examples.model :as m]
    [cljs.reader :as reader]))

(defonce data-ref
  (-> {::m/example-tab :result
       ::m/example-result "No Result yet."}
      (db/configure m/schema)
      (atom)))

(def colors-edn
  (rc/inline "shadow/css/colors.edn"))

(def aliases-edn
  (rc/inline "shadow/css/aliases.edn"))

(def css-base
  (-> (cb/init)
      (update :colors merge (reader/read-string colors-edn))
      (update :aliases merge (reader/read-string aliases-edn))
      (cb/generate-color-aliases)
      (cb/generate-spacing-aliases)))

(defonce rt-ref
  (-> {::m/compile-state-ref (cljs-env/default-compiler-env)}
      (gr/prepare data-ref ::app)))