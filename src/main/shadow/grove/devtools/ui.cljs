(ns shadow.grove.devtools.ui
  {:dev/always true}
  (:require
    [shadow.arborist.attributes :as attrs]
    [shadow.grove.db :as db]
    [shadow.grove.runtime :as rt]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools :as-alias m]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.ui.edn :as edn]
    [shadow.grove.ui.vlist2 :as vlist]
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
  [env {:keys [view runtime] :as ev} e]
  (assoc-in env [:db runtime :view] view))

(defn highlight!
  {::ev/handle ::m/highlight!}
  [env {:keys [item runtime] :as ev} e]
  (when (contains? (get-in env [:db runtime :supported-ops]) ::m/highlight-component)
    (sg/queue-fx env
      :relay-send
      {:op ::m/highlight-component
       :component item
       :to (get-in env [:db runtime :client-id])})))

(defn request-log!
  {::ev/handle ::m/request-log!}
  [env {:keys [type name idx instance-id target] :as ev} e]
  (when (contains? (get-in env [:db target :supported-ops]) ::m/request-log)
    (sg/queue-fx env
      :relay-send
      {:op ::m/request-log
       :type type
       :idx idx
       :component instance-id
       :name name
       :to (get-in env [:db target :client-id])})))

(defn remove-highlight!
  {::ev/handle ::m/remove-highlight!}
  [env {:keys [item runtime] :as ev} e]
  (when (contains? (get-in env [:db runtime :supported-ops]) ::m/highlight-component)
    (sg/queue-fx env
      :relay-send
      {:op ::m/remove-highlight
       :to (get-in env [:db runtime :client-id])})))

(defn events-vlist [env db runtime-ident {:keys [offset num] :or {offset 0 num 0} :as params}]
  (let [events
        (get-in db [runtime-ident :events])

        entries
        (count events)

        slice
        (->> events
             (drop offset)
             (take num)
             (vec))]

    {:item-count entries
     :offset offset
     :slice slice}))

(defc ui-target [^:stable runtime-ident]
  (bind view
    (sg/db-read [runtime-ident :view]))

  (render
    (let [$button (css :inline-block :cursor-pointer :text-lg :border :px-4 :rounded :whitespace-nowrap :bg-blue-200 [:hover :bg-blue-400])]
      (<< [:div {:class (css :flex-1 :flex :flex-col :text-sm :overflow-hidden)}
           [:div {:class (css :bg-white :p-2)}
            [:div
             [:button {:class $button
                       :on-click {:e ::m/switch-view! :runtime runtime-ident :view :tree}} "Tree"]
             [:button {:class $button
                       :on-click {:e ::m/switch-view! :runtime runtime-ident :view :events}} "Events"]
             [:button {:class $button
                       :on-click {:e ::m/switch-view! :runtime runtime-ident :view :data}} "Data"]]]
           (case view
             :tree (ui-tree/ui-panel runtime-ident)
             :data (ui-data/ui-panel runtime-ident)
             (ui-events/ui-panel runtime-ident)
             )]))))

(defn suitable-targets [env db]
  (->> (db/all-of db ::m/target)
       (filter #(contains? (:supported-ops %) ::m/take-snapshot))
       (remove :disconnected)
       (sort-by :client-id)
       (vec)))

(defc ui-root []
  (bind targets
    (sg/db-read suitable-targets))

  (bind selected
    (sg/db-read ::m/selected-target))

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
                           :target (:db/ident target)}}
                    (str "#" client-id " - " (pr-str (dissoc client-info :since :proc-id)))
                    ])))
           ]))))