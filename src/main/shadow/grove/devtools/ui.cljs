(ns shadow.grove.devtools.ui
  {:dev/always true}
  (:require
    [shadow.grove.events :as ev]
    [shadow.grove.devtools :as-alias m]
    [shadow.grove.devtools.ui.events :as ui-events]
    [shadow.grove.devtools.ui.tree :as ui-tree]
    [shadow.grove.devtools.ui.data :as ui-data]
    [shadow.grove :as sg :refer (defc << css)]))

(defn form-set-attr!
  {::ev/handle :form/set-attr}
  [env {:keys [a v]}]
  (update env :db assoc-in (if (keyword? a) [a] a) v))

(defn switch-view!
  {::ev/handle ::m/switch-view!}
  [env {:keys [view target-id] :as ev} e]
  (assoc-in env [::m/target target-id :view] view))

(defn highlight!
  {::ev/handle ::m/highlight!}
  [env {:keys [item target-id] :as ev} e]
  (when (contains? (get-in env [::m/target target-id :supported-ops]) ::m/highlight-component)
    (sg/queue-fx env
      :relay-send
      {:op ::m/highlight-component
       :component item
       :to target-id})))

(defn request-log!
  {::ev/handle ::m/request-log!}
  [env {:keys [type name idx instance-id target-id] :as ev} e]
  (when (contains? (get-in env [::m/target target-id :supported-ops]) ::m/request-log)
    (sg/queue-fx env
      :relay-send
      {:op ::m/request-log
       :type type
       :idx idx
       :component instance-id
       :name name
       :to target-id})))

(defn remove-highlight!
  {::ev/handle ::m/remove-highlight!}
  [env {:keys [item target-id] :as ev} e]
  (when (contains? (get-in env [::m/target target-id :supported-ops]) ::m/highlight-component)
    (sg/queue-fx env
      :relay-send
      {:op ::m/remove-highlight
       :to target-id})))

(defc ui-target [^:stable target-id]
  (bind view
    (:view (sg/kv-lookup ::m/target target-id)))

  (render
    (let [$button (css :inline-block :cursor-pointer :text-lg :border :px-4 :rounded :whitespace-nowrap :bg-blue-200 [:hover :bg-blue-400])]
      (<< [:div {:class (css :flex-1 :flex :flex-col :text-sm :overflow-hidden)}
           [:div {:class (css :bg-white :p-2)}
            [:div
             [:button {:class $button
                       :on-click {:e ::m/switch-view! :target-id target-id :view :tree}} "Tree"]
             [:button {:class $button
                       :on-click {:e ::m/switch-view! :target-id target-id :view :events}} "Events"]
             [:button {:class $button
                       :on-click {:e ::m/switch-view! :target-id target-id :view :data}} "Data"]]]
           (case view
             ;; :tree (ui-tree/ui-panel runtime-id)
             :data (ui-data/ui-panel target-id)
             :events (ui-events/ui-panel target-id)
             (ui-tree/ui-panel target-id)
             )]))))

(defn suitable-targets [env]
  (->> (::m/target env)
       (vals)
       (filter #(contains? (:supported-ops %) ::m/take-snapshot))
       (remove :disconnected)
       (sort-by :client-id)
       (vec)))

(defc ui-root []
  (bind targets
    (sg/query suitable-targets))

  (bind selected
    (sg/kv-lookup :db ::m/selected-target))

  (render
    (cond
      (empty? targets)
      (<< [:div {:class (css :p-4 :text-lg)}
           "no target runtimes found, need a runtime with shadow.grove.preload loaded!"])

      selected
      (ui-target selected)

      :else
      (<< [:div {:class (css :p-4)}
           [:h1 {:class (css :font-bold :text-2xl :pb-4)} "Runtimes"]
           (sg/simple-seq targets
             (fn [{:keys [client-id client-info supported-ops] :as target}]
               (<< [:div {:class (css :cursor-pointer :border :p-2 :mb-2 [:hover :border-green-500])
                          :on-click
                          {:e ::m/select-target!
                           :target-id client-id}}
                    (str "#" client-id " - " (pr-str (dissoc client-info :since :proc-id)))
                    ])))
           ]))))