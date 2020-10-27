(ns shadow.experiments.grove.preload
  (:require
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.shared :as shared]
    [shadow.cljs.devtools.client.shared :as cljs-shared]
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.runtime :as rt]
    [clojure.core.protocols :as cp]))

(defn get-databases [{:keys [runtime]} msg]
  (shared/reply runtime msg
    {:op :db/list-databases
     ;; just keywords or more details? don't actually have any?
     :databases
     (-> (keys @rt/known-runtimes-ref)
         (set))}))

(defn get-tables [{:keys [runtime]} {:keys [db] :as msg}]
  (let [env-ref (get @rt/known-runtimes-ref db)
        data-ref (get @env-ref ::rt/data-ref)
        data @data-ref
        {::db/keys [ident-types]} (meta data)]

    (shared/reply runtime msg
      {:op :db/list-tables
       ;; just keywords or more details? don't actually have any?
       :tables (conj ident-types :db/globals)})))

(defn get-table-columns [{:keys [runtime]} {:keys [db table] :as msg}]
  (let [env-ref (get @rt/known-runtimes-ref db)
        data-ref (get @env-ref ::rt/data-ref)
        db @data-ref

        ;; FIXME: likely doesn't need all rows, should just take a random sample
        known-keys-of-table
        (->> (db/all-of db table)
             (mapcat keys)
             (set))]

    (shared/reply runtime msg
      {:op :db/list-table-columns
       :columns known-keys-of-table})))

(defn get-rows [{:keys [runtime]} {:keys [db table offset count] :as msg}]
  (let [env-ref (get @rt/known-runtimes-ref db)
        data-ref (get @env-ref ::rt/data-ref)
        data @data-ref
        rows
        (->> (db/all-of data table)
             (sort-by :db/ident)
             (vec))]

    ;; FIXME: slice data, don't send everything

    (shared/reply runtime msg
      {:op :db/list-rows
       :rows rows})))

(defn get-entry
  [{:keys [runtime]}
   {:keys [db table row] :as msg}]
  (let [env-ref (get @rt/known-runtimes-ref db)
        data-ref (get @env-ref ::rt/data-ref)
        db @data-ref
        ident (if (= table :db/globals) row (db/make-ident table row))
        val (get db ident)]

    (shared/reply runtime msg
      {:op :db/entry :row val})))

(cljs-shared/add-plugin! ::db-explorer #{}
  (fn [{:keys [runtime] :as env}]
    (let [svc
          {:runtime runtime}]

      ;; maybe just return the ops?
      ;; dunno if this extra layer is needed
      (p/add-extension runtime
        ::db-explorer
        {:ops
         {:db/get-databases #(get-databases svc %)
          :db/get-tables #(get-tables svc %)
          :db/get-table-columns #(get-table-columns svc %)
          :db/get-rows #(get-rows svc %)
          :db/get-entry #(get-entry svc %)}
         ;; :on-tool-disconnect #(tool-disconnect svc %)
         })
      svc))
  (fn [{:keys [runtime] :as svc}]
    (p/del-extension runtime ::db-explorer)))

(extend-type sw/ActiveQuery
  cp/Datafiable
  (datafy [this]
    {:env (.-env this)
     :query-id (.-query-id this)
     :query (.-query this)
     :read-keys (.-read-keys this)
     :read-result (.-read-result this)
     :pending? (.-pending? this)
     :destroyed? (.-destroyed? this)}))