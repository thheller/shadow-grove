(ns todomvc.split.worker
  (:require
    [shadow.experiments.grove.worker :as sw]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.eql-query :as eql]
    [todomvc.model :as m]))

(def schema
  {::m/todo
   {:type :entity
    :attrs {::m/todo-id [:primary-key number?]}}})

(defonce data-ref
  (-> {::m/id-seq 101
       ::m/editing nil
       ::m/current-filter :all}
      (db/configure schema)
      (atom)))

(defonce app-ref
  (-> {}
      (sw/prepare data-ref ::db)))

;; FIXME: counting lazy seq ..
(defmethod eql/attr ::m/num-active
  [env db current _ params]
  (->> (db/all-of db ::m/todo)
       (remove ::m/completed?)
       (count)))

(defmethod eql/attr ::m/num-completed
  [env db current _ params]
  (->> (db/all-of db ::m/todo)
       (filter ::m/completed?)
       (count)))

(defmethod eql/attr ::m/num-total
  [env db current _ params]
  (count (db/all-of db ::m/todo)))

(defmethod eql/attr ::m/editing?
  [env db current _ params]
  (= (::m/editing db) (:db/ident current)))

(defmethod eql/attr ::m/filtered-todos
  [env {::m/keys [current-filter] :as db} current _ params]
  (let [filter-fn
        (case current-filter
          :all
          (fn [x] true)
          :active
          #(not (::m/completed? %))
          :completed
          #(true? (::m/completed? %)))]

    (->> (db/all-of db ::m/todo)
         (filter filter-fn)
         (map :db/ident)
         (sort)
         (vec))))

(defn without [items del]
  (into [] (remove #{del}) items))

(defn r-> [init rfn coll]
  (reduce rfn init coll))

(sw/reg-event-fx app-ref ::m/create-new!
  []
  (fn [{:keys [db]} {::m/keys [todo-text]}]
    (let [{::m/keys [id-seq]} db]
      {:db
       (let [new-todo {::m/todo-id id-seq ::m/todo-text todo-text}]
         (-> db
             (update ::m/id-seq inc)
             (db/add ::m/todo new-todo [::m/todos])))})))

(sw/reg-event-fx app-ref ::m/delete!
  []
  (fn [{:keys [db] :as env} {:keys [todo]}]
    {:db (-> db
             (dissoc todo)
             (update ::m/todos without todo))}))

(sw/reg-event-fx app-ref ::m/set-filter!
  []
  (fn [{:keys [db] :as env} {:keys [filter]}]
    {:db
     (assoc db ::m/current-filter filter)}))

(sw/reg-event-fx app-ref ::m/toggle-completed!
  []
  (fn [{:keys [db] :as env} {:keys [todo]}]
    {:db
     (update-in db [todo ::m/completed?] not)}))

(sw/reg-event-fx app-ref ::m/edit-start!
  []
  (fn [{:keys [db] :as env} {:keys [todo]}]
    {:db
     (assoc db ::m/editing todo)}))

(sw/reg-event-fx app-ref ::m/edit-save!
  []
  (fn [{:keys [db] :as env} {:keys [todo text]}]
    {:db
     (-> db
         (assoc-in [todo ::m/todo-text] text)
         (assoc ::m/editing nil))}))

(sw/reg-event-fx app-ref ::m/edit-cancel!
  []
  (fn [{:keys [db] :as env} {:keys [todo]}]
    {:db
     (assoc db ::m/editing nil)}))

(sw/reg-event-fx app-ref ::m/clear-completed!
  []
  (fn [{:keys [db] :as env} _]
    {:db (-> db
             (r->
               (fn [db {::m/keys [completed?] :as todo}]
                 (if-not completed?
                   db
                   (db/remove db todo)))
               (db/all-of db ::m/todo))
             (update ::m/todos (fn [current]
                                 (into [] (remove #(get-in db [% ::m/completed?])) current))))
     }))

(sw/reg-event-fx app-ref ::m/toggle-all!
  []
  (fn [{:keys [db] :as env} {:keys [completed?]}]
    {:db
     (reduce
       (fn [db ident]
         (assoc-in db [ident ::m/completed?] completed?))
       db
       (db/all-idents-of db ::m/todo))}))

(defn ^:dev/after-load after-load []
  (sw/refresh-all-queries! app-ref))

(defn init []
  (sw/init! app-ref))