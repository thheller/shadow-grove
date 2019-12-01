;; FIXME: should remain identical to simple.tx
;; need to sort out ns structure it can be actually identical
(ns todomvc.split.tx
  (:require
    [shadow.experiments.grove-worker :as sg]
    [shadow.experiments.grove.db :as db]
    [todomvc.model :as m]))

(defmethod db/query-calc ::m/num-active
  [env db current _ params]
  (->> (db/all-of db ::m/todo)
       (remove ::m/completed?)
       (count)))

(defmethod db/query-calc ::m/num-completed
  [env db current _ params]
  (->> (db/all-of db ::m/todo)
       (filter ::m/completed?)
       (count)))

(defmethod db/query-calc ::m/num-total
  [env db current _ params]
  (count (db/all-idents-of db ::m/todo)))

(defmethod db/query-calc ::m/editing?
  [env db current _ params]
  (= (::m/editing db) (::db/ident current)))

(defmethod db/query-calc ::m/filtered-todos
  [env {::m/keys [current-filter todos] :as db} current _ params]
  (let [filter-fn
        (case current-filter
          :all
          (fn [x] true)
          :active
          #(not (::m/completed? %))
          :completed
          #(true? (::m/completed? %)))]

    (->> todos
         (map #(get db %))
         (filter filter-fn)
         ;; (sort-by ::todo-id)
         (map ::db/ident)
         (vec))))

(defn without [items del]
  (into [] (remove #{del}) items))

(defn r-> [init rfn coll]
  (reduce rfn init coll))

(defn configure [env]
  (sg/reg-event-fx env ::m/create-new!
    []
    (fn [{:keys [db]} new-todo]
      (let [{::m/keys [id-seq]} db]
        {:db
         (let [new-todo (assoc new-todo ::m/todo-id id-seq)]
           (-> db
               (update ::m/id-seq inc)
               (db/add ::m/todo new-todo [::m/todos])))})))

  (sg/reg-event-fx env ::m/delete!
    []
    (fn [{:keys [db] :as env} todo]
      {:db (-> db
               (dissoc todo)
               (update ::m/todos without todo))}))

  (sg/reg-event-fx env ::m/shuffle!
    []
    (fn [{:keys [db] :as env} todo]
      {:db (update db ::m/todos (fn [current] (vec (shuffle current))))}))

  (sg/reg-event-fx env ::m/set-filter!
    []
    (fn [{:keys [db] :as env} new-filter]
      {:db
       (assoc db ::m/current-filter new-filter)}))

  (sg/reg-event-fx env ::m/toggle-completed!
    []
    (fn [{:keys [db] :as env} todo]
      {:db
       (update-in db [todo ::m/completed?] not)}))

  (sg/reg-event-fx env ::m/edit-start!
    []
    (fn [{:keys [db] :as env} todo]
      {:db
       (assoc db ::m/editing todo)}))

  (sg/reg-event-fx env ::m/edit-save!
    []
    (fn [{:keys [db] :as env} {:keys [todo text]}]
      {:db
       (-> db
           (assoc-in [todo ::m/todo-text] text)
           (assoc ::m/editing nil))}))

  (sg/reg-event-fx env ::m/edit-cancel!
    []
    (fn [{:keys [db] :as env} todo]
      {:db
       (assoc db ::m/editing nil)}))

  (sg/reg-event-fx env ::m/clear-completed!
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

  (sg/reg-event-fx env ::m/toggle-all!
    []
    (fn [{:keys [db] :as env} {:keys [completed?]}]
      {:db
       (reduce
         (fn [db ident]
           (assoc-in db [ident ::m/completed?] completed?))
         db
         (db/all-idents-of db ::m/todo))}))

  env)
