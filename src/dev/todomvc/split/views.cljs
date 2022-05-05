(ns todomvc.split.views
  (:require
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [todomvc.model :as m]))

(defc todo-item [todo]
  (event ::m/edit-update! [env {:keys [todo]} e]
    (case (.-which e)
      13 ;; enter
      (.. e -target (blur))
      27 ;; escape
      (sg/run-tx env {:e ::m/edit-cancel! :todo todo})
      ;; default do nothing
      nil))

  (event ::m/edit-complete! [env {:keys [todo]} e]
    (sg/run-tx env {:e ::m/edit-save! :todo todo :text (.. e -target -value)}))

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
                          :on-change {:e ::m/toggle-completed! :todo todo}}]
          [:label {:on-dblclick {:e ::m/edit-start! :todo todo}} todo-text]
          [:button.destroy {:on-click {:e ::m/delete! :todo todo}}]]

         (when editing?
           (<< [:input#edit.edit {:autofocus true
                                  :on-keydown {:e ::m/edit-update! :todo todo}
                                  :on-blur {:e ::m/edit-complete! :todo todo}
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
         (sg/keyed-seq filter-options :value
           (fn [{:keys [label value]}]
             (<< [:li [:a
                       {:class {:selected (= current-filter value)}
                        :href "#"
                        :on-click {:e ::m/set-filter! :filter value}}
                       label]])))])))

(defc ui-todo-list []
  (bind {::m/keys [filtered-todos] :as query}
    (sg/query-root
      [::m/filtered-todos]))

  (render
    (<< [:ul.todo-list (sg/keyed-seq filtered-todos identity todo-item)])))

(defc ui-root []
  (event ::m/create-new! [env _ ^js e]
    (when (= 13 (.-keyCode e))
      (let [input (.-target e)
            text (.-value input)]

        (when (seq text)
          (set! input -value "") ;; FIXME: this triggers a paint so should probably be delayed?
          (sg/run-tx env {:e ::m/create-new! ::m/todo-text text})))))

  (event ::m/toggle-all! [env _ e]
    (sg/run-tx env {:e ::m/toggle-all! :completed? (-> e .-target .-checked)}))

  (bind {::m/keys [num-total num-active num-completed] :as query}
    (sg/query-root
      [::m/editing
       ::m/num-total
       ::m/num-active
       ::m/num-completed]))

  (render
    (<< [:header.header
         [:h1 "todos"]
         [:input.new-todo {:on-keydown {:e ::m/create-new!}
                           :placeholder "What needs to be done?"
                           :autofocus true}]]

        (when (pos? num-total)
          (<< [:section.main
               [:input#toggle-all.toggle-all
                {:type "checkbox"
                 :on-change {:e ::m/toggle-all!}
                 :checked false}]
               [:label {:for "toggle-all"} "Mark all as complete"]

               (ui-todo-list)

               [:footer.footer
                [:span.todo-count
                 [:strong num-active] (if (= num-active 1) " item" " items") " left"]

                (ui-filter-select)

                (when (pos? num-completed)
                  (<< [:button.clear-completed {:on-click {:e ::m/clear-completed!}} "Clear completed"]))]])))))