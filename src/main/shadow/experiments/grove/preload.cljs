(ns shadow.experiments.grove.preload
  (:require
    [shadow.remote.runtime.cljs.env :as renv]
    [shadow.remote.runtime.api :as p]
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.db :as db]
    [shadow.remote.runtime.obj-support :as obj-support]))

(defn get-databases [{:keys [runtime]} msg]
  (p/reply runtime msg
    {:op :db/list-databases
     ;; just keywords or more details? don't actually have any?
     :databases
     (-> (keys @sw/known-envs-ref)
         (set))}))

(defn get-tables [{:keys [runtime]} {:keys [db] :as msg}]
  (let [env-ref (get @sw/known-envs-ref db)
        data-ref (get @env-ref ::sw/data-ref)
        data @data-ref
        {::db/keys [ident-types]} (meta data)]

    (p/reply runtime msg
      {:op :db/list-tables
       ;; just keywords or more details? don't actually have any?
       :tables (conj ident-types :db/globals)})))

(defn get-rows [{:keys [runtime]} {:keys [db table offset count] :as msg}]
  (let [env-ref (get @sw/known-envs-ref db)
        data-ref (get @env-ref ::sw/data-ref)
        db @data-ref
        rows
        (if (keyword-identical? table :db/globals)
          (->> (keys db)
               (filter keyword?)
               (sort)
               (vec))
          (->> (db/all-idents-of db table)
               (map second)
               (sort)
               (vec)))]

    ;; FIXME: slice data, don't send everything

    (p/reply runtime msg
      {:op :db/list-rows
       :rows rows})))

(defn get-entry
  [{:keys [runtime obj-support]}
   {:keys [db table row] :as msg}]
  (let [env-ref (get @sw/known-envs-ref db)
        data-ref (get @env-ref ::sw/data-ref)
        db @data-ref
        ident (db/make-ident table row)
        val (get db ident)
        val-ref (obj-support/register obj-support val {:db db :table table :row row})]

    (p/reply runtime msg
      {:op :db/object
       :oid val-ref})))

(renv/init-extension! ::db-explorer #{}
  (fn [{:keys [runtime obj-support] :as env}]
    (let [svc
          {:runtime runtime
           :obj-support obj-support}]

      ;; maybe just return the ops?
      ;; dunno if this extra layer is needed
      (p/add-extension runtime
        ::db-explorer
        {:ops
         {:db/get-databases #(get-databases svc %)
          :db/get-tables #(get-tables svc %)
          :db/get-rows #(get-rows svc %)
          :db/get-entry #(get-entry svc %)}
         ;; :on-tool-disconnect #(tool-disconnect svc %)
         })
      svc))
  (fn [{:keys [runtime] :as svc}]
    (p/del-extension runtime ::db-explorer)))