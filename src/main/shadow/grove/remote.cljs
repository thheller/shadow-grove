(ns shadow.grove.remote
  "Treat Grove KV as a local cache over a Clojure server whose database may be far
   too large to send to clients (Datomic, PostgreSQL, in-memory, etc.).

   Setup:
     (transit/init! app)
     (remote/init! app
       {:transport (remote/ws-transport {:url \"/api/ws\" :format :transit})
        ;; or (remote/http-transport {:url \"/api/remote\" :format :transit :sse-url \"/api/sse\"})
        ;; or (remote/http-transport {:url \"/api/remote\" :format :edn}) ;; pull-only
        :tables {:item  {:primary-key :item/id
                         :views {:detail {:satisfies #{:summary :default}}}}
                 :owner {:primary-key :owner/id}}})

   Reading (inside sg/query fns OR directly inside defc bind):
     (remote/entity env :item id)
     (remote/entity env :item id :detail)
     (remote/entity :item id :detail)
     (remote/query env :app/stats)
     (remote/query :items/search {:q text} {:suspend? false})
     (remote/subscribe :home/summary nil {:interval 10000 :suspend? false})
     (remote/window env :items/all {:filter f} window {:page-size 50})

   Writing (inside deftx):
     (-> tx-env
         (assoc-in [:item id :item/label] label) ;; optional optimistic write
         (remote/transact :item/rename {:id id :label label}
           {:on-result (rename-done!)}))"
  (:require
    [shadow.grove :as sg]
    [shadow.grove.impl :as impl]
    [shadow.grove.kv :as kv]
    [shadow.grove.runtime :as rt]
    [shadow.grove.websocket :as ws]))

;; ---------------------------------------------------------------------------
;; Helpers & View Satisfaction
;; ---------------------------------------------------------------------------

(defn- state-ref-of [rt-ref]
  (or (::state @rt-ref)
      (throw (ex-info "remote/init! was not called on this runtime" {}))))

(defn- env-state [env]
  (state-ref-of (::sg/runtime-ref env)))

(defn- format-fns [rt-ref format]
  (let [rt @rt-ref
        {:keys [read write content-type] :as fns}
        (case format
          :transit {:read (::sg/transit-read rt)
                    :write (::sg/transit-str rt)
                    :content-type "application/transit+json"}
          :edn {:read (::sg/edn-read rt)
                :write (::sg/edn-str rt)
                :content-type "application/edn"}
          format)]
    (when-not (and (fn? read) (fn? write))
      (throw (ex-info
               "remote :format needs :read and :write fns; :transit and :edn require transit/init! or edn/init! first"
               {:format format})))
    (update fns :content-type #(or % "application/transit+json"))))

(defn- compute-view-closure
  "Precomputes transitive closure of :satisfies for each view on a table."
  [views]
  (let [direct (reduce-kv
                 (fn [m v cfg]
                   (assoc m v (conj (set (:satisfies cfg)) v)))
                 {}
                 views)]
    (reduce-kv
      (fn [m v _]
        (loop [seen (get direct v #{v})
               frontier (get direct v #{v})]
          (let [next-set (reduce (fn [s x] (into s (get direct x #{x}))) #{} frontier)
                added (into #{} (remove seen) next-set)]
            (if (empty? added)
              (assoc m v seen)
              (recur (into seen added) added)))))
      {}
      direct)))

(defn- expand-views [tables table view-set]
  (let [closure (get-in tables [table ::view-closure])]
    (reduce
      (fn [acc v]
        (into acc (get closure v #{v})))
      #{}
      view-set)))

(defn- entity-ready?
  "Returns true when the cached entity satisfies the requested view."
  [tables state table id view entity meta-entry]
  (let [loaded-views (:views meta-entry)
        ready-fn (get-in tables [table :views view :ready?])]
    (cond
      (identical? kv/NOT-FOUND entity) false
      (nil? entity) false
      (contains? loaded-views view) true
      (and ready-fn (ready-fn entity)) true
      ;; Optimistically created entity (not on server yet): don't suspend waiting for server
      (and (contains? (:base state) [table id])
           (identical? kv/NOT-FOUND (get-in state [:base [table id]])))
      true
      :else false)))

;; ---------------------------------------------------------------------------
;; Reverse index helpers: holders & tag->demands
;; ---------------------------------------------------------------------------

(defn- idx-add [idx k v]
  (update idx k (fn [s] (conj (or s #{}) v))))

(defn- idx-del [idx k v]
  (let [left (disj (get idx k) v)]
    (if (empty? left)
      (dissoc idx k)
      (assoc idx k left))))

(defn- set-demand-idents [state demand idents]
  (let [prev (get-in state [:demands demand :idents] #{})
        idents (set idents)]
    (if (= prev idents)
      state
      (-> state
          (assoc-in [:demands demand :idents] idents)
          (update :holders
            (fn [holders]
              (as-> holders $
                (reduce #(idx-del %1 %2 demand) $ (remove idents prev))
                (reduce #(idx-add %1 %2 demand) $ (remove prev idents)))))))))

(defn- set-demand-tags [state demand tags]
  (let [prev (get-in state [:demands demand :tags] #{})
        tags (set tags)]
    (if (= prev tags)
      state
      (-> state
          (assoc-in [:demands demand :tags] tags)
          (update :tag->demands
            (fn [tidx]
              (as-> tidx $
                (reduce #(idx-del %1 %2 demand) $ (remove tags prev))
                (reduce #(idx-add %1 %2 demand) $ (remove prev tags)))))))))

(defn- demand-default-tags [demand]
  (let [[kind a b] demand]
    (case kind
      :entity #{[a b]}
      :query #{}
      #{})))

(defn- ensure-demand [state demand]
  (if (contains? (:demands state) demand)
    state
    (let [tags (demand-default-tags demand)
          idents (if (= :entity (first demand)) #{[(nth demand 1) (nth demand 2)]} #{})]
      (-> state
          (assoc-in [:demands demand] {:refs 0 :status :idle :idents #{} :tags #{}})
          (set-demand-idents demand idents)
          (set-demand-tags demand tags)))))

(defn- want [{:keys [want-seq] :as state} demand]
  (-> state
      (ensure-demand demand)
      (assoc :want-seq (inc want-seq))
      (assoc-in [:wanted demand] {:n want-seq})))

;; ---------------------------------------------------------------------------
;; Request Batching & Flush Queue
;; ---------------------------------------------------------------------------

(declare flush!)

(defn- schedule-flush!
  "Coalesces all demands from a render pass into a single batch on next-tick."
  [rt-ref state-ref]
  (when-not (:flush-queued? @state-ref)
    (swap! state-ref assoc :flush-queued? true)
    (rt/next-tick #(flush! rt-ref state-ref))))

(defn- coalesce-demands
  "Turns a seq of demand keys into coalesced wire requests:
   multiple [:entity table id view] for the same [table view] become one request."
  [demands]
  (let [{:keys [entities queries]}
        (reduce
          (fn [acc demand]
            (let [[kind a b c] demand]
              (case kind
                :entity (update-in acc [:entities [a c]] (fnil conj #{}) b)
                :query (update acc :queries conj {:op :query :query a :params b})
                acc)))
          {:entities {} :queries []}
          demands)]
    (into
      (mapv (fn [[[table view] ids]]
              {:op :entity :table table :view view :ids ids})
        entities)
      queries)))

(defn- send-batch! [rt-ref state-ref batch-demands]
  (let [{:keys [transport]} @state-ref
        requests (coalesce-demands batch-demands)]
    (swap! state-ref
      (fn [state]
        (-> state
            (update :in-flight inc)
            (update :demands
              (fn [ds]
                (reduce
                  (fn [ds d]
                    (update ds d assoc :status :loading :dirty? false))
                  ds
                  batch-demands))))))

    ((:read-batch! transport)
     requests
     (fn [res]
       (when (identical? state-ref (::state @rt-ref))
         (sg/run-tx! rt-ref
           {:e ::read-done
            :demands batch-demands
            :response res}))))))

(defn- flush! [rt-ref state-ref]
  (when (identical? state-ref (::state @rt-ref))
    (swap! state-ref assoc :flush-queued? false)

    (let [{:keys [wanted demands in-flight max-batches batch-limit transport]} @state-ref
          ready? (if-some [ready-fn (:ready? transport)] (ready-fn) true)]
      (when (and ready? (seq wanted))
        (let [alive (->> wanted
                         (filter (fn [[demand _]]
                                   (let [{:keys [refs status]} (get demands demand)]
                                     (and (pos? (or refs 0))
                                          (not= :loading status)))))
                         (sort-by (fn [[_ {:keys [n]}]] (- n)))
                         (mapv first))
              free-slots (max 0 (- max-batches in-flight))]
          (if (or (zero? free-slots) (empty? alive))
            ;; Drop dead wanted items (scrolled past / unmounted before leaving)
            (swap! state-ref assoc :wanted
              (into {} (filter (fn [[d _]] (pos? (get-in demands [d :refs] 0)))) wanted))
            (let [take-n (min (count alive) (* free-slots batch-limit))
                  to-send (subvec alive 0 take-n)
                  remaining (subvec alive take-n)
                  remaining-map (select-keys wanted remaining)
                  batches (partition-all batch-limit to-send)]
              (swap! state-ref assoc :wanted remaining-map)
              (doseq [batch batches]
                (send-batch! rt-ref state-ref (vec batch))))))))))

;; ---------------------------------------------------------------------------
;; Optimistic Overlay & KV Application (always inside a Grove tx)
;; ---------------------------------------------------------------------------

(def ^:private removed ::removed)
(def ^:private attr-removed ::attr-removed)

(defn- merge-value [prev next]
  (if (and (map? prev) (map? next))
    (merge prev next)
    next))

(defn- apply-write [val write]
  (cond
    (nil? write) val
    (= removed write) kv/NOT-FOUND
    (and (map? write) (contains? write ::replace)) (::replace write)
    (identical? kv/NOT-FOUND val)
    (if (map? write)
      (reduce-kv (fn [m k v] (if (= attr-removed v) m (assoc m k v))) {} write)
      write)
    (and (map? val) (map? write))
    (reduce-kv
      (fn [m k v]
        (if (= attr-removed v)
          (dissoc m k)
          (assoc m k v)))
      val
      write)
    :else write))

(defn- visible
  "Server's :base value of ident with all :pending optimistic writes replayed on top."
  [{:keys [base pending]} ident]
  (reduce
    (fn [val {:keys [writes]}]
      (apply-write val (get writes ident)))
    (get base ident kv/NOT-FOUND)
    pending))

(defn- write-kv [tx-env [table id] val]
  (if-not (contains? tx-env table)
    tx-env
    (update tx-env table
      (fn [kv]
        (let [prev (get kv id kv/NOT-FOUND)]
          (cond
            (= prev val) kv
            (identical? kv/NOT-FOUND val) (dissoc kv id)
            :else (assoc kv id val)))))))

(defn- apply-server-entity
  ([tx-env state-ref ident value]
   (apply-server-entity tx-env state-ref ident value false))
  ([tx-env state-ref [table id :as ident] value replace?]
   (if (contains? (:base @state-ref) ident)
     (do (swap! state-ref update-in [:base ident]
           (fn [base]
             (cond
               (identical? kv/NOT-FOUND value) kv/NOT-FOUND
               replace? value
               (identical? kv/NOT-FOUND base) value
               :else (merge-value base value))))
         (write-kv tx-env ident (visible @state-ref ident)))
     (cond
       (identical? kv/NOT-FOUND value)
       (write-kv tx-env ident kv/NOT-FOUND)

       replace?
       (write-kv tx-env ident value)

       :else
       (write-kv tx-env ident (merge-value (get-in tx-env [table id]) value))))))

(defn- apply-entities-and-views
  ([tx-env state-ref entities views absent]
   (apply-entities-and-views tx-env state-ref entities views absent false))
  ([tx-env state-ref entities views absent allow-replace?]
   (let [tables (:tables @state-ref)
         ;; 1. Upsert entities into KV (via optimistic base if pending).
         ;; On authoritative reads (allow-replace? true), when the incoming view's closure
         ;; covers all currently loaded views for this entity, replace the row so server-retracted
         ;; attributes disappear cleanly; when a slimmer view or push delta arrives, merge.
         tx-env
         (reduce-kv
           (fn [tx-env table rows]
             (reduce-kv
               (fn [tx-env id row]
                 (let [ident [table id]
                       raw-views (get views ident #{:default})
                       expanded (expand-views tables table raw-views)
                       prev-meta (get-in tx-env [::entity-meta ident])
                       prev-views (when (map? prev-meta) (:views prev-meta))
                       replace? (and allow-replace?
                                     (or (empty? prev-views)
                                         (every? expanded prev-views)))
                       tx-env (apply-server-entity tx-env state-ref ident row replace?)]
                   (update-in tx-env [::entity-meta ident]
                     (fn [prev]
                       (-> (or prev {})
                           (dissoc :absent?)
                           (update :views (fnil into #{}) expanded)
                           (update :errors #(apply dissoc % expanded))
                           (update :stale-views #(apply dissoc % expanded)))))))
               tx-env
               rows))
           tx-env
           entities)

         ;; 2. Record confirmed absent entities
         tx-env
         (reduce
           (fn [tx-env [table id :as ident]]
             (-> tx-env
                 (apply-server-entity state-ref ident kv/NOT-FOUND true)
                 (assoc-in [::entity-meta ident] {:absent? true :views #{}})))
           tx-env
           absent)]
     tx-env)))

(defn- apply-retracts [tx-env state-ref retracts]
  (reduce
    (fn [tx-env [table id :as ident]]
      (let [held? (or (contains? (:holders @state-ref) ident)
                      (contains? (:pinned-orphans @state-ref) ident))]
        (-> tx-env
            (apply-server-entity state-ref ident kv/NOT-FOUND true)
            (cond->
              held? (assoc-in [::entity-meta ident] {:absent? true :views #{}})
              (not held?) (update ::entity-meta dissoc ident)))))
    tx-env
    retracts))

(defn- find-pending-entry [pending tx-id]
  (some #(when (or (= tx-id (:id %))
                   (contains? (:group-ids %) tx-id))
           %)
        pending))

(defn- settle-tx
  "Removes the pending entry for tx-id and recomputes visible KV for all idents it wrote."
  [tx-env state-ref tx-id]
  (if-some [{:keys [id writes]} (find-pending-entry (:pending @state-ref) tx-id)]
    (do (swap! state-ref update :pending (fn [ps] (filterv #(not= id (:id %)) ps)))
        (reduce
          (fn [tx-env ident]
            (let [tx-env (write-kv tx-env ident (visible @state-ref ident))]
              (when-not (some #(contains? (:writes %) ident) (:pending @state-ref))
                (swap! state-ref update :base dissoc ident))
              tx-env))
          tx-env
          (keys writes)))
    tx-env))

(defn- demand-finished
  "Notifies pending transacts waiting for demand to finish refreshing."
  [tx-env state-ref demand]
  (reduce
    (fn [tx-env {:keys [id waiting]}]
      (cond
        (not (contains? waiting demand))
        tx-env

        (= #{demand} waiting)
        (settle-tx tx-env state-ref id)

        :else
        (do (swap! state-ref update :pending
              (fn [ps]
                (mapv #(if (= id (:id %)) (update % :waiting disj demand) %) ps)))
            tx-env)))
    tx-env
    (:pending @state-ref)))

;; ---------------------------------------------------------------------------
;; Stale / Invalidation & Reactive Status
;; ---------------------------------------------------------------------------

(defn- sync-status-kv [tx-env state-ref]
  (if-not (contains? tx-env ::status)
    tx-env
    (let [{:keys [connected? in-flight pending]} @state-ref]
      (-> tx-env
          (assoc-in [::status :connected?] connected?)
          (assoc-in [::status :in-flight] in-flight)
          (assoc-in [::status :pending] (count pending))))))

(defn- apply-stale-kv [tx-env demands]
  (reduce
    (fn [tx-env demand]
      (let [[kind a b c] demand]
        (case kind
          :entity
          (let [ident [a b]]
            (if (contains? (get tx-env ::entity-meta) ident)
              (assoc-in tx-env [::entity-meta ident :stale-views c] true)
              tx-env))
          :query
          (let [qkey [a b]]
            (if (contains? (get tx-env ::queries) qkey)
              (assoc-in tx-env [::queries qkey :stale?] true)
              tx-env))
          tx-env)))
    tx-env
    demands))

(defn- find-affected-demands [state tags]
  (let [{:keys [tag->demands holders]} state]
    (reduce
      (fn [acc tag]
        (-> acc
            (into (get tag->demands tag))
            (into (get holders tag))))
      #{}
      tags)))

(defn- mark-stale*!
  "Marks all demands matching tags as stale. Active demands (refs > 0) are
   scheduled for background SWR revalidation; returns [affected live]."
  [rt-ref state-ref tags]
  (if (empty? tags)
    [#{} #{}]
    (let [tag-set (set tags)
          affected (find-affected-demands @state-ref tag-set)
          live (into #{}
                 (filter #(pos? (get-in @state-ref [:demands % :refs] 0)))
                 affected)]
      (swap! state-ref
        (fn [state]
          (let [next-epoch (inc (:sync-epoch state))]
            (reduce
              (fn [state demand]
                (let [{:keys [refs status]} (get-in state [:demands demand])]
                  (cond
                    (zero? (or refs 0))
                    (assoc-in state [:demands demand :stale?] true)

                    (= :loading status)
                    (assoc-in state [:demands demand :dirty?] true)

                    :else
                    (-> state
                        (assoc-in [:demands demand :stale?] true)
                        (want demand)))))
              (assoc state :sync-epoch next-epoch)
              affected))))

      (when (seq live)
        (schedule-flush! rt-ref state-ref))

      [affected live])))

(defn- mark-stale-in-tx [tx-env rt-ref state-ref tags]
  (let [[affected _] (mark-stale*! rt-ref state-ref tags)]
    (if (seq affected)
      (apply-stale-kv tx-env affected)
      tx-env)))

(defn- mark-active-stale-in-tx [tx-env rt-ref state-ref]
  (let [active-demands (into []
                         (comp (filter #(pos? (:refs (val %)))) (map key))
                         (:demands @state-ref))]
    (if (empty? active-demands)
      tx-env
      (do
        (swap! state-ref
          (fn [state]
            (reduce
              (fn [state d]
                (if (= :loading (get-in state [:demands d :status]))
                  (assoc-in state [:demands d :dirty?] true)
                  (-> state (assoc-in [:demands d :stale?] true) (want d))))
              state
              active-demands)))
        (schedule-flush! rt-ref state-ref)
        (apply-stale-kv tx-env active-demands)))))

(defn- update-cursor! [state-ref epoch cursor]
  (when (or epoch cursor)
    (swap! state-ref
      (fn [state]
        (cond-> state
          epoch (assoc :epoch epoch)
          (and cursor (or (not= epoch (:epoch state))
                          (> cursor (or (:cursor state) 0))))
          (assoc :cursor cursor))))))

;; ---------------------------------------------------------------------------
;; Event Handlers (run inside Grove transactions)
;; ---------------------------------------------------------------------------

(defn- window-params? [params]
  (and (map? params)
       (contains? params :page)
       (contains? params :page-size)
       (contains? params :offset)
       (contains? params :limit)))

(defn- window-base-key [query-id params]
  [query-id (dissoc params :page :page-size :offset :limit)])

(defn- apply-query-error [tx-env qkey err]
  (let [prev (get-in tx-env [::queries qkey])]
    (if (and (map? prev) (= :ready (:state prev)))
      ;; Background SWR refresh failed: preserve cached data instead of wiping the UI
      (assoc-in tx-env [::queries qkey]
        (-> prev
            (dissoc :stale?)
            (assoc :bg-error err)))
      (assoc-in tx-env [::queries qkey]
        {:state :error :error err}))))

(defn- on-read-done [tx-env {:keys [demands response]}]
  (let [rt-ref (::sg/runtime-ref tx-env)
        state-ref (state-ref-of rt-ref)
        {:keys [ok? entities views absent queries errors entity-idents entity-tags stale epoch cursor error]} response]

    (swap! state-ref update :in-flight #(max 0 (dec %)))

    (if-not ok?
      ;; Whole batch failed (network / transport error)
      (let [err (or error {:message "read batch failed"})
            tx-env (reduce
                     (fn [tx-env demand]
                       (let [[kind a b c] demand
                             default-tags (if (= :query kind) #{a} #{[a b]})]
                         (swap! state-ref
                           (fn [state]
                             (let [existing-tags (get-in state [:demands demand :tags])]
                               (-> state
                                   (update-in [:demands demand] assoc :status :idle :stale? false)
                                   (cond-> (empty? existing-tags)
                                     (set-demand-tags demand default-tags))))))
                         (-> (case kind
                               :entity
                               (update-in tx-env [::entity-meta [a b]]
                                 (fn [prev]
                                   (-> (or prev {})
                                       (assoc-in [:errors c] err)
                                       (update :stale-views dissoc c))))
                               :query
                               (apply-query-error tx-env [a b] err)
                               tx-env)
                             (demand-finished state-ref demand))))
                     tx-env
                     demands)]
        (schedule-flush! rt-ref state-ref)
        (sync-status-kv tx-env state-ref))

      ;; Batch succeeded
      (do
        (update-cursor! state-ref epoch cursor)

        (let [tx-env (apply-entities-and-views tx-env state-ref entities views absent true)

              ;; Apply per-entity errors
              tx-env (reduce-kv
                       (fn [tx-env [_ table id view] err]
                         (update-in tx-env [::entity-meta [table id]]
                           (fn [prev]
                             (-> (or prev {})
                                 (assoc-in [:errors view] err)
                                 (update :stale-views dissoc view)))))
                       tx-env
                       errors)

              ;; Apply query results
              tx-env (reduce-kv
                       (fn [tx-env [qid params :as qkey] entry]
                         (if (:ok? entry)
                           (let [clean (-> entry (dissoc :ok? :stale? :bg-error) (assoc :state :ready))
                                 tx-env (assoc-in tx-env [::queries qkey] clean)]
                             (if (and (window-params? params) (contains? entry :total))
                               (assoc-in tx-env [::window-meta (window-base-key qid params)]
                                 {:total (:total entry) :more? (:more? entry)})
                               tx-env))
                           (apply-query-error tx-env qkey (:error entry))))
                       tx-env
                       queries)

              stale-set (set stale)

              ;; Update demand metadata, holders, tags, and handle dirty/outdated revalidation
              tx-env
              (reduce
                (fn [tx-env demand]
                  (let [[kind a b] demand
                        dirty? (get-in @state-ref [:demands demand :dirty?])
                        ;; Compute idents & tags held by this demand
                        [idents tags]
                        (case kind
                          :entity
                          (let [ident [a b]
                                eidents (get entity-idents ident #{ident})
                                etags (into (get entity-tags ident #{ident}) eidents)]
                            [eidents etags])

                          :query
                          (let [entry (get queries [a b])
                                t (:table entry)
                                ids (:ids entry)
                                idents (cond
                                         (seq (:idents entry))
                                         (set (:idents entry))

                                         (and t (sequential? ids))
                                         (into #{} (map (fn [id] [t id])) ids)

                                         (and (= 1 (count demands)) (seq entities))
                                         (into #{} (for [[et rows] entities id (keys rows)] [et id]))

                                         :else #{})
                                ;; Ensure failed queries remain invalidatable by query-id (and any explicit tags)
                                base-tags (if (and entry (not (:ok? entry)))
                                            (into #{a} (:tags entry))
                                            (set (:tags entry)))
                                qtags (into base-tags idents)]
                            [idents qtags])

                          [#{} #{}])

                        outdated? (or dirty? (boolean (some stale-set tags)))]

                    (swap! state-ref
                      (fn [state]
                        (-> state
                            (update-in [:demands demand] assoc :status :idle :dirty? false :loaded? true :stale? outdated?)
                            (set-demand-idents demand idents)
                            (set-demand-tags demand tags)
                            (cond-> (and outdated? (pos? (get-in state [:demands demand :refs] 0)))
                              (want demand)))))

                    (let [tx-env (if outdated?
                                   (apply-stale-kv tx-env [demand])
                                   (demand-finished tx-env state-ref demand))]
                      tx-env)))
                tx-env
                demands)]

          (schedule-flush! rt-ref state-ref)
          (sync-status-kv tx-env state-ref))))))

(defn- on-stale [tx-env {:keys [tags epoch cursor]}]
  (let [rt-ref (::sg/runtime-ref tx-env)
        state-ref (state-ref-of rt-ref)]
    (update-cursor! state-ref epoch cursor)
    (mark-stale-in-tx tx-env rt-ref state-ref tags)))

(defn- on-push [tx-env {:keys [data views retract stale epoch cursor replace?]}]
  (let [rt-ref (::sg/runtime-ref tx-env)
        state-ref (state-ref-of rt-ref)]
    (update-cursor! state-ref epoch cursor)
    (let [tx-env (-> tx-env
                     (cond-> (seq data) (apply-entities-and-views state-ref data views nil (boolean replace?)))
                     (cond-> (seq retract) (apply-retracts state-ref retract)))]
      (if (seq stale)
        (mark-stale-in-tx tx-env rt-ref state-ref stale)
        tx-env))))

;; ---------------------------------------------------------------------------
;; Live Subscriptions (client demand tracking, interval merging, push apply)
;; ---------------------------------------------------------------------------

(declare flush-subs!)

(defn- schedule-sub-flush! [rt-ref state-ref]
  (when-not (:sub-flush-queued? @state-ref)
    (swap! state-ref assoc :sub-flush-queued? true)
    (rt/next-tick #(flush-subs! rt-ref state-ref))))

(defn- effective-sub-spec [readers]
  (let [intervals (into []
                    (keep (fn [[[interval _] cnt]]
                            (when (and (pos? cnt) (number? interval))
                              interval)))
                    readers)
        modes (into #{}
                (keep (fn [[[_ mode] cnt]]
                        (when (and (pos? cnt) (some? mode))
                          mode)))
                readers)]
    {:interval (when (seq intervals) (apply min intervals))
     :mode (cond
             (contains? modes :throttle) :throttle
             (contains? modes :interval) :interval
             :else nil)}))

(defn- flush-subs! [rt-ref state-ref]
  (when (identical? state-ref (::state @rt-ref))
    (swap! state-ref assoc :sub-flush-queued? false)

    (let [{:keys [transport sub-grace-ms]} @state-ref
          ready? (if-some [f (or (:sub-ready? transport) (:ready? transport))]
                   (f)
                   true)
          to-sub (volatile! [])
          to-unsub (volatile! [])
          evict-subs (volatile! [])]

      (swap! state-ref
        (fn [{:keys [subs] :as state}]
          (reduce-kv
            (fn [state sub-key {:keys [id sub params refs readers sent] :as entry}]
              (cond
                (pos? (or refs 0))
                (if-not ready?
                  state
                  (let [desired (effective-sub-spec readers)]
                    (if (= sent desired)
                      state
                      (do
                        (vswap! to-sub conj
                          (cond-> {:id id :sub sub :params params}
                            (some? (:interval desired)) (assoc :interval (:interval desired))
                            (some? (:mode desired)) (assoc :mode (:mode desired))))
                        (assoc-in state [:subs sub-key :sent] desired)))))

                (<= (or sub-grace-ms 0) 0)
                (do
                  (when (some? sent)
                    (vswap! to-unsub conj id))
                  (vswap! evict-subs conj sub-key)
                  (-> state
                      (update :subs dissoc sub-key)
                      (update :sid->sub-key dissoc id)))

                :else
                state))
            state
            subs)))

      (when (seq @evict-subs)
        (sg/run-tx! rt-ref
          {:e ::evict
           :idents []
           :queries []
           :window-keys []
           :subs @evict-subs}))

      (when (and ready? (seq @to-sub) (:subscribe! transport))
        ((:subscribe! transport) @to-sub))

      (when (and ready? (seq @to-unsub) (:unsubscribe! transport))
        ((:unsubscribe! transport) @to-unsub)))))

(defn- on-sub-data [tx-env {:keys [values]}]
  (let [state-ref (state-ref-of (::sg/runtime-ref tx-env))
        sid->sub-key (:sid->sub-key @state-ref)]
    (reduce-kv
      (fn [tx-env sid val]
        (if-some [sub-key (get sid->sub-key sid)]
          (let [next-entry {:state :ready :value val}
                prev-entry (get-in tx-env [::subs sub-key])]
            (if (= prev-entry next-entry)
              tx-env
              (assoc-in tx-env [::subs sub-key] next-entry)))
          tx-env))
      tx-env
      values)))

(defn- on-sub-error [tx-env {:keys [errors]}]
  (let [state-ref (state-ref-of (::sg/runtime-ref tx-env))
        sid->sub-key (:sid->sub-key @state-ref)]
    (reduce-kv
      (fn [tx-env sid err]
        (if-some [sub-key (get sid->sub-key sid)]
          (let [prev (get-in tx-env [::subs sub-key])]
            (if (and (map? prev) (= :ready (:state prev)))
              (assoc-in tx-env [::subs sub-key] (assoc prev :bg-error err))
              (assoc-in tx-env [::subs sub-key] {:state :error :error err})))
          tx-env))
      tx-env
      errors)))

(defn- on-hello [tx-env {:keys [conn-id epoch cursor]}]
  (let [rt-ref (::sg/runtime-ref tx-env)
        state-ref (state-ref-of rt-ref)
        {:keys [had-connected? transport]
         prev-epoch :epoch
         prev-cursor :cursor
         prev-conn-id :conn-id} @state-ref
        new-conn? (and (some? prev-conn-id) (some? conn-id) (not= prev-conn-id conn-id))]
    (swap! state-ref
      (fn [state]
        (-> state
            (assoc :connected? true :had-connected? true :conn-id conn-id)
            (update :subs
              (fn [subs]
                (reduce-kv
                  (fn [m k v] (assoc m k (assoc v :sent nil)))
                  {}
                  subs))))))
    (schedule-flush! rt-ref state-ref)
    (schedule-sub-flush! rt-ref state-ref)
    (let [tx-env (-> tx-env
                     (sync-status-kv state-ref)
                     (sg/queue-fx ::pump-transacts nil))]
      (cond
        ;; Reconnected with a new server conn-id: server discarded old dynamic subscriptions,
        ;; so revalidate active demands (SWR) to catch up AND re-register authorized tags.
        (and had-connected? new-conn?)
        (do (update-cursor! state-ref epoch cursor)
            (mark-active-stale-in-tx tx-env rt-ref state-ref))

        ;; Reconnected on same conn-id (or stateless hello with epoch): catch up via sync-since!
        (and had-connected? prev-epoch)
        (if (:sync-since! transport)
          (sg/queue-fx tx-env ::sync-since [prev-epoch prev-cursor])
          (do (update-cursor! state-ref epoch cursor)
              (mark-active-stale-in-tx tx-env rt-ref state-ref)))

        :else
        (do (update-cursor! state-ref epoch cursor)
            tx-env)))))

(defn- on-sync-done [tx-env {:keys [response]}]
  (let [rt-ref (::sg/runtime-ref tx-env)
        state-ref (state-ref-of rt-ref)
        {:keys [ok? resync? stale pushes epoch cursor]} response]
    (update-cursor! state-ref epoch cursor)
    (cond
      (or (not ok?) resync?)
      (mark-active-stale-in-tx tx-env rt-ref state-ref)

      :else
      (let [tx-env (reduce
                     (fn [tx-env push]
                       (on-push tx-env push))
                     tx-env
                     pushes)]
        (if (seq stale)
          (mark-stale-in-tx tx-env rt-ref state-ref stale)
          tx-env)))))

(defn- on-disconnect [tx-env _]
  (let [state-ref (state-ref-of (::sg/runtime-ref tx-env))]
    (swap! state-ref assoc :connected? false)
    (sync-status-kv tx-env state-ref)))

(defn- on-mark-stale-ev [tx-env {:keys [tags all-active?]}]
  (let [rt-ref (::sg/runtime-ref tx-env)
        state-ref (state-ref-of rt-ref)]
    (if all-active?
      (mark-active-stale-in-tx tx-env rt-ref state-ref)
      (mark-stale-in-tx tx-env rt-ref state-ref tags))))

;; ---------------------------------------------------------------------------
;; Mutations & Optimistic Diff Capture
;; ---------------------------------------------------------------------------

(defn- pump-transacts! [rt-ref state-ref]
  (when (identical? state-ref (::state @rt-ref))
    (let [{:keys [outbox sending transport]} @state-ref
          ready? (if-some [ready-fn (:ready? transport)] (ready-fn) true)]
      (when (and ready? (not sending) (seq outbox))
        (let [{:keys [id tx params on-result]} (first outbox)]
          (swap! state-ref assoc :outbox (subvec outbox 1) :sending id)
          ((:transact! transport)
           {:id id :tx tx :params params}
           (fn [res]
             (when (identical? state-ref (::state @rt-ref))
               (swap! state-ref assoc :sending nil)
               (sg/run-tx! rt-ref
                 {:e ::transact-done
                  :tx-id id
                  :on-result on-result
                  :response res})
               (pump-transacts! rt-ref state-ref)))))))))

(defn- on-transact-done [tx-env {:keys [tx-id on-result response]}]
  (let [rt-ref (::sg/runtime-ref tx-env)
        state-ref (state-ref-of rt-ref)
        {:keys [ok? unknown? result data views retract stale reject epoch cursor]} response
        pending-entry (find-pending-entry (:pending @state-ref) tx-id)
        writes (:writes pending-entry)
        is-owner? (= tx-id (:id pending-entry))]

    (update-cursor! state-ref epoch cursor)

    (let [tx-env
          (if ok?
            (let [;; 1. Apply authoritative :data and :retract directly to :base (1-RTT fast path!)
                  tx-env (-> tx-env
                             (cond-> (seq data) (apply-entities-and-views state-ref data views nil))
                             (cond-> (seq retract) (apply-retracts state-ref retract)))
                  covered (into (set retract)
                            (for [[t rows] data
                                  id (keys rows)]
                              [t id]))
                  uncovered (if is-owner?
                              (into #{} (remove covered) (keys writes))
                              #{})
                  ;; 2. Mark server :stale tags AND any uncovered optimistic writes stale
                  stale-tags (into (set stale) uncovered)
                  tx-env (if (seq stale-tags)
                           (mark-stale-in-tx tx-env rt-ref state-ref stale-tags)
                           tx-env)
                  ;; 3. Only wait to settle if this tx owns the pending writes AND uncovered writes have live demands
                  waiting (if (seq uncovered)
                            (into #{}
                              (filter #(pos? (get-in @state-ref [:demands % :refs] 0)))
                              (find-affected-demands @state-ref uncovered))
                            #{})]
              (cond
                (not is-owner?)
                tx-env

                (empty? waiting)
                (do
                  ;; Server succeeded with no live demand to refetch uncovered writes:
                  ;; fold the succeeded optimistic writes into :base before settling so KV
                  ;; does not revert to the pre-mutation value (inactive demands remain :stale?).
                  (when (seq uncovered)
                    (swap! state-ref update :base
                      (fn [base]
                        (reduce
                          (fn [base ident]
                            (assoc base ident (apply-write (get base ident kv/NOT-FOUND) (get writes ident))))
                          base
                          uncovered))))
                  (settle-tx tx-env state-ref tx-id))

                :else
                (do (swap! state-ref update :pending
                      (fn [ps]
                        (mapv #(if (= tx-id (:id %)) (assoc % :waiting waiting) %) ps)))
                    tx-env)))
            ;; Rejected or transport failure -> roll back pending overlay!
            ;; If :unknown? is true (server may have committed before disconnect/timeout),
            ;; also revalidate affected idents against server truth.
            (let [tx-env (settle-tx tx-env state-ref tx-id)]
              (if (and unknown? (seq writes))
                (mark-stale-in-tx tx-env rt-ref state-ref (keys writes))
                tx-env)))

          tx-env (sync-status-kv tx-env state-ref)]

      (cond-> tx-env
        on-result
        (sg/queue-fx ::run-tx
          (assoc on-result :call-result
            (if ok?
              {:ok? true :result result}
              (cond-> {:ok? false :reject reject}
                unknown? (assoc :unknown? true)))))))))

(defn- entity-writes [before after]
  (cond
    (and (map? before) (map? after))
    (let [added-or-changed
          (reduce-kv
            (fn [writes attr val]
              (if (= val (get before attr kv/NOT-FOUND))
                writes
                (assoc writes attr val)))
            {}
            after)]
      (reduce-kv
        (fn [writes attr _]
          (if (contains? after attr)
            writes
            (assoc writes attr attr-removed)))
        added-or-changed
        before))

    (map? after)
    after

    :else
    {::replace after}))

(defn- capture-writes [state-ref tx-info]
  (let [{:keys [tables]} @state-ref]
    (reduce-kv
      (fn [writes table {:keys [data-before data keys-new keys-updated keys-removed]}]
        (if-not (contains? tables table)
          writes
          (let [remember!
                (fn [ident id]
                  (swap! state-ref update :base
                    (fn [base]
                      (if (contains? base ident)
                        base
                        (assoc base ident (get data-before id kv/NOT-FOUND))))))]
            (as-> writes $
              (reduce
                (fn [writes id]
                  (let [ident [table id]]
                    (remember! ident id)
                    (assoc writes ident (entity-writes (get data-before id) (get data id)))))
                $
                (concat keys-new keys-updated))
              (reduce
                (fn [writes id]
                  (let [ident [table id]]
                    (remember! ident id)
                    (assoc writes ident removed)))
                $
                keys-removed)))))
      {}
      tx-info)))

(defn transact
  "In a deftx, sends a mutation to the server.
   Any writes made to remote KV tables in the same tx are captured from ::sg/tx-info
   after commit as an optimistic overlay: visible immediately, protected from flicker
   while in-flight, settled in 1 RTT if the server returns :data/:retract, or rolled
   back automatically on :reject.
   Omit local KV writes for purely server-authoritative mutations.

   opts (or event map directly as 4th arg for convenience):
   :on-result  event map run as a tx with :call-result ({:ok? true :result x} or {:ok? false :reject x :unknown? bool})"
  ([tx-env tx params]
   (transact tx-env tx params nil))
  ([tx-env tx params opts-or-event]
   (let [state-ref (env-state tx-env)
         tx-id (rt/next-id)
         on-result (if (and (map? opts-or-event) (contains? opts-or-event :e))
                     opts-or-event
                     (:on-result opts-or-event))
         already-queued? (contains? tx-env ::tx-ids)
         tx-env (update tx-env ::tx-ids (fnil conj []) tx-id)]
     (-> tx-env
         (cond-> (not already-queued?)
           (sg/queue-after-commit
             (fn [tx-env]
               (let [writes (capture-writes state-ref (::sg/tx-info tx-env))
                     ids (::tx-ids tx-env)]
                 (when (seq writes)
                   (swap! state-ref update :pending conj
                     (cond-> {:id (peek ids) :writes writes}
                       (> (count ids) 1) (assoc :group-ids (set ids)))))
                 tx-env))))
         (sg/queue-fx ::send {:id tx-id :tx tx :params params :on-result on-result})))))

;; ---------------------------------------------------------------------------
;; Demand Lifecycle & LRU + Grace + read-index Eviction
;; ---------------------------------------------------------------------------

(defn- add-sub-demand [state [_ sub-id params interval mode]]
  (let [sub-key [sub-id params]
        reader-key [interval mode]
        state (if (contains? (:subs state) sub-key)
                state
                (let [sid (inc (:sub-seq state))]
                  (-> state
                      (assoc :sub-seq sid)
                      (assoc-in [:subs sub-key]
                        {:id sid
                         :sub sub-id
                         :params params
                         :readers {}
                         :refs 0
                         :released-at nil
                         :sent nil})
                      (assoc-in [:sid->sub-key sid] sub-key))))]
    (update-in state [:subs sub-key]
      (fn [entry]
        (-> entry
            (update :refs inc)
            (update-in [:readers reader-key] (fnil inc 0))
            (assoc :released-at nil))))))

(defn- remove-sub-demand [state now [_ sub-id params interval mode]]
  (let [sub-key [sub-id params]
        reader-key [interval mode]]
    (if-not (contains? (:subs state) sub-key)
      state
      (update-in state [:subs sub-key]
        (fn [{:keys [refs readers] :as entry}]
          (let [next-reader-cnt (dec (get readers reader-key 1))
                next-readers (if (pos? next-reader-cnt)
                               (assoc readers reader-key next-reader-cnt)
                               (dissoc readers reader-key))
                next-refs (max 0 (dec (or refs 1)))]
            (-> entry
                (assoc :refs next-refs :readers next-readers)
                (cond-> (zero? next-refs)
                  (assoc :released-at now)))))))))

(defn- handle-demands [rt-ref state-ref prev next-demands]
  (let [now (js/Date.now)
        added (remove #(contains? prev %) next-demands)
        removed (remove #(contains? next-demands %) prev)
        sub-changed? (volatile! false)
        demand-changed? (volatile! false)]
    (swap! state-ref
      (fn [state]
        (as-> state $
          (reduce
            (fn [state demand]
              (if (= :sub (first demand))
                (do (vreset! sub-changed? true)
                    (add-sub-demand state demand))
                (do (vreset! demand-changed? true)
                    (let [state (ensure-demand state demand)
                          {:keys [stale? status]} (get-in state [:demands demand])]
                      (cond-> (update-in state [:demands demand :refs] inc)
                        (and stale? (not= :loading status)) (want demand))))))
            $
            added)
          (reduce
            (fn [state demand]
              (if (= :sub (first demand))
                (do (vreset! sub-changed? true)
                    (remove-sub-demand state now demand))
                (do (vreset! demand-changed? true)
                    (if (contains? (:demands state) demand)
                      (let [next-refs (max 0 (dec (get-in state [:demands demand :refs] 1)))]
                        (-> state
                            (assoc-in [:demands demand :refs] next-refs)
                            (cond-> (zero? next-refs)
                              (assoc-in [:demands demand :released-at] now))))
                      state))))
            $
            removed))))
    (when @demand-changed?
      (schedule-flush! rt-ref state-ref))
    (when @sub-changed?
      (schedule-sub-flush! rt-ref state-ref))))

(defn- on-evict [tx-env {:keys [idents queries window-keys subs]}]
  (let [tx-env (reduce
                 (fn [tx-env [table id :as ident]]
                   (-> tx-env
                       (update table dissoc id)
                       (update ::entity-meta dissoc ident)))
                 tx-env
                 idents)
        tx-env (reduce
                 (fn [tx-env qkey]
                   (update tx-env ::queries dissoc qkey))
                 tx-env
                 queries)
        tx-env (reduce
                 (fn [tx-env wkey]
                   (update tx-env ::window-meta dissoc wkey))
                 tx-env
                 window-keys)]
    (reduce
      (fn [tx-env skey]
        (update tx-env ::subs dissoc skey))
      tx-env
      subs)))

(defn sweep!
  "Evicts expired or over-budget inactive demands, expired inactive subscriptions, and
   any entities no longer pinned by another demand, a pending optimistic tx, or Grove's
   live KV read-index."
  [rt-ref]
  (let [state-ref (state-ref-of rt-ref)
        {:keys [demands subs grace-ms sub-grace-ms max-cached tables transport pinned-orphans]} @state-ref
        now (js/Date.now)
        inactive (->> demands
                      (filter (fn [[_ {:keys [refs status]}]]
                                (and (zero? (or refs 0))
                                     (not= :loading status))))
                      (sort-by (fn [[_ {:keys [released-at]}]] (or released-at 0)))
                      (vec))
        excess (max 0 (- (count inactive) max-cached))
        expired (into []
                  (keep-indexed
                    (fn [idx [demand {:keys [released-at]}]]
                      (let [t-grace (when (= :entity (first demand))
                                      (get-in tables [(second demand) :grace-ms]))
                            g (or t-grace grace-ms)]
                        (when (or (< idx excess)
                                  (<= (or released-at 0) (- now g)))
                          demand))))
                  inactive)
        expired-subs (into []
                       (keep (fn [[sub-key {:keys [refs released-at]}]]
                               (when (and (zero? (or refs 0))
                                          (<= (or released-at 0) (- now (or sub-grace-ms 0))))
                                 sub-key)))
                       subs)]

    (when (or (seq expired) (seq pinned-orphans) (seq expired-subs))
      (let [cand-idents (into (or pinned-orphans #{}) (mapcat #(get-in demands [% :idents])) expired)
            cand-tags (into (or pinned-orphans #{}) (mapcat #(get-in demands [% :tags])) expired)
            evict-queries (into []
                            (comp (filter #(= :query (first %)))
                                  (map (fn [[_ qid params]] [qid params])))
                            expired)
            unsub-ids (volatile! [])]

        (when (or (seq expired) (seq expired-subs))
          (swap! state-ref
            (fn [state]
              (let [state (reduce
                            (fn [state demand]
                              (-> state
                                  (set-demand-idents demand #{})
                                  (set-demand-tags demand #{})
                                  (update :demands dissoc demand)
                                  (update :wanted dissoc demand)))
                            state
                            expired)]
                (reduce
                  (fn [state sub-key]
                    (if-some [{:keys [id refs sent]} (get-in state [:subs sub-key])]
                      (if (zero? (or refs 0))
                        (do
                          (when (some? sent)
                            (vswap! unsub-ids conj id))
                          (-> state
                              (update :subs dissoc sub-key)
                              (update :sid->sub-key dissoc id)))
                        state)
                      state))
                  state
                  expired-subs)))))

        (let [{:keys [holders base tag->demands] remaining-demands :demands} @state-ref
              ;; Separate unheld idents that are still pinned via sg/kv-lookup (pinned-orphans)
              ;; from those ready for eviction so they remain tracked and get evicted once unmounted.
              [evict-idents next-orphans]
              (reduce
                (fn [[evict orphans] [table id :as ident]]
                  (cond
                    (or (contains? holders ident) (contains? base ident))
                    [evict orphans]

                    (impl/key-read? table id)
                    [evict (conj orphans ident)]

                    :else
                    [(conj evict ident) orphans]))
                [[] #{}]
                cand-idents)

              _ (when (not= pinned-orphans next-orphans)
                  (swap! state-ref assoc :pinned-orphans next-orphans))

              ;; Only forget tags not held by any remaining demand or still-pinned orphan
              dropped-tags (into []
                             (remove (fn [tag]
                                       (or (contains? tag->demands tag)
                                           (contains? next-orphans tag))))
                             cand-tags)

              ;; Clean up ::window-meta entries when no remaining query demand shares the window base key
              cand-window-keys (into #{}
                                 (keep (fn [[qid params]]
                                         (when (window-params? params)
                                           (window-base-key qid params))))
                                 evict-queries)
              evict-window-keys
              (if (empty? cand-window-keys)
                []
                (let [live-window-keys
                      (into #{}
                        (keep (fn [[kind qid params]]
                                (when (and (= :query kind) (window-params? params))
                                  (window-base-key qid params))))
                        (keys remaining-demands))]
                  (into [] (remove live-window-keys) cand-window-keys)))]

          (when (or (seq evict-idents) (seq evict-queries) (seq evict-window-keys) (seq expired-subs))
            (sg/run-tx! rt-ref
              {:e ::evict
               :idents evict-idents
               :queries evict-queries
               :window-keys evict-window-keys
               :subs expired-subs}))

          (when (and (seq dropped-tags) (:forget! transport))
            ((:forget! transport) dropped-tags))

          (when (and (seq @unsub-ids) (:unsubscribe! transport))
            ((:unsubscribe! transport) @unsub-ids)))))))

;; ---------------------------------------------------------------------------
;; Public Read API (usable inside sg/query AND directly in defc bind!)
;; ---------------------------------------------------------------------------

(defn- trigger-demand! [env demand]
  (sg/demand! env demand)
  (let [rt-ref (::sg/runtime-ref env)
        state-ref (state-ref-of rt-ref)]
    (swap! state-ref
      (fn [state]
        (let [state (ensure-demand state demand)
              {:keys [status]} (get-in state [:demands demand])]
          (if (= :loading status)
            state
            (want state demand)))))
    (schedule-flush! rt-ref state-ref)))

(defn- register-demand! [env demand]
  (sg/demand! env demand)
  (let [state-ref (env-state env)]
    (when-not (contains? (:demands @state-ref) demand)
      (swap! state-ref ensure-demand demand))))

(defn- optimistic-create? [state ident]
  (and (contains? (:base state) ident)
       (identical? kv/NOT-FOUND (get-in state [:base ident]))))

(defn- entity* [env table id view {:keys [suspend?] :or {suspend? true}}]
  (if (nil? id)
    (if suspend? nil {:state :ready :value nil})
    (let [view (or view :default)
          ident [table id]
          demand [:entity table id view]
          state-ref (env-state env)
          state @state-ref
          tables (:tables state)
          ;; Read through ObservedData so KV writes invalidate this query!
          meta-entry (get (get env ::entity-meta) ident kv/NOT-FOUND)
          entity-val (get (get env table) id kv/NOT-FOUND)
          err (when (map? meta-entry) (get-in meta-entry [:errors view]))
          absent? (and (map? meta-entry) (:absent? meta-entry))
          opt-create? (and (optimistic-create? state ident)
                           (not (identical? kv/NOT-FOUND entity-val))
                           (some? entity-val))
          ready? (or opt-create?
                     (and (not absent?)
                          (entity-ready? tables state table id view entity-val meta-entry)))
          stale? (boolean (or (when (map? meta-entry) (get-in meta-entry [:stale-views view]))
                              (get-in state [:demands demand :stale?])))]

      (cond
        ready?
        (do (register-demand! env demand)
            (if suspend?
              entity-val
              (cond-> {:state (if stale? :stale :ready)
                       :value entity-val}
                (some? err) (assoc :error err))))

        (some? err)
        (do (register-demand! env demand)
            (if suspend?
              {::error err}
              {:state :error :error err :value (when (map? entity-val) entity-val)}))

        absent?
        (do (register-demand! env demand)
            (if suspend? nil {:state :absent :value nil}))

        :else
        (do (trigger-demand! env demand)
            (if suspend?
              (sg/suspend!)
              {:state :loading
               :value (when (map? entity-val) entity-val)}))))))

(defn- normalize-opts [opts]
  (when (seq opts) opts))

(defn- entity-slot-query [env table id view opts]
  (entity* env table id view opts))

(defn entity
  "Reads a remote entity from KV (fetching if missing or if the requested view
   isn't loaded yet). Works both inside a sg/query fn (pass env as 1st arg) and
   directly inside a defc bind (omit env).

   In a query:  (remote/entity env :item id)
                (remote/entity env :item id :detail)
                (remote/entity env :item id :detail {:suspend? false})
   In a bind:   (remote/entity :item id)
                (remote/entity :item id :detail)
                (remote/entity :item id :detail {:suspend? false})"
  {:arglists '([table id]
               [table id view-or-opts]
               [table id view opts]
               [env table id]
               [env table id view-or-opts]
               [env table id view opts])}
  ([table id]
   (if (keyword? table)
     (impl/slot-query [table id :default nil] entity-slot-query)
     (throw (ex-info "invalid args to remote/entity" {:table table :id id}))))
  ([env-or-table table-or-id id-or-view-or-opts]
   (cond
     (map? env-or-table)
     (entity* env-or-table table-or-id id-or-view-or-opts :default nil)

     (keyword? env-or-table)
     (let [[view opts] (if (map? id-or-view-or-opts)
                         [:default id-or-view-or-opts]
                         [(or id-or-view-or-opts :default) nil])]
       (impl/slot-query [env-or-table table-or-id view (normalize-opts opts)] entity-slot-query))

     :else
     (throw (ex-info "invalid args to remote/entity"
              {:env-or-table env-or-table
               :table-or-id table-or-id
               :id-or-view-or-opts id-or-view-or-opts}))))
  ([env-or-table table-or-id id-or-view view-or-opts]
   (cond
     (map? env-or-table)
     (let [[view opts] (if (map? view-or-opts)
                         [:default view-or-opts]
                         [view-or-opts nil])]
       (entity* env-or-table table-or-id id-or-view view opts))

     (keyword? env-or-table)
     (impl/slot-query [env-or-table table-or-id (or id-or-view :default) (normalize-opts view-or-opts)] entity-slot-query)

     :else
     (throw (ex-info "invalid args to remote/entity"
              {:env-or-table env-or-table
               :table-or-id table-or-id
               :id-or-view id-or-view
               :view-or-opts view-or-opts}))))
  ([env table id view opts]
   (entity* env table id view opts)))

(defn- resolve-query-entry [env entry]
  (cond
    (contains? entry :value)
    (:value entry)

    (and (:table entry) (sequential? (:ids entry)))
    (let [kv-table (get env (:table entry))
          items (into [] (keep #(get kv-table %)) (:ids entry))]
      (-> entry
          (dissoc :state :tags :stale? :bg-error)
          (assoc :items items)))

    :else
    (dissoc entry :state :tags :stale? :bg-error)))

(defn- query* [env query-id params {:keys [suspend?] :or {suspend? true}}]
  (let [demand [:query query-id params]
        state @(env-state env)
        entry (get (get env ::queries) [query-id params] kv/NOT-FOUND)]
    (cond
      (identical? kv/NOT-FOUND entry)
      (do (trigger-demand! env demand)
          (if suspend?
            (sg/suspend!)
            {:state :loading :value nil}))

      (= :error (:state entry))
      (do (register-demand! env demand)
          (if suspend?
            {::error (:error entry)}
            {:state :error :error (:error entry)}))

      :else
      (let [val (resolve-query-entry env entry)
            stale? (boolean (or (:stale? entry)
                                (get-in state [:demands demand :stale?])))
            bg-err (:bg-error entry)]
        (register-demand! env demand)
        (if suspend?
          val
          (cond-> {:state (if stale? :stale :ready)
                   :value val}
            (some? bg-err) (assoc :error bg-err)))))))

(defn- query-slot-fn [env query-id params opts]
  (query* env query-id params opts))

(defn query
  "Reads a named remote query from KV (fetching if missing). Works both inside a
   sg/query fn (pass env as 1st arg) and directly inside a defc bind (omit env).

   In a query:  (remote/query env :app/stats)
                (remote/query env :items/search {:q text})
                (remote/query env :items/search {:q text} {:suspend? false})
   In a bind:   (remote/query :app/stats)
                (remote/query :items/search {:q text})
                (remote/query :items/search {:q text} {:suspend? false})"
  {:arglists '([query-id]
               [query-id params]
               [query-id params opts]
               [env query-id]
               [env query-id params]
               [env query-id params opts])}
  ([query-id]
   (impl/slot-query [query-id nil nil] query-slot-fn))
  ([env-or-query-id query-id-or-params]
   (if (map? env-or-query-id)
     (query* env-or-query-id query-id-or-params nil nil)
     (impl/slot-query [env-or-query-id query-id-or-params nil] query-slot-fn)))
  ([env-or-query-id query-id-or-params params-or-opts]
   (if (map? env-or-query-id)
     (query* env-or-query-id query-id-or-params params-or-opts nil)
     (impl/slot-query [env-or-query-id query-id-or-params (normalize-opts params-or-opts)] query-slot-fn)))
  ([env query-id params opts]
   (query* env query-id params opts)))

(defn- subscribe* [env sub-id params {:keys [interval mode suspend?] :or {suspend? true}}]
  (let [demand [:sub sub-id params interval mode]
        entry (get (get env ::subs) [sub-id params] kv/NOT-FOUND)]
    (sg/demand! env demand)
    (cond
      (identical? kv/NOT-FOUND entry)
      (if suspend?
        (sg/suspend!)
        {:state :loading :value nil})

      (= :error (:state entry))
      (if suspend?
        {::error (:error entry)}
        {:state :error :error (:error entry)})

      :else
      (let [val (:value entry)
            bg-err (:bg-error entry)]
        (if suspend?
          val
          (cond-> {:state :ready :value val}
            (some? bg-err) (assoc :error bg-err)))))))

(defn- sub-slot-fn [env sub-id params opts]
  (subscribe* env sub-id params opts))

(defn subscribe
  "Subscribes to a server-pushed remote subscription stored in ::subs.
   Works both inside a sg/query fn (pass env as 1st arg) and directly inside a defc bind (omit env).

   In a query:  (remote/subscribe env :home/summary)
                (remote/subscribe env :home/summary nil {:interval 10000})
                (remote/subscribe env :home/summary nil {:interval 10000 :suspend? false})
   In a bind:   (remote/subscribe :home/summary)
                (remote/subscribe :home/summary nil {:interval 10000})
                (remote/subscribe :home/summary nil {:interval 10000 :suspend? false})

   opts:
   :interval   requested push interval in ms (clamped by server; nil uses server default)
   :mode       optional :throttle (leading+trailing) or :interval (strict periodic); nil uses server default
   :suspend?   default true; when false returns {:state :loading|:ready|:error :value v :error err}"
  {:arglists '([sub-id]
               [sub-id params]
               [sub-id params opts]
               [env sub-id]
               [env sub-id params]
               [env sub-id params opts])}
  ([sub-id]
   (impl/slot-query [sub-id nil nil] sub-slot-fn))
  ([env-or-sub-id sub-id-or-params]
   (if (map? env-or-sub-id)
     (subscribe* env-or-sub-id sub-id-or-params nil nil)
     (impl/slot-query [env-or-sub-id sub-id-or-params nil] sub-slot-fn)))
  ([env-or-sub-id sub-id-or-params params-or-opts]
   (if (map? env-or-sub-id)
     (subscribe* env-or-sub-id sub-id-or-params params-or-opts nil)
     (impl/slot-query [env-or-sub-id sub-id-or-params (normalize-opts params-or-opts)] sub-slot-fn)))
  ([env sub-id params opts]
   (subscribe* env sub-id params opts)))

(defn window
  "In a sg/query fn: paged window helper for vlist3.
   Returns {:item-count n :offset n :slice [...] :more? bool} or suspends while
   pages for the visible window are loading.

   Each page is requested as query-id with (assoc params :page p :page-size size :offset (* p size) :limit size).
   opts:
   :page-size  rows per page (default 50)"
  ([env query-id params win]
   (window env query-id params win nil))
  ([env query-id params {:keys [offset num] :or {offset 0 num 0}} {:keys [page-size] :or {page-size 50}}]
   (let [params (or params {})
         offset (max 0 (or offset 0))
         header (get (get env ::window-meta) (window-base-key query-id params))
         total (:total header)
         more? (:more? header)]
     (if (and (number? total) (pos? total) (>= offset total))
       ;; Offset scrolled past a shrunk total: don't fetch out-of-range pages
       (cond-> {:item-count total :offset offset :slice []}
         (some? more?) (assoc :more? more?))
       (let [end (cond-> (+ offset (max num 1)) (and (number? total) (pos? total)) (min total))
             first-page (quot offset page-size)
             last-page (quot (max offset (dec end)) page-size)

             pages
             (mapv
               (fn [p]
                 (let [p-params (assoc params :page p :page-size page-size :offset (* p page-size) :limit page-size)
                       demand [:query query-id p-params]
                       entry (get (get env ::queries) [query-id p-params] kv/NOT-FOUND)]
                   (cond
                     (identical? kv/NOT-FOUND entry)
                     (do (trigger-demand! env demand)
                         {:wait? true})

                     (= :error (:state entry))
                     (do (register-demand! env demand)
                         {:error (:error entry)})

                     :else
                     (let [resolved (resolve-query-entry env entry)
                           rows (if (map? resolved) (or (:items resolved) (:rows resolved) []) resolved)]
                       (register-demand! env demand)
                       {:rows (vec rows)
                        :total (:total entry)
                        :more? (:more? entry)}))))
               (range first-page (inc last-page)))

             total (or (some :total pages) total)
             more? (or (some :more? pages) more?)]

         (cond
           (some :error pages)
           {:item-count (or total 0) :offset offset :slice [] ::error (some :error pages)}

           (some :wait? pages)
           (sg/suspend! {:item-count (or total 0)})

           :else
           (let [all-rows (into [] (mapcat :rows) pages)
                 start (min (- offset (* first-page page-size)) (count all-rows))
                 stop (max start (min (- end (* first-page page-size)) (count all-rows)))]
             (cond-> {:item-count (or total (count all-rows))
                      :offset offset
                      :slice (subvec all-rows start stop)}
               (some? more?) (assoc :more? more?)))))))))

;; ---------------------------------------------------------------------------
;; Imperative Controls & Stats
;; ---------------------------------------------------------------------------

(defn invalidate!
  "Marks all demands matching tags/idents stale. Active demands revalidate in background."
  [rt-ref tags]
  (let [rt-ref (sg/get-runtime rt-ref)
        state-ref (state-ref-of rt-ref)
        tag-set (set tags)]
    (when (seq tag-set)
      (sg/run-tx! rt-ref {:e ::mark-stale :tags tag-set}))
    (into #{}
      (filter #(pos? (get-in @state-ref [:demands % :refs] 0)))
      (find-affected-demands @state-ref tag-set))))

(defn revalidate-active!
  "Revalidates every currently mounted demand in background (SWR)."
  [rt-ref]
  (let [rt-ref (sg/get-runtime rt-ref)
        state-ref (state-ref-of rt-ref)
        active-count (count (filter #(pos? (:refs %)) (vals (:demands @state-ref))))]
    (when (pos? active-count)
      (sg/run-tx! rt-ref {:e ::mark-stale :all-active? true}))
    active-count))

(defn stats
  "Returns cache statistics for debugging / devtools."
  [rt-ref]
  (let [{:keys [demands subs holders pending in-flight connected?]} @(state-ref-of (sg/get-runtime rt-ref))]
    {:demands (count demands)
     :live (count (filter #(pos? (:refs %)) (vals demands)))
     :subs (count subs)
     :live-subs (count (filter #(pos? (:refs %)) (vals subs)))
     :held (count holders)
     :pending (count pending)
     :in-flight in-flight
     :connected? connected?}))

;; ---------------------------------------------------------------------------
;; Built-in Transports: WebSocket, HTTP (+ optional SSE), and Local
;; ---------------------------------------------------------------------------

(defn ws-transport
  "WebSocket transport built on shadow.grove.websocket.
   Reads, mutations, forget, sync catch-up, and server push all share one connection.

   opts:
   :url           WebSocket path or URL (e.g. \"/api/ws\")
   :format        :transit (default), :edn, or {:read fn :write fn}
   :ws-id         id under shadow.grove.websocket (default ::ws)
   :reconnect-ms  initial reconnect delay in ms (default 1000)
   :timeout-ms    per-call timeout in ms (default 30000; nil or 0 disables)"
  [{:keys [url format ws-id reconnect-ms timeout-ms]
    :or {format :transit ws-id ::ws reconnect-ms 1000 timeout-ms 30000}}]
  {:pre [(string? url)]}
  (fn [rt-ref callbacks]
    (let [{:keys [on-hello on-stale on-push on-sub-data on-sub-error on-disconnect]} callbacks
          inst-id (rt/next-id)
          conn-state (atom {:open? false :attempt 0 :stopped? false :timer nil})
          pending-cbs (atom {})
          clear-pending!
          (fn []
            (let [entries @pending-cbs]
              (reset! pending-cbs {})
              (doseq [[_ {:keys [timer]}] entries]
                (when timer (js/clearTimeout timer)))))
          schedule-reconnect!
          (fn []
            (when-not (:stopped? @conn-state)
              (let [attempt (:attempt @conn-state)
                    delay-ms (js/Math.min 15000 (* reconnect-ms (js/Math.pow 2 (js/Math.min attempt 4))))]
                (swap! conn-state update :attempt inc)
                (when-some [t (:timer @conn-state)] (js/clearTimeout t))
                (swap! conn-state assoc :timer
                  (js/setTimeout
                    (fn []
                      (when-not (:stopped? @conn-state)
                        (ws/connect! rt-ref ws-id)))
                    delay-ms)))))

          on-ws-msg
          (fn [_ msg]
            (when-not (:stopped? @conn-state)
              (case (:op msg)
                ::hello (do (swap! conn-state assoc :open? true :attempt 0)
                            (on-hello msg))
                ::stale (on-stale msg)
                ::push (on-push msg)
                ::sub-data (on-sub-data msg)
                ::sub-error (on-sub-error msg)
                nil)))

          on-ws-close-ev (keyword "shadow.grove.remote" (str "ws-closed-" inst-id))
          call-reply-ev (keyword "shadow.grove.remote" (str "ws-call-reply-" inst-id))]

      (sg/reg-event rt-ref on-ws-close-ev
        (fn [tx-env _]
          (if (:stopped? @conn-state)
            tx-env
            (do (swap! conn-state assoc :open? false)
                (schedule-reconnect!)
                (sg/queue-fx tx-env ::run-cb {:cb on-disconnect})))))

      (sg/reg-event rt-ref call-reply-ev
        (fn [tx-env {:keys [cb-id call-result]}]
          (if-some [{:keys [cb timer]} (get @pending-cbs cb-id)]
            (do (when timer (js/clearTimeout timer))
                (swap! pending-cbs dissoc cb-id)
                (if (:stopped? @conn-state)
                  tx-env
                  (sg/queue-fx tx-env ::run-cb
                    {:cb cb
                     :arg (if (= ::ws/closed (:op call-result))
                            {:ok? false :unknown? true :error {:message "websocket closed"} :reject ::closed}
                            call-result)})))
            tx-env)))

      (let [do-call!
            (fn [msg cb]
              (let [cb-id (rt/next-id)
                    timer (when (and (number? timeout-ms) (pos? timeout-ms))
                            (js/setTimeout
                              (fn []
                                (when-some [{:keys [cb]} (get @pending-cbs cb-id)]
                                  (swap! pending-cbs dissoc cb-id)
                                  (when-not (:stopped? @conn-state)
                                    (cb {:ok? false
                                         :unknown? true
                                         :error {:message "request timed out"}
                                         :reject ::timeout}))))
                              timeout-ms))]
                (swap! pending-cbs assoc cb-id {:cb cb :timer timer})
                (ws/call! rt-ref ws-id msg {:e call-reply-ev :cb-id cb-id})))]

        (ws/init! rt-ref ws-id
          {:url url
           :format format
           :on-message on-ws-msg
           :on-close {:e on-ws-close-ev}})

        {:start!
         (fn []
           (swap! conn-state assoc :stopped? false)
           (ws/connect! rt-ref ws-id))

         :stop!
         (fn []
           (swap! conn-state assoc :stopped? true :open? false)
           (when-some [t (:timer @conn-state)] (js/clearTimeout t))
           (clear-pending!)
           (ws/close! rt-ref ws-id))

         :ready?
         (fn [] (:open? @conn-state))

         :read-batch!
         (fn [requests cb]
           (do-call! {:op ::read :requests requests} cb))

         :transact!
         (fn [{:keys [id tx params]} cb]
           (do-call! (cond-> {:op ::transact :tx tx :params params}
                       (some? id) (assoc :tx-id id))
             cb))

         :forget!
         (fn [tags]
           (when (:open? @conn-state)
             (ws/cast! rt-ref ws-id {:op ::forget :tags tags})))

         :subscribe!
         (fn [subs]
           (when (:open? @conn-state)
             (ws/cast! rt-ref ws-id {:op ::subscribe :subs subs})))

         :unsubscribe!
         (fn [ids]
           (when (:open? @conn-state)
             (ws/cast! rt-ref ws-id {:op ::unsubscribe :ids ids})))

         :sync-since!
         (fn [since cb]
           (do-call! {:op ::sync :since since} cb))}))))

(defn http-transport
  "HTTP POST transport with optional SSE push stream.
   Without :sse-url, works as a stateless pull-only transport (batch reads + mutations + SWR).
   With :sse-url, opens an EventSource for live push invalidation and/or push data.

   opts:
   :url                   base HTTP POST URL (e.g. \"/api/remote\")
   :sse-url               optional SSE URL (e.g. \"/api/remote/sse\")
   :format                :transit (default), :edn, or {:read fn :write fn :content-type str}
   :single-endpoint?      if true, POST everything to :url instead of :url/read, :url/transact, etc.
   :headers               optional extra HTTP headers map
   :credentials           fetch credentials mode (default \"same-origin\")
   :timeout-ms            per-request fetch timeout in ms (default 30000; nil or 0 disables)
   :sse-stale-ms          SSE watchdog timeout in ms (default 60000)
   :revalidate-on-focus?  revalidate active demands on window focus/visible (default false when SSE active)"
  [{:keys [url sse-url format single-endpoint? headers credentials timeout-ms sse-stale-ms revalidate-on-focus?]
    :or {format :transit credentials "same-origin" timeout-ms 30000 sse-stale-ms 60000}}]
  {:pre [(string? url)]}
  (fn [rt-ref callbacks]
    (let [{:keys [on-hello on-stale on-push on-sub-data on-sub-error on-disconnect on-revalidate]} callbacks
          {:keys [read write content-type]} (format-fns rt-ref format)
          state (atom {:conn-id nil :sse nil :last-event 0 :stopped? false :watchdog nil :listeners [] :had-pre-conn-read? false})

          post!
          (fn [subpath body cb]
            (let [target (if single-endpoint? url (str url "/" subpath))
                  hdrs (js/Object.assign #js {"content-type" content-type} (clj->js (or headers {})))
                  use-timeout? (and (number? timeout-ms) (pos? timeout-ms))
                  ^js controller (when (and use-timeout? (exists? js/AbortController))
                                   (js/AbortController.))
                  done? (volatile! false)
                  finish! (fn [val]
                            (when-not @done?
                              (vreset! done? true)
                              (when (and cb (not (:stopped? @state)))
                                (cb val))))
                  timer (when use-timeout?
                          (js/setTimeout
                            (fn []
                              (when controller (.abort controller))
                              (finish! {:ok? false
                                        :unknown? true
                                        :error {:status ::timeout :message "request timed out"}
                                        :reject ::timeout}))
                            timeout-ms))
                  clear-timer! (fn [] (when timer (js/clearTimeout timer)))
                  fetch-opts #js {:method "POST"
                                  :credentials credentials
                                  :headers hdrs
                                  :body (write body)}]
              (when controller
                (set! (.-signal fetch-opts) (.-signal controller)))
              (when-some [cid (:conn-id @state)]
                (aset hdrs "x-grove-conn" cid))
              (-> (js/fetch target fetch-opts)
                  (.then
                    (fn [^js res]
                      (let [status (.-status res)]
                        (cond
                          (= 204 status)
                          (do (clear-timer!)
                              (finish! {:ok? true}))

                          (or (.-ok res) (= 422 status))
                          (-> (.text res)
                              (.then (fn [txt]
                                       (clear-timer!)
                                       (finish! (read txt)))))

                          :else
                          (do (clear-timer!)
                              (finish! {:ok? false
                                        :unknown? true
                                        :error {:status status :message (str "HTTP " status)}
                                        :reject status}))))))
                  (.catch
                    (fn [err]
                      (clear-timer!)
                      (finish! {:ok? false
                                :unknown? true
                                :error {:status ::network :message (str err)}
                                :reject ::network}))))))

          connect-sse!
          (fn connect-sse! []
            (when (and sse-url (not (:stopped? @state)))
              (when-some [^js old (:sse @state)]
                (.close old))
              (let [^js sse (if (= "include" credentials)
                              (js/EventSource. sse-url #js {:withCredentials true})
                              (js/EventSource. sse-url))]
                (swap! state assoc :sse sse :last-event (js/Date.now))
                (.addEventListener sse "hello"
                  (fn [^js e]
                    (when-not (:stopped? @state)
                      (swap! state assoc :last-event (js/Date.now))
                      (let [msg (read (.-data e))
                            had-pre? (:had-pre-conn-read? @state)]
                        (swap! state assoc :conn-id (:conn-id msg) :had-pre-conn-read? false)
                        (on-hello msg)
                        (when had-pre?
                          (on-revalidate))))))
                (.addEventListener sse "stale"
                  (fn [^js e]
                    (when-not (:stopped? @state)
                      (swap! state assoc :last-event (js/Date.now))
                      (on-stale (read (.-data e))))))
                (.addEventListener sse "push"
                  (fn [^js e]
                    (when-not (:stopped? @state)
                      (swap! state assoc :last-event (js/Date.now))
                      (on-push (read (.-data e))))))
                (.addEventListener sse "sub-data"
                  (fn [^js e]
                    (when-not (:stopped? @state)
                      (swap! state assoc :last-event (js/Date.now))
                      (on-sub-data (read (.-data e))))))
                (.addEventListener sse "sub-error"
                  (fn [^js e]
                    (when-not (:stopped? @state)
                      (swap! state assoc :last-event (js/Date.now))
                      (on-sub-error (read (.-data e))))))
                (.addEventListener sse "ping"
                  (fn [_]
                    (swap! state assoc :last-event (js/Date.now))))
                (.addEventListener sse "error"
                  (fn [_]
                    (when (and (not (:stopped? @state))
                               (= (.-readyState sse) (.-CLOSED js/EventSource)))
                      (swap! state assoc :conn-id nil)
                      (on-disconnect)))))))

          check-sse!
          (fn []
            (let [{:keys [^js sse last-event stopped?]} @state]
              (when (and sse-url (not stopped?)
                         (or (nil? sse)
                             (= (.-readyState sse) (.-CLOSED js/EventSource))
                             (> (- (js/Date.now) last-event) sse-stale-ms)))
                (when (some? (:conn-id @state))
                  (swap! state assoc :conn-id nil)
                  (on-disconnect))
                (connect-sse!))))]

      {:start!
       (fn []
         (swap! state assoc :stopped? false)
         (when sse-url
           (connect-sse!)
           (swap! state assoc :watchdog (js/setInterval check-sse! 15000)))
         (when (and (exists? js/document) (or sse-url revalidate-on-focus?))
           (let [on-vis (fn []
                          (when (= "visible" (.-visibilityState js/document))
                            (if sse-url (check-sse!) (on-revalidate))))]
             (.addEventListener js/document "visibilitychange" on-vis)
             (swap! state update :listeners conj [js/document "visibilitychange" on-vis])))
         (when (and (exists? js/window) sse-url)
           (.addEventListener js/window "online" check-sse!)
           (swap! state update :listeners conj [js/window "online" check-sse!])))

       :stop!
       (fn []
         (swap! state assoc :stopped? true :conn-id nil)
         (when-some [^js sse (:sse @state)] (.close sse))
         (when-some [t (:watchdog @state)] (js/clearInterval t))
         (doseq [[^js target ev f] (:listeners @state)]
           (.removeEventListener target ev f))
         (swap! state assoc :sse nil :watchdog nil :listeners []))

       :ready?
       (fn [] true)

       :sub-ready?
       (fn [] (boolean (and sse-url (:conn-id @state))))

       :read-batch!
       (fn [requests cb]
         (when (and sse-url (nil? (:conn-id @state)))
           (swap! state assoc :had-pre-conn-read? true))
         (post! "read" {:op ::read :requests requests} cb))

       :transact!
       (fn [{:keys [id tx params]} cb]
         (post! "transact"
           (cond-> {:op ::transact :tx tx :params params}
             (some? id) (assoc :tx-id id))
           cb))

       :forget!
       (fn [tags]
         (when (and sse-url (:conn-id @state))
           (post! "forget" {:op ::forget :tags tags} nil)))

       :subscribe!
       (fn [subs]
         (when (and sse-url (:conn-id @state))
           (post! "subscribe" {:op ::subscribe :subs subs} nil)))

       :unsubscribe!
       (fn [ids]
         (when (and sse-url (:conn-id @state))
           (post! "unsubscribe" {:op ::unsubscribe :ids ids} nil)))

       :sync-since!
       (when sse-url
         (fn [since cb]
           (post! "sync" {:op ::sync :since since} cb)))})))

(defn local-transport
  "In-process transport for tests or local/embedded backends.
   handler is (fn [msg reply-fn]) where msg has :op (::read, ::transact, ::forget, ::subscribe, ::unsubscribe, ::sync).
   Optional :on-start receives {:push! (fn [msg]) :hello! (fn [msg]) :disconnect! (fn [])}."
  [{:keys [handler on-start]}]
  {:pre [(fn? handler)]}
  (fn [rt-ref {:keys [on-hello on-stale on-push on-sub-data on-sub-error on-disconnect]}]
    (let [push! (fn [msg]
                  (case (:op msg)
                    ::hello (on-hello msg)
                    ::stale (on-stale msg)
                    ::push (on-push msg)
                    ::sub-data (on-sub-data msg)
                    ::sub-error (on-sub-error msg)
                    nil))]
      {:start!
       (fn []
         (when on-start
           (on-start {:push! push!
                      :hello! on-hello
                      :disconnect! on-disconnect})))
       :stop! (fn [])
       :ready? (fn [] true)
       :read-batch! (fn [requests cb] (handler {:op ::read :requests requests} cb))
       :transact! (fn [{:keys [id tx params]} cb]
                    (handler (cond-> {:op ::transact :tx tx :params params}
                               (some? id) (assoc :tx-id id))
                      cb))
       :forget! (fn [tags] (handler {:op ::forget :tags tags} nil))
       :subscribe! (fn [subs] (handler {:op ::subscribe :subs subs} nil))
       :unsubscribe! (fn [ids] (handler {:op ::unsubscribe :ids ids} nil))
       :sync-since! (fn [since cb] (handler {:op ::sync :since since} cb))})))

;; ---------------------------------------------------------------------------
;; Reset, Init & Stop
;; ---------------------------------------------------------------------------

(defn- on-reset [tx-env _]
  (let [state-ref (state-ref-of (::sg/runtime-ref tx-env))
        {:keys [tables]} @state-ref
        tx-env (reduce-kv
                 (fn [tx-env table _]
                   (if (contains? tx-env table)
                     (assoc tx-env table nil)
                     tx-env))
                 tx-env
                 tables)]
    (-> tx-env
        (assoc ::queries nil
               ::subs nil
               ::entity-meta nil
               ::window-meta nil)
        (sync-status-kv state-ref))))

(defn clear!
  "Clears all cached remote KV tables, query/entity/subscription metadata, pending optimistic
   state, and server cursor/epoch (e.g. on logout or tenant/account switch).
   By default restarts the transport so the server establishes a fresh connection;
   pass {:reconnect? false} to keep the current transport stopped."
  ([rt-ref]
   (clear! rt-ref nil))
  ([rt-ref {:keys [reconnect?] :or {reconnect? true}}]
   (let [rt-ref (sg/get-runtime rt-ref)
         state-ref (state-ref-of rt-ref)
         {:keys [transport]} @state-ref]
     (when-some [stop-fn (:stop! transport)]
       (stop-fn))
     (swap! state-ref assoc
       :connected? false
       :had-connected? false
       :conn-id nil
       :epoch nil
       :cursor 0
       :sync-epoch 0
       :demands {}
       :holders {}
       :tag->demands {}
       :pinned-orphans #{}
       :want-seq 0
       :wanted {}
       :in-flight 0
       :flush-queued? false
       :sub-seq 0
       :subs {}
       :sid->sub-key {}
       :sub-flush-queued? false
       :outbox []
       :sending nil
       :pending []
       :base {})
     (sg/run-tx! rt-ref {:e ::reset})
     (when (and reconnect? (:start! transport))
       ((:start! transport)))
     rt-ref)))

(defn stop!
  "Stops the remote cache on rt-ref (closes transport, clears sweep timer, unregisters demand handler)."
  [rt-ref]
  (let [rt-ref (sg/get-runtime rt-ref)]
    (when-some [state-ref (::state @rt-ref)]
      (let [{:keys [sweep-timer transport]} @state-ref]
        (when sweep-timer
          (js/clearInterval sweep-timer))
        (when-some [stop-fn (:stop! transport)]
          (stop-fn)))
      (sg/unreg-demand-handler! rt-ref ::remote)
      (swap! rt-ref dissoc ::state)))
  rt-ref)

(defn init!
  "Initializes shadow.grove.remote on rt-ref.

   opts:
   :transport          transport constructor (ws-transport, http-transport, local-transport)
   :tables             {table-kw {:primary-key :id :views {:detail {:satisfies #{:summary :default}}} :grace-ms n}}
   :grace-ms           ms an inactive demand stays cached before eviction (default 30000; 0 disables auto timer unless :sweep-interval-ms is set)
   :sub-grace-ms       ms an inactive subscription stays alive before unsubscribing (default min(2000, grace-ms))
   :sweep-interval-ms  optional eviction sweep interval in ms (defaults to clamped grace-ms/2 when grace-ms > 0)
   :max-cached         max inactive demands kept in LRU cache (default 500)
   :max-batches        max concurrent read batches in flight (default 4)
   :batch-limit        max demands per read batch (default 64)"
  [rt-ref {:keys [transport tables grace-ms sub-grace-ms sweep-interval-ms max-cached max-batches batch-limit]
           :or {grace-ms 30000 max-cached 500 max-batches 4 batch-limit 64}}]
  {:pre [(fn? transport)]}
  (let [rt-ref (sg/get-runtime rt-ref)
        sub-grace-ms (if (some? sub-grace-ms)
                       sub-grace-ms
                       (js/Math.min 2000 grace-ms))]
    ;; Clean up previous instance on hot-reload / re-init
    (when (::state @rt-ref)
      (stop! rt-ref))

    (let [tables (reduce-kv
                   (fn [m table cfg]
                     (assoc m table
                       (assoc cfg ::view-closure (compute-view-closure (:views cfg)))))
                   {}
                   (or tables {}))

          callbacks
          {:on-hello (fn [msg] (sg/run-tx! rt-ref (assoc msg :e ::hello)))
           :on-stale (fn [msg] (sg/run-tx! rt-ref (assoc msg :e ::stale)))
           :on-push (fn [msg] (sg/run-tx! rt-ref (assoc msg :e ::push)))
           :on-sub-data (fn [msg] (sg/run-tx! rt-ref (assoc msg :e ::sub-data)))
           :on-sub-error (fn [msg] (sg/run-tx! rt-ref (assoc msg :e ::sub-error)))
           :on-disconnect (fn [] (sg/run-tx! rt-ref {:e ::disconnect}))
           :on-revalidate (fn [] (revalidate-active! rt-ref))}

          transport-inst (transport rt-ref callbacks)

          state-ref
          (atom {:tables tables
                 :grace-ms grace-ms
                 :sub-grace-ms sub-grace-ms
                 :max-cached max-cached
                 :max-batches max-batches
                 :batch-limit batch-limit
                 :transport transport-inst
                 :connected? false
                 :had-connected? false
                 :conn-id nil
                 :epoch nil
                 :cursor 0
                 :sync-epoch 0
                 :demands {}
                 :holders {}
                 :tag->demands {}
                 :pinned-orphans #{}
                 :want-seq 0
                 :wanted {}
                 :in-flight 0
                 :flush-queued? false
                 :sub-seq 0
                 :subs {}
                 :sid->sub-key {}
                 :sub-flush-queued? false
                 :outbox []
                 :sending nil
                 :pending []
                 :base {}
                 :sweep-timer nil})]

      (swap! rt-ref assoc ::state state-ref)

      ;; Ensure remote entity tables exist in KV (preserving any already registered by the app)
      (doseq [[table cfg] tables]
        (when-not (contains? (::sg/kv @rt-ref) table)
          (sg/add-kv-table rt-ref table (dissoc cfg :views ::view-closure :grace-ms :max-cached) {})))

      (sg/add-kv-table rt-ref ::queries {} {})
      (sg/add-kv-table rt-ref ::subs {} {})
      (sg/add-kv-table rt-ref ::entity-meta {} {})
      (sg/add-kv-table rt-ref ::window-meta {} {})
      (sg/add-kv-table rt-ref ::status {} {:connected? false :in-flight 0 :pending 0})

      (sg/reg-demand-handler! rt-ref ::remote
        (fn [prev next-demands]
          (handle-demands rt-ref state-ref prev next-demands)))

      (sg/reg-event rt-ref ::read-done (fn [tx-env ev] (on-read-done tx-env ev)))
      (sg/reg-event rt-ref ::transact-done (fn [tx-env ev] (on-transact-done tx-env ev)))
      (sg/reg-event rt-ref ::evict (fn [tx-env ev] (on-evict tx-env ev)))
      (sg/reg-event rt-ref ::hello (fn [tx-env ev] (on-hello tx-env ev)))
      (sg/reg-event rt-ref ::stale (fn [tx-env ev] (on-stale tx-env ev)))
      (sg/reg-event rt-ref ::push (fn [tx-env ev] (on-push tx-env ev)))
      (sg/reg-event rt-ref ::sub-data (fn [tx-env ev] (on-sub-data tx-env ev)))
      (sg/reg-event rt-ref ::sub-error (fn [tx-env ev] (on-sub-error tx-env ev)))
      (sg/reg-event rt-ref ::sync-done (fn [tx-env ev] (on-sync-done tx-env ev)))
      (sg/reg-event rt-ref ::disconnect (fn [tx-env ev] (on-disconnect tx-env ev)))
      (sg/reg-event rt-ref ::mark-stale (fn [tx-env ev] (on-mark-stale-ev tx-env ev)))
      (sg/reg-event rt-ref ::reset (fn [tx-env ev] (on-reset tx-env ev)))

      (sg/reg-fx rt-ref ::send
        (fn [_ tx]
          (swap! state-ref update :outbox conj tx)
          (pump-transacts! rt-ref state-ref)))

      (sg/reg-fx rt-ref ::pump-transacts
        (fn [_ _]
          (pump-transacts! rt-ref state-ref)))

      (sg/reg-fx rt-ref ::run-tx
        (fn [{:keys [transact!]} ev]
          (transact! ev)))

      (sg/reg-fx rt-ref ::run-cb
        (fn [_ {:keys [cb arg]}]
          (if (some? arg)
            (cb arg)
            (cb))))

      (sg/reg-fx rt-ref ::sync-since
        (fn [_ since]
          (when-some [sync-fn (get-in @state-ref [:transport :sync-since!])]
            (sync-fn since
              (fn [res]
                (when (identical? state-ref (::state @rt-ref))
                  (sg/run-tx! rt-ref {:e ::sync-done :response res})))))))

      (let [base-grace (cond
                         (and (pos? grace-ms) (pos? sub-grace-ms)) (js/Math.min grace-ms sub-grace-ms)
                         (pos? grace-ms) grace-ms
                         (pos? sub-grace-ms) sub-grace-ms
                         :else 0)
            interval (cond
                       (some? sweep-interval-ms) sweep-interval-ms
                       (pos? base-grace) (js/Math.max 200 (js/Math.min 5000 (/ base-grace 2)))
                       :else nil)]
        (when (and (number? interval) (pos? interval))
          (swap! state-ref assoc :sweep-timer
            (js/setInterval #(sweep! rt-ref) interval))))

      (when-some [start-fn (:start! transport-inst)]
        (start-fn))

      rt-ref)))
