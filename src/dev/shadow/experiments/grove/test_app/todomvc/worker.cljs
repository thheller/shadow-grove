(ns shadow.experiments.grove.test-app.todomvc.worker
  (:require
    [shadow.experiments.grove-worker :as sg]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.test-app.todomvc.model :as m]
    [cognitect.transit :as transit]))

(def init-todos
  (->> (range 100)
       (map (fn [i]
              {::m/todo-id i
               ::m/todo-text (str "item #" i)
               ::m/completed? false}))
       (vec)))

(defonce data-ref
  (-> {::m/id-seq 101
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

(sg/reg-event-fx app-env ::m/create-new!
  []
  (fn [{:keys [db]} new-todo]
    (let [{::m/keys [id-seq]} db]
      {:db
       (let [new-todo (assoc new-todo ::m/todo-id id-seq)]
         (-> db
             (update ::m/id-seq inc)
             (db/add ::m/todo new-todo)))})))

(sg/reg-event-fx app-env ::m/delete!
  []
  (fn [{:keys [db] :as env} todo]
    {:db (dissoc db todo)}))

(sg/reg-event-fx app-env ::m/shuffle!
  []
  (fn [{:keys [db] :as env} todo]
    {:db (update db ::m/todos (fn [current] (vec (shuffle current))))}))

(sg/reg-event-fx app-env ::m/set-filter!
  []
  (fn [{:keys [db] :as env} new-filter]
    {:db
     (assoc db ::m/current-filter new-filter)}))

(sg/reg-event-fx app-env ::m/toggle-completed!
  []
  (fn [{:keys [db] :as env} todo]
    {:db
     (update-in db [todo ::m/completed?] not)}))

(sg/reg-event-fx app-env ::m/edit-start!
  []
  (fn [{:keys [db] :as env} todo]
    {:db
     (assoc db ::m/editing todo)}))

(sg/reg-event-fx app-env ::m/edit-save!
  []
  (fn [{:keys [db] :as env} {:keys [todo text]}]
    {:db
     (-> db
         (assoc-in [todo ::m/todo-text] text)
         (assoc ::m/editing nil))}))

(sg/reg-event-fx app-env ::m/edit-cancel!
  []
  (fn [{:keys [db] :as env} todo]
    {:db
     (assoc db ::m/editing nil)}))

(sg/reg-event-fx app-env ::m/clear-completed!
  []
  (fn [{:keys [db] :as env} _]
    {:db
     (reduce
       (fn [db {::m/keys [completed?] :as todo}]
         (if-not completed?
           db
           (db/remove db todo)))
       db
       (db/all-of db ::m/todo))}))

(sg/reg-event-fx app-env ::m/toggle-all!
  []
  (fn [{:keys [db] :as env} {:keys [completed?]}]
    {:db
     (reduce
       (fn [db ident]
         (assoc-in db [ident ::m/completed?] completed?))
       db
       (db/all-idents-of db ::m/todo))}))

;; FIXME: these need to be customizable so don't move to app
;; need to support custom handlers, maybe just as options though
(def tr (transit/reader :json))
(def tw (transit/writer :json))

(defn ^:dev/after-load start [])

(defn init []
  (set! app-env (sg/init app-env tr tw))
  (start))