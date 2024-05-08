(ns shadow.grove.devtools.ui.data
  (:require
    [clojure.string :as str]
    [shadow.grove.devtools :as-alias m]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.events :as ev]
    [shadow.grove.runtime :as rt]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.ui.edn :as edn]))

(defn set-db-copy!
  {::ev/handle ::m/set-db-copy!}
  [env {:keys [ident call-result] :as msg}]
  (let [remote-db
        (:db call-result)

        db-globals
        (reduce-kv
          (fn [m k v]
            (if (keyword? k)
              (assoc m k v)
              m))
          {}
          remote-db)

        db-tables
        (reduce-kv
          (fn [m k v]
            (if (and (vector? k) (= 2 (count k)) (= :shadow.grove.db/all (first k)))
              (assoc m k
                       {:label (second k)
                        :coll-key k
                        :row-count (count v)
                        :columns (reduce #(into %1 (keys (get remote-db %2))) #{} v)})
              m))
          {}
          remote-db)]

    (update-in env [:db ident] merge
      {:db remote-db
       :db-globals db-globals
       :db-tables db-tables})
    ))


(defn select-table!
  {::ev/handle ::select-table!}
  [env {:keys [target table] :as msg}]
  (assoc-in env [:db target :selected-table] table))

(defn load-db-copy [env {:keys [client-id] :as target}]
  (relay-ws/call! @(::rt/runtime-ref env)
    {:op ::m/get-db-copy
     :to client-id
     ;; FIXME: app selection in case of multiple?
     :app-id (first (:runtimes target))}
    {:e ::m/set-db-copy!
     :ident (:db/ident target)}))

(def $table-col
  (css :truncate :border-b :border-r :p-1))

(defc ui-table-row [target-ident columns row-ident]
  (bind data
    (sg/db-read [target-ident :db row-ident]))

  (render
    (sg/simple-seq columns
      (fn [col]
        (<< [:div {:class $table-col}
             (pr-str (get data col))])))))

;; FIXME: write actual table view
;; I don't think a table view is all that useful in the end
;; at least in the shadow-cljs UI the amount of data can grow very fast (due to Inspect/tap>)
;; and seeing hundreds of objects with UUID keys and mostly edn data doesn't look any better in a table
;; so for now trying to stick with a diff-ish view that only shows the actively changed data per event
;; leaving this until I can think of a better way to display this
(defc ui-table-view [target-ident table]
  (bind {:keys [columns] :as table-data}
    (sg/db-read [target-ident :db-tables table]))

  (bind row-set
    (sg/db-read [target-ident :db table]))

  (bind columns-sorted
    (-> columns
        (disj :db/ident)
        (edn/attempt-to-sort)))

  ;; :shadow.grove.db/all stored as set
  ;; need a vec to do anything useful here
  (bind row-vec
    (vec row-set))

  (render
    (<< [:div {:class (css {:display "grid"})
               :style/grid-template-columns
               (->> columns-sorted
                    (map (fn [col]
                           "200px"))
                    (str/join " "))}
         (sg/simple-seq columns-sorted
           (fn [col]
             (<< [:div {:class $table-col} (str col)])))

         (sg/keyed-seq row-vec identity #(ui-table-row target-ident columns-sorted %1))])))

(defc ui-panel [target-ident]
  (bind {:keys [db selected-table db-globals db-table db-tables] :as target}
    (sg/db-read target-ident))

  (effect :mount [env]
    (when-not db
      (load-db-copy env target)))

  (bind db-tables-sorted
    (->> db-tables
         (vals)
         (sort-by :label)))

  (render
    (<< [:div {:class (css :p-2 :text-lg :font-semibold)}
         "Tables " (count db-tables)
         " | Globals " (count db-globals)]
        (sg/simple-seq db-tables-sorted
          (fn [{:keys [label coll-key row-count] :as table}]
            (<< [:div {:class (css :flex :cursor-pointer :border-b)
                       :on-click {:e ::select-table! :target target-ident :table coll-key}}
                 [:div {:class (css :text-right :px-2 {:width "60px"})} row-count]
                 [:div {:class (css :flex-1)} (str label)]])))

        (when selected-table
          (ui-table-view target-ident selected-table)))))

