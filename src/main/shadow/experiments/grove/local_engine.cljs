(ns shadow.experiments.grove.local-engine
  (:require [shadow.experiments.grove.protocols :as gp]))

(deftype LocalEngine [data-ref]
  gp/IQueryEngine
  (query-init [this key query config callback])
  (query-destroy [this key])
  ;; FIXME: one shot query that can't be updated later?
  ;; can be done by helper method over init/destroy but engine
  ;; would still do a bunch of needless work
  ;; only had one case where this might have been useful, maybe it isn't worth adding?
  ;; (query-once [this query config callback])
  (transact! [this tx with-return?])

  gp/IStreamEngine
  (stream-init [this env stream-id stream-key opts callback])
  (stream-destroy [this stream-id stream-key])
  (stream-clear [this stream-key]))

(defn init [data-key]
  (fn [env]
    (let [data-ref (get env data-key)]
      (assoc env ::gp/query-engine (LocalEngine. data-ref)))))