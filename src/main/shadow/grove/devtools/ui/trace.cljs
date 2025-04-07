(ns shadow.grove.devtools.ui.trace
  (:require
    [clojure.string :as str]
    [shadow.grove.devtools :as-alias m]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.components :as comp]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools.ui.common :as ui-common]
    [shadow.grove.ui.edn :as edn]))

(defn load-traces [tx {:keys [target-id] :as target}]
  (relay-ws/cast! (::sg/runtime-ref tx)
    {:op ::m/request-traces
     :to target-id}))

(defn slot-label [component-details slot-idx]
  (:name (nth (:slots component-details) slot-idx)))

(defn rollup-trace [{:keys [components snapshot snapshot-after tasks ts ts-end trigger] :as trace}]
  (let [parts (partition-by :instance-id tasks)

        find-depth
        (fn find-depth [x id]
          (let [{:keys [parent-id] :as component}
                (or (get snapshot-after id)
                    (get snapshot id))]

            (if-not parent-id
              x
              (recur (inc x) parent-id))))]

    (assoc trace
      :updates
      (->> parts
           (mapv (fn [tasks]
                   (let [{:keys [instance-id] :as task}
                         (first tasks)

                         start-ts
                         (:ts task)

                         depth
                         (find-depth 0 instance-id)

                         instance-info
                         (or (get snapshot-after instance-id)
                             (get snapshot instance-id))

                         component-details
                         (get components (:component-name instance-info))]

                     (reduce
                       (fn [upd task]
                         (case (:action task)
                           ::comp/sync! (assoc upd :sync true)
                           ::comp/work! (assoc upd :work true :runtime (:runtime task))
                           ::comp/render! (assoc upd :rendered true)
                           ::comp/create! (assoc upd :created true)
                           ::comp/destroy! (assoc upd :destroyed true)
                           (update upd :tasks conj task)))
                       {:start-ts start-ts
                        :depth depth
                        :component-details component-details
                        :tasks []}
                       tasks))

                   ))))))

(defc ui-trace [target-id trace-id]
  (bind snapshot
    (sg/kv-lookup ::m/work-snapshot [target-id trace-id]))

  (bind {:keys [updates ts ts-end trigger] :as snapshot}
    (rollup-trace snapshot))

  (render
    (<< [:div (css :p-4)
         [:div (css :font-semibold :border-b :mb-1) "Update started by: " (str trigger) " duration: " (.toFixed (- ts-end ts) 1) "ms"]
         [:div #_(css {:display "grid" :grid-template-columns "min-content 1fr"})
          (sg/simple-seq updates
            (fn [{:keys [component-details tasks depth start-ts] :as component-update}]
              (<< [:div (css :flex :text-xs :mb-1
                          ["& > div + div" :ml-2]
                          ["& > div" :whitespace-nowrap])
                   [:div
                    {:style {:padding-left (js/CSS.px (* depth 10))}}
                    (str (cond
                           (:created component-update) "\uD83C\uDF31 " ;; sprout
                           (:destroyed component-update) "❌ "
                           (:rendered component-update) "\uD83C\uDF33 " ;; tree
                           (:sync component-update) "↓ " ;; component updated but skipped render
                           :else "")
                         (:component-name component-details))]
                   (sg/simple-seq tasks
                     (fn [task]
                       (case (:action task)
                         ::comp/slot-cleanup!
                         (<< [:div (css :px-2 :bg-red-200)
                              (slot-label component-details (:slot-idx task))])

                         ::comp/run-slot!
                         (<< [:div
                              {:class (cond
                                        (:created component-update)
                                        (css :px-2 :bg-green-200)

                                        (:equal task)
                                        (css :px-2 :bg-gray-300)

                                        :else
                                        (css :px-2 :bg-orange-200))}

                              (slot-label component-details (:slot-idx task))

                              (when-not (:ready task)
                                " \uD83D\uDCA4")])

                         (<< [:div (pr-str task)]))
                       ))])))]])))

(defn ?work-snapshots [env target-id]
  (->> (::m/work-snapshot env)
       (vals)
       (filter #(= target-id (:target-id %)))
       (sort-by :trace-id)
       (map :trace-id)
       (reverse)
       (vec)))

(defc ui-panel [target-id]
  (bind snapshots
    (sg/query ?work-snapshots target-id))

  (render
    (<< [:div {:class (css :flex-1 :overflow-auto :font-mono)}
         [:div {:class (css :flex)}]
         (sg/simple-seq snapshots
           (fn [trace-id]
             (ui-trace target-id trace-id)))])))
