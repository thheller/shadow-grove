;; FIXME: should remain identical to split.views
;; need to sort out ns structure it can be actually identical
(ns todomvc.simple.views
  (:require
    [shadow.experiments.arborist :as sa :refer (<< defc)]
    [shadow.experiments.arborist.effects :as sfx]
    [shadow.experiments.grove :as sg]
    [todomvc.model :as m]))

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

(defc ui-todo-list []
  [{::m/keys [filtered-todos] :as query}
   (sg/query [::m/filtered-todos])]
  (<< [:ul.todo-list (sa/render-seq filtered-todos identity todo-item)]))

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
  [{::m/keys [num-total num-active num-completed] :as query}
   (sg/query [::m/editing
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

             ;; test extract to see if shuffle gets faster
             ;; since all the other stuff doesn't need to happen here when only the todos are shuffled
             ;; does have an impact overall but its all very efficient already so doesn't matter much
             ;; even with 6x slowdown
             (ui-todo-list)

             [:footer.footer
              [:span.todo-count
               [:strong num-active] (if (= num-active 1) " item" " items") " left"]

              (ui-filter-select)

              (when (pos? num-completed)
                (<< [:button.clear-completed {:on-click [::m/clear-completed!]} "Clear completed"]))]]))))