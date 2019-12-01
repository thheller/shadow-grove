(ns shadow.experiments.grove.test-app.todomvc
  (:require
    [shadow.experiments.arborist :as sa :refer (<< defc)]
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.arborist.effects :as sfx]))

(defc todo-item
  {::edit-update!
   (fn [env e todo]
     (case (.-which e)
       13 ;; enter
       (.. e -target (blur))
       27 ;; escape
       (sg/run-tx env ::edit-cancel! todo)
       ;; default do nothing
       nil))

   ::edit-complete!
   (fn [env e todo]
     (sg/run-tx env ::edit-save! {:todo todo :text (.. e -target -value)}))

   ::toggle-completed! sg/tx
   ::edit-start! sg/tx}
  [todo]
  [{::keys [completed? editing? todo-text]}
   (sg/query todo [::todo-text
                   ::editing?
                   ::completed?])

   test-fx
   (sfx/make-test-effect {:duration 200})

   ;; FIXME: I want all events to be "declarative"
   ;; but allowing regular functions would make certain cases easier
   ;; especially related to hooks that want to act as event handlers
   ;; doesn't matter in this example since ::delete! works just as well
   ;; but I think allowing it makes sense? just makes server-side
   ;; harder should I ever implement that
   delete!
   (fn [env e todo]
     (test-fx #(sg/run-tx env ::delete! todo)))]

  (<< [:li {::sfx/effect test-fx
            :class {:completed completed?
                    :editing editing?}}
       [:div.view
        [:input.toggle {:type "checkbox"
                        :checked completed?
                        :on-change [::toggle-completed! todo]}]
        [:label {:on-dblclick [::edit-start! todo]} todo-text]
        [:button.destroy {:on-click [delete! todo]}]]

       (when editing?
         (<< [:input#edit.edit {:autofocus true
                                :on-keydown [::edit-update! todo]
                                :on-blur [::edit-complete! todo]
                                :value todo-text}]))]))

(defc ui-filter-select []
  [{::keys [current-filter]}
   (sg/query [::current-filter])

   filter-options
   [{:label "All" :value :all}
    {:label "Active" :value :active}
    {:label "Completed" :value :completed}]]

  (<< [:ul.filters
       (sa/render-seq filter-options :value
         (fn [{:keys [label value]}]
           (<< [:li [:a
                     {:class {:selected (= current-filter value)}
                      :href "#"
                      :on-click [::set-filter! value]}
                     label]])))]))

(defc ui-root
  {::set-filter! sg/tx
   ::create-new!
   (fn [env ^js e]
     (when (= 13 (.-keyCode e))
       (let [input (.-target e)
             text (.-value input)]

         (when (seq text)
           (set! input -value "") ;; FIXME: this triggers a paint so should probably be delayed?
           (sg/run-tx env ::create-new! {::todo-text text})))))

   ::clear-completed! sg/tx
   ::toggle-all!
   (fn [env e]
     (sg/run-tx env ::toggle-all! {:completed? (-> e .-target .-checked)}))}

  []
  [{::keys [num-total filtered-todos num-active num-completed] :as query}
   (sg/query [::filtered-todos
              ::editing
              ::num-total
              ::num-active
              ::num-completed])]

  (<< [:header.header
       [:h1 "todos"]
       [:input.new-todo {:on-keydown [::create-new!]
                         :placeholder "What needs to be done?"
                         :autofocus true}]]

      (when (pos? num-total)
        (<< [:section.main
             [:input#toggle-all.toggle-all
              {:type "checkbox"
               :on-change [::toggle-all!]
               :checked false}]
             [:label {:for "toggle-all"} "Mark all as complete"]

             [:ul.todo-list
              (sa/render-seq filtered-todos identity todo-item)]

             [:footer.footer
              [:span.todo-count
               [:strong num-active] (if (= num-active 1) " item" " items") " left"]

              (ui-filter-select)

              (when (pos? num-completed)
                (<< [:button.clear-completed {:on-click [::clear-completed!]} "Clear completed"]))]]))
      ))

(defonce root-el (js/document.getElementById "app"))

(def init-todos
  (->> (range 100)
       (map (fn [i]
              {::todo-id i
               ::todo-text (str "item #" i)
               ::completed? false}))
       (vec)))


(defonce data-ref
  (-> {::id-seq 101
       ::editing nil
       ::current-filter :all}
      (with-meta {::db/schema (db/configure
                                {::todo
                                 {:type :entity
                                  :attrs {::todo-id [:primary-key number?]}}})})
      (db/merge-seq ::todo init-todos)
      (atom)))

(defonce app-env
  (-> {}
      (sa/init)
      (sg/env ::todomvc data-ref)))

(defmethod db/query-calc ::num-active
  [env db current _ params]
  (->> (db/all-of db ::todo)
       (remove ::completed?)
       (count)))

(defmethod db/query-calc ::num-completed
  [env db current _ params]
  (->> (db/all-of db ::todo)
       (filter ::completed?)
       (count)))

(defmethod db/query-calc ::num-total
  [env db current _ params]
  (count (db/all-idents-of db ::todo)))

(defmethod db/query-calc ::editing?
  [env db current _ params]
  (= (::editing db) (::db/ident current)))

(defmethod db/query-calc ::filtered-todos
  [env {::keys [current-filter] :as db} current _ params]
  (let [filter-fn
        (case current-filter
          :all
          (fn [x] true)
          :active
          #(not (::completed? %))
          :completed
          #(true? (::completed? %)))]

    (->> (db/all-of db ::todo)
         (filter filter-fn)
         (sort-by ::todo-id)
         (map ::db/ident)
         (vec))))

(sg/reg-event-fx app-env ::create-new!
  []
  (fn [{:keys [db]} new-todo]
    (let [{::keys [id-seq]} db]
      {:db
       (let [new-todo (assoc new-todo ::todo-id id-seq)]
         (-> db
             (update ::id-seq inc)
             (db/add ::todo new-todo)))})))

(sg/reg-event-fx app-env ::delete!
  []
  (fn [{:keys [db] :as env} todo]
    {:db (dissoc db todo)}))

(sg/reg-event-fx app-env ::set-filter!
  []
  (fn [{:keys [db] :as env} new-filter]
    {:db
     (assoc db ::current-filter new-filter)}))

(sg/reg-event-fx app-env ::toggle-completed!
  []
  (fn [{:keys [db] :as env} todo]
    {:db
     (update-in db [todo ::completed?] not)}))

(sg/reg-event-fx app-env ::edit-start!
  []
  (fn [{:keys [db] :as env} todo]
    {:db
     (assoc db ::editing todo)}))

(sg/reg-event-fx app-env ::edit-save!
  []
  (fn [{:keys [db] :as env} {:keys [todo text]}]
    {:db
     (-> db
         (assoc-in [todo ::todo-text] text)
         (assoc ::editing nil))}))

(sg/reg-event-fx app-env ::edit-cancel!
  []
  (fn [{:keys [db] :as env} todo]
    {:db
     (assoc db ::editing nil)}))

(sg/reg-event-fx app-env ::clear-completed!
  []
  (fn [{:keys [db] :as env} _]
    {:db
     (reduce
       (fn [db {::keys [completed?] :as todo}]
         (if-not completed?
           db
           (db/remove db todo)))
       db
       (db/all-of db ::todo))}))

(sg/reg-event-fx app-env ::toggle-all!
  []
  (fn [{:keys [db] :as env} {:keys [completed?]}]
    {:db
     (reduce
       (fn [db ident]
         (assoc-in db [ident ::completed?] completed?))
       db
       (db/all-idents-of db ::todo))}))

(defn ^:dev/after-load start []
  (sg/start app-env root-el (ui-root)))

(defn init []
  (start))