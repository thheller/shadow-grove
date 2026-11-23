(ns examples.remote-backend
  "Server backend for examples.remote: 100,000 items and 1,000 owners in an atom,
   exposing both WebSocket and HTTP POST + SSE transports via shadow.grove.remote.server.

   Any other database (Datomic, PostgreSQL, etc.) replaces only the defmethods below."
  (:require
    [clojure.string :as str]
    [shadow.grove.remote.server :as rs]))

(def item-count 100000)
(def owner-count 1000)

(defn generate-db []
  {:item
   (into (sorted-map)
     (map (fn [id]
            [id {:item/id id
                 :item/label (str "item #" id)
                 :item/notes (str "Detailed notes for item #" id)
                 :item/done? false
                 :item/owner (inc (mod id owner-count))}]))
     (range 1 (inc item-count)))

   :owner
   (into {}
     (map (fn [id]
            [id {:owner/id id
                 :owner/name (str "Owner " id)}]))
     (range 1 (inc owner-count)))})

(defonce db (atom (generate-db)))
(defonce hub (rs/make-hub))

(defn reset-all! []
  (reset! db (generate-db)))

;; Named views:
;; - :summary -> slim row (id, label, done?)
;; - :detail  -> full row (includes :item/notes & :item/owner) + sideloaded :owner entity
(defmethod rs/entity :item [_ctx ids view]
  (let [items (:item @db)
        owners (:owner @db)
        matched (into {}
                  (keep (fn [id]
                          (when-some [row (get items id)]
                            [id (if (= :detail view)
                                  row
                                  (select-keys row [:item/id :item/label :item/done?]))])))
                  ids)]
    (if (= :detail view)
      (let [owner-ids (into #{} (keep :item/owner) (vals matched))
            sideload (select-keys owners owner-ids)]
        {:entities {:item matched
                    :owner sideload}
         :views (into {}
                  (concat
                    (map (fn [id] [[:item id] #{:detail :summary :default}]) (keys matched))
                    (map (fn [oid] [[:owner oid] #{:default}]) (keys sideload))))
         :tags (into #{} (map (fn [oid] [:owner oid])) owner-ids)})
      matched)))

(defmethod rs/entity :owner [_ctx ids _view]
  (select-keys (:owner @db) ids))

;; Paged list query returning inline summary entities (1 DB query, no N+1)
(defmethod rs/query :remote-ex/items [_ctx {:keys [offset limit] :or {offset 0 limit 50}}]
  (let [items (:item @db)
        slice (->> (vals items) (drop offset) (take limit) (vec))
        rows (into {}
               (map (fn [r] [(:item/id r) (select-keys r [:item/id :item/label :item/done?])]))
               slice)]
    {:ids (mapv :item/id slice)
     :table :item
     :view :summary
     :total (count items)
     :entities {:item rows}
     :tags #{:table/item}}))

;; Non-entity projection (no fake table needed)
(defmethod rs/query :remote-ex/stats [_ctx _params]
  (let [items (vals (:item @db))
        done-count (count (filter :item/done? items))]
    {:value {:total (count items)
             :done done-count}
     :tags #{:remote-ex/stats}}))

;; Mutations (return authoritative :data for 1-RTT settlement + :stale tags for lists/other clients)
(defmethod rs/transact :remote-ex/set-done [_ctx {:keys [id done?]}]
  (if-not (contains? (:item @db) id)
    {:reject :not-found}
    (let [updated (get-in (swap! db assoc-in [:item id :item/done?] (boolean done?)) [:item id])]
      {:data {:item {id updated}}
       :views {[:item id] #{:detail :summary :default}}
       :stale #{:remote-ex/stats}})))

(defmethod rs/transact :remote-ex/rename [_ctx {:keys [id label]}]
  (let [label (str/trim (str label))]
    (cond
      (not (contains? (:item @db) id))
      {:reject :not-found}

      (str/starts-with? label "!")
      {:reject (str "the server rejects labels starting with !, got " label)}

      (str/blank? label)
      {:reject "label cannot be blank"}

      :else
      (let [updated (get-in (swap! db assoc-in [:item id :item/label] label) [:item id])]
        {:result {:id id :label label}
         :data {:item {id updated}}
         :views {[:item id] #{:detail :summary :default}}}))))

(defn write-from-elsewhere!
  "Simulates another user / background job modifying item 1 behind the client's back."
  [id label]
  (swap! db assoc-in [:item id :item/label] label)
  (rs/notify! hub {:stale #{[:item id] :table/item}}))
