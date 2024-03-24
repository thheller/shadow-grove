(ns shadow.grove.devtools.ui.data
  (:require
    [shadow.grove.devtools :as-alias m]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.events :as ev]
    [shadow.grove.runtime :as rt]
    [shadow.grove :as sg :refer (defc << css)]))

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
              (assoc m (second k) (count v))
              m))
          {}
          remote-db)]

    (update-in env [:db ident] merge
      {:db remote-db
       :db-globals db-globals
       :db-tables db-tables})
    ))

(defn load-db-copy [env {:keys [client-id] :as target}]
  (relay-ws/call! @(::rt/runtime-ref env)
    {:op ::m/get-db-copy
     :to client-id
     ;; FIXME: app selection in case of multiple?
     :app-id (first (:runtimes target))}
    {:e ::m/set-db-copy!
     :ident (:db/ident target)}))

(defc ui-panel [target-ident]
  (bind {:keys [db db-globals db-table db-tables] :as target}
    (sg/db-read target-ident))

  (effect :mount [env]
    (when-not db
      (load-db-copy env target)))

  (bind db-tables-sorted
    (->> (keys db-tables)
         (sort)
         (vec)))

  (event ::select! [env ev e]
    (js/console.log "select" ev))

  (render
    (<< [:div {:class (css :p-2 :text-lg :font-semibold)}
         "Tables " (count db-tables)
         " | Globals " (count db-globals)]
        (sg/simple-seq db-tables-sorted
          (fn [table]
            (<< [:div {:class (css :flex :cursor-pointer :border-b)
                       :on-click {:e ::select! :table table}}
                 [:div {:class (css :text-right :px-2 {:width "60px"})} (str (get db-tables table))]
                 [:div {:class (css :flex-1)}
                  (str table)]])))

        )))
