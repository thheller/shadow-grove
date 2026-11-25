(ns shadow.grove.devtools.ui.data
  "the Data view, a browser for the kv tables of the target runtime. rows the mounted UI
   currently reads (sg/kv-lookup, sg/query, see impl/read-keys) are highlighted.
   the preload pushes what this shows after every tx and render, see kv-sub there."
  (:require
    [shadow.grove.devtools :as-alias m]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.events :as ev]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.ui.edn :as edn]))

;; ::m/kv-view has one entry per target. what the user picked:
;;   :app-id :kv-table :filter :only-read? :entry ([key] or nil) :sort ([col dir] or nil)
;; and what the preload pushed for that:
;;   :tables (every table with counts) :table (rows of the picked one) :entry-state
;; :sub-id goes up with every change the preload needs to know about. a push for an
;; older sub-id only updates :tables, so rows of the previous table never show up.

(defn supported? [env target-id]
  (contains? (get-in env [::m/target target-id :supported-ops]) ::m/kv-sub))

(defn update-sub [env target-id f]
  (let [view
        (-> (get-in env [::m/kv-view target-id])
            (f)
            (assoc :target-id target-id)
            (update :sub-id (fnil inc 0)))]

    (-> env
        (assoc-in [::m/kv-view target-id] view)
        (cond->
          (supported? env target-id)
          (sg/queue-fx :relay-send
            (-> view
                (select-keys [:sub-id :app-id :kv-table :filter :only-read? :entry])
                (assoc :op ::m/kv-sub :to target-id)))))))

(defn kv-open!
  {::ev/handle ::kv-open!}
  [env {:keys [target-id]}]
  (update-sub env target-id identity))

(defn kv-close!
  {::ev/handle ::kv-close!}
  [env {:keys [target-id]}]
  ;; the target may be gone already
  (cond-> env
    (supported? env target-id)
    (sg/queue-fx :relay-send {:op ::m/kv-unsub :to target-id})))

(defn show-table [view app-id kv-table entry]
  (-> view
      (assoc :app-id app-id :kv-table kv-table :entry entry :filter "" :sort nil)
      (dissoc :table :entry-state)))

(defn kv-select-table!
  {::ev/handle ::kv-select-table!}
  [env {:keys [target-id app-id kv-table]}]
  (update-sub env target-id #(show-table % app-id kv-table nil)))

(defn kv-navigate!
  "follows a reference (:joins or :attrs {attr {:references table}}) into another table"
  {::ev/handle ::kv-navigate!}
  [env {:keys [target-id app-id kv-table key]}]
  (update-sub env target-id #(show-table % app-id kv-table [key])))

(defn kv-set-filter!
  {::ev/handle ::kv-set-filter!}
  [env {:keys [target-id]} e]
  (update-sub env target-id #(assoc % :filter (.. e -target -value))))

(defn kv-toggle-only-read!
  {::ev/handle ::kv-toggle-only-read!}
  [env {:keys [target-id]}]
  (update-sub env target-id #(update % :only-read? not)))

(defn kv-select-entry!
  {::ev/handle ::kv-select-entry!}
  [env {:keys [target-id key]}]
  (update-sub env target-id
    (fn [{:keys [entry] :as view}]
      (if (= entry [key])
        (-> view (assoc :entry nil) (dissoc :entry-state))
        (assoc view :entry [key])))))

(defn kv-close-entry!
  {::ev/handle ::kv-close-entry!}
  [env {:keys [target-id]}]
  (update-sub env target-id #(-> % (assoc :entry nil) (dissoc :entry-state))))

(defn kv-sort!
  {::ev/handle ::kv-sort!}
  [env {:keys [target-id col]}]
  ;; sorts the rows that are here, the preload doesn't need to know
  (update-in env [::m/kv-view target-id :sort]
    (fn [[current dir]]
      (cond
        (not= current col) [col :asc]
        (= dir :asc) [col :desc]
        :else nil))))

(defn kv-log!
  {::ev/handle ::kv-log!}
  [env {:keys [target-id app-id kv-table key]}]
  (cond-> env
    (supported? env target-id)
    (sg/queue-fx :relay-send
      {:op ::m/kv-log-entry
       :to target-id
       :app-id app-id
       :kv-table kv-table
       :key key})))

(defmethod relay-ws/handle-msg ::m/kv-update
  [env {:keys [from sub-id tables table entry]}]
  (update-in env [::m/kv-view from]
    (fn [view]
      (cond-> (assoc view :target-id from :tables tables)
        (= sub-id (:sub-id view))
        (assoc :table table :entry-state entry)))))

;; values

(defn truncated?
  "the preload replaces values too large to send with {::m/truncated preview}"
  [v]
  (and (map? v) (contains? v ::m/truncated)))

(defn entity? [v]
  (and (map? v) (not (truncated? v))))

(defn columns-for
  "the attrs of the map values when most values are maps, most common first.
   nil for a single value column"
  [{:keys [rows primary-key] :as table}]
  (let [maps (filter #(entity? (:val %)) rows)]
    (when (and (seq maps) (>= (* 2 (count maps)) (count rows)))
      (let [freqs
            (reduce
              (fn [freqs {:keys [val]}]
                (reduce-kv
                  (fn [freqs k _]
                    (update freqs k (fnil inc 0)))
                  freqs
                  val))
              {}
              maps)]
        (->> (keys freqs)
             ;; the key column already shows it
             (remove #(= % primary-key))
             (sort-by (fn [k] [(- (get freqs k)) (str k)]))
             (take 50)
             (vec))))))

(defn sort-rows [rows [col dir]]
  (if (nil? col)
    rows
    (let [get-val
          (case col
            ::key :key
            ::val :val
            #(get (:val %) col))
          sorted
          (sort-by get-val edn/smart-comp rows)]
      (vec (if (= dir :desc) (reverse sorted) sorted)))))

(def $nil (css {:color "#9ca3af"}))
(def $string (css {:color "#008000"}))
(def $keyword (css {:color "#660e7a"}))
(def $number (css {:color "#0000ff"}))
(def $truncated (css :italic {:color "#9ca3af"}))
(def $ref (css :cursor-pointer :underline {:color "#1d4ed8"} [:hover {:color "#1e3a8a"}]))

(defn value-class [v]
  (cond
    (nil? v) $nil
    (string? v) $string
    (keyword? v) $keyword
    (or (number? v) (boolean? v)) $number
    (truncated? v) $truncated
    :else nil))

(defn value-str [v]
  (let [s (if (truncated? v)
            (str (::m/truncated v) " … (too large to send)")
            (pr-str v))]
    (if (> (count s) 2000)
      (str (subs s 0 2000) " …")
      s)))

(defn ui-value [v]
  (let [s (value-str v)]
    (<< [:span {:class (value-class v) :title s} s])))

(defn ui-ref-link [{:keys [target-id app-id]} kv-table key]
  (<< [:span {:class $ref
              :title (str "show " (pr-str key) " in " kv-table)
              :on-click {:e ::kv-navigate!
                         :e/stop true
                         :target-id target-id
                         :app-id app-id
                         :kv-table kv-table
                         :key key}}
       (pr-str key)]))

(defn ui-ref-value
  "a value of a reference attr, one key or a collection of keys"
  [ctx kv-table v]
  (cond
    (or (nil? v) (truncated? v) (map? v))
    (ui-value v)

    (coll? v)
    (<< "["
        (sg/simple-seq v
          (fn [key idx]
            (<< (when (pos? idx) " ")
                (ui-ref-link ctx kv-table key))))
        "]")

    :else
    (ui-ref-link ctx kv-table v)))

;; sidebar

(def $sidebar
  (css :flex-shrink-0 :overflow-y-auto :border-r :bg-gray-50 :py-1 {:width "250px"}))

(def $app-header
  (css :px-3 :pt-2 :pb-1 :text-xs :font-semibold :text-gray-500))

(def $table-item
  (css :flex :items-center :px-3 :py-1 :cursor-pointer {:gap "0.5rem"}
    [:hover :bg-gray-200]
    ["&.empty" {:color "#9ca3af"}]
    ["&.selected" :bg-blue-100]))

(def $badge
  (css :text-xs :px-1 :rounded :whitespace-nowrap))

(def $badge-read
  (css :bg-green-100 {:color "#166534"}))

(defn ui-table-item [target-id {:keys [app-id kv-table read-count table-read?] :as table} selected?]
  (let [n (:count table)
        ns (when (keyword? kv-table) (namespace kv-table))]
    (<< [:div {:class (str $table-item
                           (when (zero? n) " empty")
                           (when selected? " selected"))
               :title (str kv-table)
               :on-click {:e ::kv-select-table!
                          :target-id target-id
                          :app-id app-id
                          :kv-table kv-table}}
         [:div {:class (css :flex-1 :min-w-0)}
          [:div {:class (css :truncate :font-mono)}
           (if (keyword? kv-table) (name kv-table) (pr-str kv-table))]
          (when ns
            (<< [:div {:class (css :truncate :text-xs :text-gray-400)} ns]))]

         (when table-read?
           (<< [:span {:class (str $badge " " $badge-read)
                       :title "the UI reads this table as a whole, (sg/kv-lookup table) or a query using seq on it"}
                "all"]))

         [:span {:class (css :text-xs :whitespace-nowrap :text-gray-500)}
          (when (pos? read-count)
            (<< [:span {:class (css :font-semibold {:color "#15803d"})
                        :title "keys the mounted UI reads"}
                 read-count]
                " / "))
          n]])))

(defc ui-table-list [^:stable target-id]
  (bind {:keys [tables app-id kv-table]}
    (sg/kv-lookup ::m/kv-view target-id))

  (bind groups
    (->> tables
         (partition-by :app-id)
         (vec)))

  (render
    (<< [:div {:class $sidebar}
         (sg/simple-seq groups
           (fn [tables]
             (let [group-app (:app-id (first tables))]
               (<< [:div {:class $app-header} (str "runtime " group-app)]
                   (sg/simple-seq tables
                     (fn [table]
                       (ui-table-item target-id table
                         (and (= group-app app-id)
                              (= kv-table (:kv-table table))))))))))])))

;; table

(def $table
  (css :font-mono :text-xs {:border-collapse "separate" :border-spacing "0" :width "100%"}))

(def $th
  (css :sticky :text-left :font-semibold :px-2 :py-1 :bg-gray-100 :whitespace-nowrap
    :cursor-pointer :select-none :border-b
    {:top "0" :z-index "1"}
    [:hover :bg-gray-200]))

(def $th-key
  (css {:left "0" :z-index "2"}))

(def $row
  (css :cursor-pointer
    {:background-color "#fff"}
    [:hover {:background-color "#f3f4f6"}]
    ["&.read" {:background-color "#f0fdf4"}]
    ["&.read:hover" {:background-color "#dcfce7"}]
    ["&.read > td:first-child" {:box-shadow "inset 3px 0 0 #22c55e"}]
    ["&.selected" {:background-color "#dbeafe"}]
    ["&.selected:hover" {:background-color "#bfdbfe"}]))

(def $cell
  (css :px-2 :py-0.5 :border-b :truncate {:max-width "320px" :border-color "#f3f4f6"}))

;; last column, takes the space the others don't need so they don't stretch
(def $filler
  (css :border-b {:width "100%" :border-color "#f3f4f6"}))

(def $th-filler
  (css :sticky :bg-gray-100 :border-b {:top "0" :width "100%"}))

(def $key-cell
  (css :sticky :px-2 :py-0.5 :border-b :border-r :truncate
    {:left "0"
     :max-width "360px"
     :background-color "inherit"
     :border-color "#f3f4f6"}))

(defc ui-row [ctx row selected?]
  (render
    (let [{:keys [key read? val]} row
          {:keys [target-id columns refs]} ctx]
      (<< [:tr {:class (str $row (when read? " read") (when selected? " selected"))
                :on-click {:e ::kv-select-entry! :target-id target-id :key key}}
           [:td {:class $key-cell} (ui-value key)]
           (cond
             (nil? columns)
             (<< [:td {:class $cell} (ui-value val)])

             ;; a value that isn't a map in a table of maps
             (not (entity? val))
             (<< [:td {:class $cell :col-span (count columns)} (ui-value val)])

             :else
             (sg/simple-seq columns
               (fn [col]
                 (<< [:td {:class $cell}
                      (when (contains? val col)
                        (let [v (get val col)]
                          (if-some [ref-table (get refs col)]
                            (ui-ref-value ctx ref-table v)
                            (ui-value v))))]))))
           [:td {:class $filler}]]))))

(defn sort-label [[sort-col dir] col label]
  (str label (when (= sort-col col) (if (= dir :asc) " ▲" " ▼"))))

(defc ui-table [^:stable target-id table sorting entry]
  (bind columns
    (columns-for table))

  (bind ctx
    {:target-id target-id
     :app-id (:app-id table)
     :columns columns
     :refs (:refs table)})

  (bind rows
    (sort-rows (:rows table) sorting))

  (render
    (let [{:keys [primary-key refs]} table
          sort-ev (fn [col] {:e ::kv-sort! :target-id target-id :col col})]
      (<< [:table {:class $table}
           [:thead
            [:tr
             [:th {:class (str $th " " $th-key) :on-click (sort-ev ::key)}
              (sort-label sorting ::key (if (keyword? primary-key) (str primary-key) "key"))]
             (if-not columns
               (<< [:th {:class $th :on-click (sort-ev ::val)} (sort-label sorting ::val "value")])
               (sg/simple-seq columns
                 (fn [col]
                   (<< [:th {:class $th
                             :title (when-some [ref-table (get refs col)]
                                      (str "references " ref-table))
                             :on-click (sort-ev col)}
                        (sort-label sorting col (str col (when (contains? refs col) " →")))]))))
             [:th {:class $th-filler}]]]
           [:tbody
            (sg/keyed-seq rows :key
              (fn [row]
                (ui-row ctx row (= entry [(:key row)]))))]]))))

;; selected entry

(def $button
  (css :border :rounded :px-2 :py-0.5 :bg-white :cursor-pointer :whitespace-nowrap [:hover :bg-gray-100]))

(defc ui-entry [^:stable target-id app-id kv-table key entry-state table]
  (render
    (let [{:keys [found? read? val]} entry-state
          ctx {:target-id target-id :app-id app-id}
          refs (:refs table)]
      (<< [:div {:class (css :flex :flex-col :overflow-hidden {:height "45%" :border-top "4px solid #e5e7eb"})}
           [:div {:class (css :flex :items-center :px-3 :py-2 :border-b {:gap "0.75rem"})}
            [:div {:class (css :font-mono :font-semibold :truncate :flex-1)} (ui-value key)]
            (cond
              (nil? entry-state)
              nil

              (not found?)
              (<< [:span {:class (str $badge " " (css :bg-red-100 {:color "#991b1b"}))} "not in the table"])

              read?
              (<< [:span {:class (str $badge " " $badge-read)} "read by the UI"])

              (:table-read? table)
              (<< [:span {:class (str $badge " " $badge-read)} "read with the whole table"])

              :else
              (<< [:span {:class (str $badge " " (css :bg-gray-100 :text-gray-600))} "not read"]))
            [:button {:class $button
                      :title "console.log the value in the runtime"
                      :on-click {:e ::kv-log! :target-id target-id :app-id app-id :kv-table kv-table :key key}}
             "log in console"]
            [:button {:class $button
                      :on-click {:e ::kv-close-entry! :target-id target-id}}
             "close"]]

           [:div {:class (css :flex-1 :overflow-auto :p-2)}
            (cond
              (nil? entry-state)
              (<< [:div {:class (css :text-gray-400)} "loading …"])

              (not found?)
              nil

              (truncated? val)
              (<< [:div {:class (css :p-2)}
                   [:div {:class (css :font-semibold :pb-2)} "too large to send, use log in console"]
                   [:div {:class (css :font-mono :text-xs {:word-break "break-all"})} (::m/truncated val)]])

              ;; attrs as rows, references link to their table
              (map? val)
              (<< [:div {:class (css :font-mono :text-xs
                                  {:display "grid"
                                   :grid-template-columns "max-content minmax(0, 1fr)"
                                   :column-gap "1rem"
                                   :row-gap "2px"})}
                   (sg/simple-seq (edn/attempt-to-sort (keys val))
                     (fn [attr]
                       (let [v (get val attr)]
                         (<< [:div {:class (css :py-0.5 {:color "#660e7a"})} (str attr)]
                             [:div {:class (css :py-0.5 :overflow-auto)}
                              (if-some [ref-table (get refs attr)]
                                (ui-ref-value ctx ref-table v)
                                (edn/render-edn v))]))))])

              :else
              (edn/render-edn val))]]))))

;; panel

(def $toolbar
  (css :flex :items-center :flex-wrap :px-3 :py-2 :border-b :bg-white {:gap "0.75rem"}))

(def $input
  (css :border :rounded :px-2 :py-0.5 {:width "16rem"}))

(defc ui-table-view [^:stable target-id]
  (bind view
    (sg/kv-lookup ::m/kv-view target-id))

  (bind {:keys [app-id kv-table only-read? entry] filter-text :filter} view)

  (bind summary
    (->> (:tables view)
         (filter #(and (= app-id (:app-id %)) (= kv-table (:kv-table %))))
         (first)))

  (bind table
    (let [table (:table view)]
      (when (and (= app-id (:app-id table)) (= kv-table (:kv-table table)))
        table)))

  (render
    (cond
      (nil? kv-table)
      (<< [:div {:class (css :flex-1 :p-6 :text-gray-500)}
           [:div {:class (css :text-base :pb-2)} "Pick a table on the left."]
           [:div "Rows the mounted UI reads, via sg/kv-lookup or in a sg/query, are highlighted. "
            "The numbers on the left are read keys / entries. "
            "Updates arrive after every tx and render of the runtime."]])

      :else
      (let [{:keys [matched rows table-read?]} table
            n (or (:count table) (:count summary))]
        (<< [:div {:class (css :flex-1 :flex :flex-col :min-w-0 :overflow-hidden)}
             [:div {:class $toolbar}
              [:div {:class (css :font-mono :font-semibold :text-base)} (str kv-table)]
              [:div {:class (css :text-gray-500 :whitespace-nowrap)}
               (str n " entries"
                    (when-some [read-count (:read-count summary)]
                      (when (pos? read-count)
                        (str ", " read-count " read"))))]
              [:input {:class $input
                       :type "search"
                       :placeholder "filter keys and values"
                       :value filter-text
                       :on-input {:e ::kv-set-filter! :target-id target-id}}]
              [:label {:class (css :flex :items-center :cursor-pointer :select-none :whitespace-nowrap {:gap "0.25rem"})}
               [:input {:type "checkbox"
                        :checked (boolean only-read?)
                        :on-change {:e ::kv-toggle-only-read! :target-id target-id}}]
               "only rows the UI reads"]
              [:div {:class (css :flex-1)}]
              (when (and table (< (count rows) matched))
                (<< [:div {:class (css :text-xs {:color "#b45309"})}
                     (str "showing " (count rows) " of " matched ", read rows first")]))
              [:div {:class (css :flex :items-center :text-xs :text-gray-500 :whitespace-nowrap {:gap "0.25rem"})}
               [:span {:class (css {:display "inline-block" :width "12px" :height "12px"
                                    :background-color "#f0fdf4"
                                    :box-shadow "inset 3px 0 0 #22c55e"
                                    :border "1px solid #dcfce7"})}]
               "read by the mounted UI"]]

             (when table-read?
               (<< [:div {:class (css :px-3 :py-1 :text-xs :border-b {:background-color "#fefce8"})}
                    "The UI reads this table as a whole, (sg/kv-lookup table) or a query using seq on it. "
                    "Every change to it re-runs those readers."]))

             [:div {:class (css :flex-1 :overflow-auto)}
              (cond
                (and (nil? table) (some? summary))
                (<< [:div {:class (css :p-4 :text-gray-400)} "loading …"])

                (nil? table)
                (<< [:div {:class (css :p-4 :text-gray-400)} "The runtime has no such table (anymore)."])

                (empty? rows)
                (<< [:div {:class (css :p-4 :text-gray-400)}
                     (if (zero? n) "The table is empty." "No rows match.")])

                :else
                (ui-table target-id table (:sort view) entry))]

             (when entry
               (ui-entry target-id app-id kv-table (first entry) (:entry-state view) table))])))))

(defc ui-panel [^:stable target-id]
  (bind supported
    (contains? (:supported-ops (sg/kv-lookup ::m/target target-id)) ::m/kv-sub))

  (effect supported [env]
    (when supported
      (sg/run-tx env {:e ::kv-open! :target-id target-id})
      (fn []
        (sg/run-tx env {:e ::kv-close! :target-id target-id}))))

  (render
    (if-not supported
      (<< [:div {:class (css :p-4)}
           "This runtime has a shadow.grove.preload without the data view, reload it to get the current one."])
      (<< [:div {:class (css :flex-1 :flex :overflow-hidden :border-t)}
           (ui-table-list target-id)
           (ui-table-view target-id)]))))
