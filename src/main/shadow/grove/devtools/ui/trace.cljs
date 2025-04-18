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

(defn slot-label [component-details slot-idx]
  (:name (nth (:slots component-details) slot-idx)))

(defn vec-conj [x y]
  (if (nil? x)
    [y]
    (conj x y)))

(defn sort-tree-children [{:keys [tree snapshot-after] :as trace}]
  (assoc trace
    :tree
    (reduce-kv
      (fn [tree instance-id instance-info]
        (update-in tree [instance-id :children]
          (fn [children]
            (->> children
                 (sort-by (fn [child-id]
                            [(get-in tree [child-id :idx])
                             (if (contains? snapshot-after child-id)
                               ;; a child that has unmounted should come after new children
                               0 1)]))
                 (vec))
            )))
      tree
      tree)))

(defn reduce-kv-> [init rfn coll]
  (reduce-kv rfn init coll))

(defn sort-roots [{:keys [roots tree] :as trace}]
  (update trace :roots
    (fn [roots]
      (->> roots
           (sort-by #(get-in tree [% :idx]))
           (vec)))))

(defn find-roots [{:keys [tree] :as trace}]
  (-> trace
      (assoc :roots [])
      (reduce-kv->
        (fn [trace instance-id instance-info]
          (if-not (:parent-id instance-info)
            (update trace :roots conj instance-id)
            trace))
        tree)
      (sort-roots)))

(defn make-component-tree [{:keys [all] :as trace}]
  (-> trace
      (assoc :tree
             (reduce-kv
               (fn [all instance-id instance-info]
                 (if-some [parent-id (:parent-id instance-info)]
                   (update-in all [parent-id :children] vec-conj instance-id)
                   all))
               all
               all))

      (sort-tree-children)
      (find-roots)))

(defn calculate-instance-depth [{:keys [all] :as trace}]
  (let [find-depth
        (fn find-depth [x id]
          (let [{:keys [parent-id] :as component}
                (get all id)]

            (if-not parent-id
              x
              (recur (inc x) parent-id))))]

    (assoc trace
      :all
      (reduce-kv
        (fn [all instance-id _]
          (assoc-in all [instance-id :depth] (find-depth 0 instance-id)))
        all
        all))))

(defn rollup-log [{:keys [log] :as trace}]
  (update trace :tree
    (fn [tree]
      (reduce
        (fn [tree [ts action & more :as task]]
          (case action
            :component-run-slot
            tree

            :component-run-slot-done
            (let [[ref-idx changed claimed ready] more
                  [ts-start _ instance-id slot-idx :as ref-log] (nth log ref-idx)]
              (assoc-in tree [instance-id :slots slot-idx]
                {:ts ts-start
                 :ts-end ts
                 :action :run
                 :duration (- ts ts-start)
                 :changed changed
                 :claimed claimed
                 :ready ready}))

            :component-slot-cleanup
            tree

            :component-slot-cleanup-done
            (let [[ref-idx] more
                  [ts-start _ instance-id slot-idx :as ref-log] (nth log ref-idx)]
              (assoc-in tree [instance-id :slots slot-idx]
                {:ts ts-start
                 :ts-end ts
                 :action :cleanup
                 :duration (- ts ts-start)}))

            :component-create
            (assoc-in tree [(nth task 2) :created] true)

            :component-destroy
            (assoc-in tree [(nth task 2) :destroyed] true)

            :component-destroy-done
            (let [[ref-idx] more
                  [ts-start _ instance-id] (nth log ref-idx)]
              (assoc-in tree [instance-id :destroy-timing]
                {:ts ts-start
                 :ts-end ts
                 :duration (- ts ts-start)}))

            :component-render
            (assoc-in tree [(nth task 2) :rendered] true)

            :component-render-done
            (let [[ref-idx] more
                  [ts-start _ instance-id] (nth log ref-idx)]
              (assoc-in tree [instance-id :render-timing]
                {:ts ts-start
                 :ts-end ts
                 :duration (- ts ts-start)}))

            :component-dom-sync
            (assoc-in tree [(nth task 2) :sync] true)

            :component-work
            (assoc-in tree [(nth task 2) :work] true)

            tree))
        tree
        log))))

(defn rollup-trace [{:keys [snapshot snapshot-after] :as trace}]
  (-> trace
      ;; we want the merged version including everything mounted/destroyed
      ;; snapshot only contains state before/after update
      (assoc :all (merge snapshot-after snapshot))
      (calculate-instance-depth)
      (make-component-tree)
      (rollup-log)))

(defc ui-trace [target-id trace-id]
  (bind snapshot
    (sg/kv-lookup ::m/work-snapshot [target-id trace-id]))

  (bind {:keys [updates ts ts-end trigger roots tree] :as snapshot}
    (rollup-trace snapshot))

  (bind render-node
    (fn render-node [node-id]
      (let [{:keys [children depth component-name] :as instance-info} (get tree node-id)]
        (let [component-info (get-in snapshot [:components component-name])]
          (<< [:div
               (css :flex :mb-1 :border :whitespace-nowrap
                 {:gap "0.25rem"}
                 ;; ["& > div + div" :ml-2]
                 ["& > div" :py-1 :px-2]
                 [:hover {:border-color "black"}])
               {:style/margin-left (js/CSS.px (* depth 20))}
               [:div
                (str
                  (cond
                    (:created instance-info) "\uD83C\uDF31 " ;; sprout
                    (:destroyed instance-info) "❌ "
                    (:rendered instance-info) "\uD83C\uDF33 " ;; tree
                    (:sync instance-info) "↓ " ;; component updated but skipped render
                    :else "")
                  component-name)]
               (sg/simple-seq (:args component-info)
                 (fn [arg-name]
                   (<< [:div (css :bg-gray-100) arg-name])))
               (sg/simple-seq (:slots component-info)
                 (fn [slot idx]
                   (let [slot-info (get-in instance-info [:slots idx])]

                     (<< [:div
                          {:class (cond
                                    (nil? slot-info) ;; did not execute
                                    (css :bg-gray-100)

                                    (:created instance-info) ;; show all as changed when component first mounts
                                    (css :bg-orange-200)

                                    (:equal slot-info) ;; execute and equal
                                    (css :bg-green-100)

                                    (:cleanup slot-info) ;; destroy cleanup
                                    (css :bg-red-100)

                                    :else
                                    (css :bg-orange-200))}

                          (:name slot)

                          (when (and slot-info (false? (:ready slot-info)))
                            " \uD83D\uDCA4")]))))

               (when (:rendered instance-info)
                 (<< [:div "[RENDER]"]))
               ]
              (sg/simple-seq children render-node))))))

  (render
    (<< [:div (css :p-4)
         [:div (css :font-semibold :border-b :mb-1)
          {:on-click (fn [e] (js/console.log "snapshot" snapshot))}
          "Update started by: " (str trigger) " duration: " (.toFixed (- ts-end ts) 1) "ms"]
         [:div (css :text-xs)
          (sg/simple-seq roots render-node)]])))

(defn ?work-snapshots [env target-id]
  (->> (::m/work-snapshot env)
       (vals)
       (filter #(= target-id (:target-id %)))
       (sort-by :trace-id)
       (map :trace-id)
       #_(reverse)
       (vec)))

(defc ui-panel [target-id]
  (bind snapshots
    (sg/query ?work-snapshots target-id))

  (render
    (<< [:div {:class (css :flex-1 :overflow-auto :font-sans)}
         [:div {:class (css :flex)}]
         (sg/simple-seq snapshots
           (fn [trace-id]
             (ui-trace target-id trace-id)))])))
