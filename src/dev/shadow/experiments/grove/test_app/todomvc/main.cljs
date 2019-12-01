(ns shadow.experiments.grove.test-app.todomvc.main
  (:require
    [shadow.experiments.arborist :as sa :refer (<< defc)]
    [shadow.experiments.grove-main :as sg]
    [shadow.experiments.arborist.effects :as sfx]
    [shadow.experiments.grove.test-app.todomvc.model :as m]
    [cognitect.transit :as transit]))

(defc todo-item
  {::m/edit-update!
   (fn [env e todo]
     (case (.-which e)
       13 ;; enter
       (.. e -target (blur))
       27 ;; escape
       (sg/run-tx env ::m/edit-cancel! todo)
       ;; default do nothing
       nil))

   ::m/edit-complete!
   (fn [env e todo]
     (sg/run-tx env ::m/edit-save! {:todo todo :text (.. e -target -value)}))

   ::m/toggle-completed! sg/tx
   ::m/edit-start! sg/tx}
  [todo]
  [{::m/keys [completed? editing? todo-text] :as data}
   (sg/query todo [::m/todo-text
                   ::m/editing?
                   ::m/completed?])

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
     (test-fx #(sg/run-tx env ::m/delete! todo)))]

  (<< [:li {::sfx/effect test-fx
            :class {:completed completed?
                    :editing editing?}}
       [:div.view
        [:input.toggle {:type "checkbox"
                        :checked completed?
                        :on-change [::m/toggle-completed! todo]}]
        [:label {:on-dblclick [::m/edit-start! todo]} todo-text]
        [:button.destroy {:on-click [delete! todo]}]]

       (when editing?
         (<< [:input#edit.edit {:autofocus true
                                :on-keydown [::m/edit-update! todo]
                                :on-blur [::m/edit-complete! todo]
                                :value todo-text}]))]))

(defc ui-filter-select []
  [{::m/keys [current-filter]}
   (sg/query [::m/current-filter])

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
                      :on-click [::m/set-filter! value]}
                     label]])))]))

(defc ui-root
  {::m/set-filter! sg/tx
   ::m/create-new!
   (fn [env ^js e]
     (when (= 13 (.-keyCode e))
       (let [input (.-target e)
             text (.-value input)]

         (when (seq text)
           (set! input -value "") ;; FIXME: this triggers a paint so should probably be delayed?
           (sg/run-tx env ::m/create-new! {::m/todo-text text})))))

   ::m/clear-completed! sg/tx
   ::m/shuffle! sg/tx
   ::m/toggle-all!
   (fn [env e]
     (sg/run-tx env ::m/toggle-all! {:completed? (-> e .-target .-checked)}))}

  []
  [{::m/keys [num-total filtered-todos num-active num-completed] :as query}
   (sg/query [::m/filtered-todos
              ::m/editing
              ::m/num-total
              ::m/num-active
              ::m/num-completed])]


  (<< [:div {:on-click [::m/shuffle!]} "shuffle todos"]
      [:header.header
       [:h1 "todos"]
       [:input.new-todo {:on-keydown [::m/create-new!]
                         :placeholder "What needs to be done?"
                         :autofocus true}]]

      (when (pos? num-total)
        (<< [:section.main
             [:input#toggle-all.toggle-all
              {:type "checkbox"
               :on-change [::m/toggle-all!]
               :checked false}]
             [:label {:for "toggle-all"} "Mark all as complete"]

             [:ul.todo-list
              (sa/render-seq filtered-todos identity todo-item)]

             [:footer.footer
              [:span.todo-count
               [:strong num-active] (if (= num-active 1) " item" " items") " left"]

              (ui-filter-select)

              (when (pos? num-completed)
                (<< [:button.clear-completed {:on-click [::m/clear-completed!]} "Clear completed"]))]]))
      ))

(defonce root-el (js/document.getElementById "app"))

;; FIXME: these need to be customizable so don't move to app
;; need to support custom handlers, maybe just as options though
(def tr (transit/reader :json))
(def tw (transit/writer :json))

(defonce app-env (sa/init {}))

(defn ^:dev/after-load start []
  (sg/start app-env root-el (ui-root)))

(defn init []
  (set! app-env (sg/init app-env ::todomvc js/SHADOW_WORKER tr tw))
  (start))