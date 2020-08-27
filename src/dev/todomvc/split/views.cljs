(ns todomvc.split.views
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [todomvc.model :as m]))

(defc todo-item [todo]
  (event ::m/edit-update! [env todo e]
    (case (.-which e)
      13 ;; enter
      (.. e -target (blur))
      27 ;; escape
      (sg/run-tx env [::m/edit-cancel! todo])
      ;; default do nothing
      nil))

  (event ::m/edit-complete! [env todo e]
    (sg/run-tx env [::m/edit-save! {:todo todo :text (.. e -target -value)}]))

  (event ::m/toggle-completed! sg/tx)
  (event ::m/edit-start! sg/tx)
  (event ::m/delete! sg/tx)

  (bind {::m/keys [completed? editing? todo-text] :as data}
    (sg/query-ident todo
      [::m/todo-text
       ::m/editing?
       ::m/completed?]))

  (render
    (<< [:li {:class {:completed completed?
                      :editing editing?}}
         [:div.view
          [:input.toggle {:type "checkbox"
                          :checked completed?
                          :on-change [::m/toggle-completed! todo]}]
          [:label {:on-dblclick [::m/edit-start! todo]} todo-text]
          [:button.destroy {:on-click [::m/delete! todo]}]]

         (when editing?
           (<< [:input#edit.edit {:autofocus true
                                  :on-keydown [::m/edit-update! todo]
                                  :on-blur [::m/edit-complete! todo]
                                  :value todo-text}]))])))

(defc ui-filter-select []
  (bind {::m/keys [current-filter]}
    (sg/query-root
      [::m/current-filter]))

  (bind
    filter-options
    [{:label "All" :value :all}
     {:label "Active" :value :active}
     {:label "Completed" :value :completed}])

  (render
    (<< [:ul.filters
         (sg/render-seq filter-options :value
           (fn [{:keys [label value]}]
             (<< [:li [:a
                       {:class {:selected (= current-filter value)}
                        :href "#"
                        :on-click [::m/set-filter! value]}
                       label]])))])))

(defc ui-todo-list []
  (bind {::m/keys [filtered-todos] :as query}
    (sg/query-root
      [::m/filtered-todos]))

  (render
    (<< [:ul.todo-list (sg/render-seq filtered-todos identity todo-item)])))

(defc ui-root []
  (event ::m/set-filter! sg/tx)
  (event ::m/create-new! [env ^js e]
    (when (= 13 (.-keyCode e))
      (let [input (.-target e)
            text (.-value input)]

        (when (seq text)
          (set! input -value "") ;; FIXME: this triggers a paint so should probably be delayed?
          (sg/run-tx env [::m/create-new! {::m/todo-text text}])))))

  (event ::m/clear-completed! sg/tx)
  (event ::m/shuffle! sg/tx)

  (event ::m/toggle-all! [env e]
    (sg/run-tx env [::m/toggle-all! {:completed? (-> e .-target .-checked)}]))

  (bind {::m/keys [num-total num-active num-completed] :as query}
    (sg/query-root
      [::m/editing
       ::m/num-total
       ::m/num-active
       ::m/num-completed]))

  (render
    (<< [:header.header
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

               (ui-todo-list)

               [:footer.footer
                [:span.todo-count
                 [:strong num-active] (if (= num-active 1) " item" " items") " left"]

                (ui-filter-select)

                (when (pos? num-completed)
                  (<< [:button.clear-completed {:on-click [::m/clear-completed!]} "Clear completed"]))]])))))