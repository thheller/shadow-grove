(ns shadow.grove.remote.server
  "Database-agnostic Clojure server half of shadow.grove.remote.

   The app implements three multimethods over whatever database it uses (Datomic,
   PostgreSQL, in-memory atom, CMS, etc.):

   ;; ids is a set, view is a keyword (default :default).
   ;; Returns {id row-map} (missing/unauthorized ids omitted), OR
   ;; {:entities {table {id row-map} ...} :views {...} :tags #{...}} to sideload joins.
   (defmethod rs/entity :item [ctx ids view] ...)

   ;; params is arbitrary Clojure data (filters, :page, :page-size, :cursor, ...).
   ;; Returns one of:
   ;;   {:value x :tags #{...}}
   ;;   {:ids [...] :table :item :total n :entities {:item {...}} :tags #{...}}
   ;;   {:ids [...] :table :item :next-cursor c :more? true :entities {...} :tags #{...}}
   ;;   {:value :ready :entities {:t1 {...} :t2 {...}} :tags #{...}}
   (defmethod rs/query :items/all [ctx params] ...)

   ;; Returns {:result x :data {table {id row}} :retract [[table id]] :stale #{...}}
   ;; or {:reject reason}. Optional :push {:tags #{...} :data {...} :retract [...]}
   ;; pushes authoritative row updates directly to subscribed clients.
   (defmethod rs/transact :item/rename [ctx params] ...)

   ;; Optional live server-pushed subscription:
   ;; Returns {:read (fn [ctx] val) :tags #{...} :mode :throttle|:interval
   ;;          :min-interval-ms n :default-interval-ms n :shared? bool}
   ;; or {:start (fn [emit!] stop-fn) ...}.
   (defmethod rs/subscribe :home/summary [ctx params] ...)

   Zero external dependencies: callers pass :read-str and :write-str (Transit or EDN)
   and wire into Ring HTTP, SSE, or WebSocket as needed."
  (:require
    [shadow.grove.remote :as-alias r])
  (:import
    [java.util.concurrent Executors ScheduledExecutorService ScheduledFuture ThreadFactory TimeUnit]))

;; ---------------------------------------------------------------------------
;; Multimethods
;; ---------------------------------------------------------------------------

(defmulti entity
  "Batch lookup of entities in (::table ctx) for a set of ids and a named view."
  (fn [ctx ids view] (::table ctx)))

(defmethod entity :default [ctx ids view]
  (throw (ex-info "unknown entity table" {:table (::table ctx) :view view})))

(defmulti query
  "Named query dispatched on (::query ctx) with arbitrary params map."
  (fn [ctx params] (::query ctx)))

(defmethod query :default [ctx params]
  (throw (ex-info "unknown query" {:query (::query ctx)})))

(defmulti transact
  "Mutation dispatched on (::tx ctx) with arbitrary params map."
  (fn [ctx params] (::tx ctx)))

(defmethod transact :default [ctx params]
  (throw (ex-info "unknown transact" {:tx (::tx ctx)})))

(defmulti subscribe
  "Named live subscription dispatched on (::sub ctx) with arbitrary params map.

   Returns a descriptor map:
   :read                (fn [ctx]) -> value  (snapshot producer)
   :tags                optional set/seq of tags whose notify! marks this subscription dirty
   :poll?               optional boolean; defaults to true when :tags is empty and :read is given
   :mode                :throttle (leading+trailing, default) or :interval (strict periodic)
   :min-interval-ms     minimum allowed interval in ms (default 0)
   :max-interval-ms     optional maximum allowed interval in ms
   :default-interval-ms default interval in ms when client omits :interval (default 5000)
   :shared?             if true, compute :read once per [sub params] per flush tick across connections
   :start               optional (fn [emit!]) -> stop-fn for custom push-native sources

   Or returns {:error err} to reject the subscription."
  (fn [ctx params] (::sub ctx)))

(defmethod subscribe :default [ctx _params]
  (throw (ex-info "unknown subscription" {:sub (::sub ctx)})))

;; ---------------------------------------------------------------------------
;; Hub: inverted-index pub/sub + cursor ring buffer + subscriptions
;; ---------------------------------------------------------------------------

(def default-log-size 2048)

(def ^:private unset ::unset)

(declare schedule-next-tick! flush-subscriptions! mark-subs-dirty! missed-tags-since)

(defn make-hub
  "Creates a hub tracking live connections, their subscribed tags (inverted index),
   a ring buffer of recent changes for reconnect catch-up, and live push subscriptions.

   opts:
   :log-size     max entries kept in the reconnect ring buffer (default 2048)
   :dispatch-fn  optional (fn [thunk]) used to offload notify! sends off the caller thread
   :now-fn       optional (fn []) -> epoch ms (default #(System/currentTimeMillis); useful for tests)
   :scheduler?   whether to run the background subscription scheduler (default true)"
  ([]
   (make-hub {}))
  ([{:keys [log-size dispatch-fn now-fn scheduler?]
     :or {log-size default-log-size
          now-fn #(System/currentTimeMillis)
          scheduler? true}}]
   (atom {:epoch (str (random-uuid))
          :cursor 0
          :log-size log-size
          :dispatch-fn dispatch-fn
          :now-fn now-fn
          :scheduler? (boolean scheduler?)
          :sched-lock (Object.)
          :executor nil
          :scheduled-task nil
          :scheduled-at nil
          :stopped? false
          ;; vector of {:cursor n :stale #{...} :push {:tags #{...} :data ... :retract ...}}
          :log []
          ;; conn-id -> {:send fn :ctx-fn fn :tags #{...} :fixed-tags #{...} :owner any :subs {sid sub-entry}}
          :conns {}
          ;; tag -> #{conn-id ...}
          :tag->conns {}
          ;; tag -> #{[conn-id sid] ...}
          :sub-tag->conns {}})))

(defn- add-conn-tags [state conn-id tags]
  (if (or (empty? tags) (not (contains? (:conns state) conn-id)))
    state
    (let [existing (get-in state [:conns conn-id :tags] #{})
          added (into [] (remove existing) tags)]
      (if (empty? added)
        state
        (-> state
            (update-in [:conns conn-id :tags] into added)
            (update :tag->conns
              (fn [idx]
                (reduce
                  (fn [idx tag]
                    (update idx tag (fnil conj #{}) conn-id))
                  idx
                  added))))))))

(defn- remove-conn-tags [state conn-id tags]
  (if (or (empty? tags) (not (contains? (:conns state) conn-id)))
    state
    (let [fixed (get-in state [:conns conn-id :fixed-tags] #{})
          existing (get-in state [:conns conn-id :tags] #{})
          removable (into [] (filter #(and (contains? existing %) (not (contains? fixed %)))) tags)]
      (if (empty? removable)
        state
        (-> state
            (update-in [:conns conn-id :tags] #(reduce disj % removable))
            (update :tag->conns
              (fn [idx]
                (reduce
                  (fn [idx tag]
                    (let [left (disj (get idx tag) conn-id)]
                      (if (empty? left)
                        (dissoc idx tag)
                        (assoc idx tag left))))
                  idx
                  removable))))))))

(defn- remove-sub-tags [sub-tag->conns conn-id sid tags]
  (reduce
    (fn [idx tag]
      (let [left (disj (get idx tag) [conn-id sid])]
        (if (empty? left)
          (dissoc idx tag)
          (assoc idx tag left))))
    sub-tag->conns
    tags))

(defn- add-sub-tags [sub-tag->conns conn-id sid tags]
  (reduce
    (fn [idx tag]
      (update idx tag (fnil conj #{}) [conn-id sid]))
    sub-tag->conns
    tags))

(defn- safe-stop-fn! [stop-fn]
  (when (fn? stop-fn)
    (try
      (stop-fn)
      (catch Throwable _
        nil))))

(defn connect!
  "Registers a connection with send-fn (called with Clojure message maps).
   Optional :topics (set/seq of tags) are permanently subscribed for the lifetime
   of this connection (e.g. user/workspace channels for SSE).
   Optional :owner binds this conn-id to an owner value (matched against ::conn-owner in ctx
   on HTTP requests to prevent x-grove-conn spoofing).
   Optional :ctx (map or (fn [] ctx)) provides ctx when evaluating live subscriptions on tick.
   Sends ::r/hello and returns conn-id."
  ([hub send-fn]
   (connect! hub send-fn nil))
  ([hub send-fn {:keys [topics conn-id owner ctx]}]
   (let [conn-id (or conn-id (str (random-uuid)))
         fixed (set topics)
         ctx-fn (cond
                  (fn? ctx) ctx
                  (some? ctx) (constantly ctx)
                  :else nil)
         state (swap! hub
                 (fn [state]
                   (-> state
                       (assoc-in [:conns conn-id]
                         (cond-> {:send send-fn
                                  :tags #{}
                                  :fixed-tags fixed
                                  :subs {}}
                           (some? owner) (assoc :owner owner)
                           (some? ctx-fn) (assoc :ctx-fn ctx-fn)))
                       (add-conn-tags conn-id fixed))))]
     (send-fn {:op ::r/hello
               :conn-id conn-id
               :epoch (:epoch state)
               :cursor (:cursor state)})
     conn-id)))

(defn disconnect!
  "Removes conn-id from the hub and cleans up all its inverted-index entries and subscriptions."
  [hub conn-id]
  (let [stop-fns (volatile! [])]
    (swap! hub
      (fn [state]
        (if-some [{:keys [tags subs]} (get-in state [:conns conn-id])]
          (do
            (vreset! stop-fns (into [] (keep :stop-fn) (vals subs)))
            (-> state
                (update :tag->conns
                  (fn [idx]
                    (reduce
                      (fn [idx tag]
                        (let [left (disj (get idx tag) conn-id)]
                          (if (empty? left)
                            (dissoc idx tag)
                            (assoc idx tag left))))
                      idx
                      tags)))
                (update :sub-tag->conns
                  (fn [idx]
                    (reduce-kv
                      (fn [idx sid {:keys [tags]}]
                        (remove-sub-tags idx conn-id sid tags))
                      idx
                      subs)))
                (update :conns dissoc conn-id)))
          state)))
    (doseq [f @stop-fns]
      (safe-stop-fn! f))
    (schedule-next-tick! hub))
  nil)

(defn stop-hub!
  "Stops the hub's subscription scheduler (if started) and cleans up all active subscriptions."
  [hub]
  (let [stop-fns (volatile! [])
        {:keys [sched-lock]} @hub]
    (locking sched-lock
      (let [prev (swap! hub
                   (fn [{:keys [conns] :as state}]
                     (vreset! stop-fns
                       (into []
                         (for [[_ {:keys [subs]}] conns
                               sub (vals subs)
                               :let [f (:stop-fn sub)]
                               :when (fn? f)]
                           f)))
                     (assoc state
                       :stopped? true
                       :conns {}
                       :tag->conns {}
                       :sub-tag->conns {}
                       :scheduled-task nil
                       :scheduled-at nil
                       :executor nil)))]
        (when-some [^ScheduledFuture task (:scheduled-task prev)]
          (.cancel task false))
        (when-some [^ScheduledExecutorService exec (:executor prev)]
          (.shutdownNow exec))))
    (doseq [f @stop-fns]
      (safe-stop-fn! f))
    nil))

(defn subscribe-tags!
  "Server-side only: subscribes conn-id to authorized tags."
  [hub conn-id tags]
  (when (and hub conn-id (seq tags))
    (swap! hub add-conn-tags conn-id tags))
  nil)

(defn forget-tags!
  "Removes tags from conn-id's subscription set (fixed :topics remain subscribed)."
  [hub conn-id tags]
  (when (and hub conn-id (seq tags))
    (swap! hub remove-conn-tags conn-id tags))
  nil)

(defn- conn-targets
  "Returns {conn-id #{matched-tags}} using the inverted index: O(affected subscribers)."
  [tag->conns tags except]
  (reduce
    (fn [acc tag]
      (reduce
        (fn [acc conn-id]
          (if (= conn-id except)
            acc
            (update acc conn-id (fnil conj #{}) tag)))
        acc
        (get tag->conns tag)))
    {}
    tags))

(defn- safe-send! [hub dispatch-fn conn-id send-fn msg]
  (let [send! (fn []
                (try
                  (send-fn msg)
                  (catch Exception _
                    ;; connection died; remove so future notifies don't keep failing
                    (disconnect! hub conn-id))))]
    (if dispatch-fn
      (dispatch-fn send!)
      (send!))))

(defn notify!
  "Broadcasts changes to subscribed connections via the hub.

   opts:
   :stale   - set/seq of tags/idents that became stale (push-invalidation)
   :push    - optional {:tags #{...} :data {table {id row}} :views {...} :retract [[table id]] :stale #{...}}
              for pushing authoritative row deltas directly to subscribers of :tags
   :except  - optional conn-id to skip (usually the caller of a transact)

   Can be called from rs/transact, Datomic tx-report-queue, Postgres LISTEN/NOTIFY,
   or any background job."
  [hub {:keys [stale push except]}]
  (let [stale-set (into #{} stale)
        push-tags (into #{} (:tags push))
        push-data? (and (some? push)
                        (or (seq (:data push))
                            (seq (:retract push))
                            (seq (:stale push))))]
    (when (or (seq stale-set) (and push-data? (seq push-tags)))
      (let [state (swap! hub
                    (fn [{:keys [cursor log log-size] :as state}]
                      (let [next-cursor (inc cursor)
                            entry (cond-> {:cursor next-cursor}
                                    (seq stale-set) (assoc :stale stale-set)
                                    push-data? (assoc :push (assoc push :tags push-tags)))
                            log (conj log entry)
                            log (if (> (count log) log-size)
                                  (subvec log (- (count log) log-size))
                                  log)]
                        (assoc state :cursor next-cursor :log log))))
            {:keys [epoch cursor conns tag->conns dispatch-fn]} state]

        ;; 1. Push-data subscribers (if any)
        (let [push-targets (when (and push-data? (seq push-tags))
                             (conn-targets tag->conns push-tags except))
              push-conns (set (keys push-targets))]

          (doseq [[conn-id matched] push-targets
                  :let [send-fn (get-in conns [conn-id :send])]
                  :when send-fn]
            (safe-send! hub dispatch-fn conn-id send-fn
              (cond-> {:op ::r/push
                       :epoch epoch
                       :cursor cursor
                       :tags matched}
                (seq (:data push)) (assoc :data (:data push))
                (seq (:views push)) (assoc :views (:views push))
                (seq (:retract push)) (assoc :retract (:retract push))
                (seq (:stale push)) (assoc :stale (:stale push)))))

          ;; 2. Stale-only subscribers (skipping tags/idents already delivered via :push)
          (when (seq stale-set)
            (let [pushed-covered (when push-data?
                                   (into (set (:stale push))
                                     (concat
                                       (for [[t rows] (:data push)
                                             id (keys rows)]
                                         [t id])
                                       (:retract push))))
                  stale-targets (conn-targets tag->conns stale-set except)]
              (doseq [[conn-id matched] stale-targets
                      :let [remaining (if (contains? push-conns conn-id)
                                        (into #{} (remove pushed-covered) matched)
                                        matched)]
                      :when (seq remaining)
                      :let [send-fn (get-in conns [conn-id :send])]
                      :when send-fn]
                (safe-send! hub dispatch-fn conn-id send-fn
                  {:op ::r/stale
                   :epoch epoch
                   :cursor cursor
                   :tags remaining})))))

        ;; 3. Mark matching live subscriptions dirty (includes mutating conn-id too,
        ;;    since transact replies do not deliver subscription snapshots).
        (let [sub-tags (cond-> stale-set
                         push-data? (into (concat
                                            push-tags
                                            (:stale push)
                                            (:retract push)
                                            (for [[t rows] (:data push)
                                                  id (keys rows)]
                                              [t id]))))]
          (when (seq sub-tags)
            (mark-subs-dirty! hub sub-tags)))))))

(defn sync-since
  "Reconnect catch-up: given [req-epoch req-cursor], returns missed stale tags
   (and pushes) if the ring buffer still covers req-cursor and conn-id has
   authorized subscriptions, or {:resync? true}."
  [hub conn-id [req-epoch req-cursor]]
  (let [{:keys [epoch cursor log conns]} @hub
        conn (get conns conn-id)
        conn-tags (:tags conn)
        oldest-cursor (if (seq log) (:cursor (first log)) (inc cursor))]
    (cond
      (not= req-epoch epoch)
      {:op ::r/sync-result :ok? true :epoch epoch :cursor cursor :resync? true}

      ;; Unknown conn or fresh connection with no subscribed tags:
      ;; NEVER return unfiltered hub entries (would leak cross-tenant tags/pushes).
      ;; Force resync so the client re-reads active demands and re-establishes authorized tags.
      (or (nil? conn) (empty? conn-tags))
      {:op ::r/sync-result :ok? true :epoch epoch :cursor cursor :resync? true}

      (= req-cursor cursor)
      {:op ::r/sync-result :ok? true :epoch epoch :cursor cursor :resync? false :stale #{} :pushes []}

      ;; req-cursor fell off the ring buffer (we need entry (inc req-cursor) to still be present)
      (< (inc (long (or req-cursor 0))) oldest-cursor)
      {:op ::r/sync-result :ok? true :epoch epoch :cursor cursor :resync? true}

      :else
      (let [missed (filter #(> (:cursor %) req-cursor) log)
            stale (into #{}
                    (comp
                      (mapcat :stale)
                      (filter conn-tags))
                    missed)
            pushes (into []
                     (comp
                       (keep :push)
                       (filter (fn [{:keys [tags]}]
                                 (some conn-tags tags))))
                     missed)]
        {:op ::r/sync-result
         :ok? true
         :epoch epoch
         :cursor cursor
         :resync? false
         :stale stale
         :pushes pushes}))))

;; ---------------------------------------------------------------------------
;; Read batch execution
;; ---------------------------------------------------------------------------

(defn- merge-entities [a b]
  (reduce-kv
    (fn [acc table rows]
      (update acc table (fn [prev] (merge-with merge prev rows))))
    a
    b))

(defn- merge-views [a b]
  (reduce-kv
    (fn [acc ident view-set]
      (update acc ident (fnil into #{}) view-set))
    a
    b))

(defn- rich-entity-result? [res ids]
  (and (map? res)
       (contains? res :entities)
       (map? (:entities res))
       (not (contains? ids :entities))))

(defn- mark-table-views [views table rows view]
  (reduce-kv
    (fn [acc id _]
      (update acc [table id] (fnil conj #{}) view))
    views
    rows))

(defn- mark-all-entities-views [views entities primary-table primary-view explicit-views]
  (let [views (reduce-kv
                (fn [acc table rows]
                  (let [v (if (= table primary-table) primary-view :default)]
                    (mark-table-views acc table rows v)))
                views
                entities)]
    (if (seq explicit-views)
      (merge-views views explicit-views)
      views)))

(defn- row-ref-values
  "Collects scalar values (and elements of sequential/set values) from a row map
   to match sideloaded entity IDs back to their owning primary row."
  [row]
  (if-not (map? row)
    #{}
    (reduce-kv
      (fn [acc _ v]
        (cond
          (or (sequential? v) (set? v)) (into acc v)
          (some? v) (conj acc v)
          :else acc))
      #{}
      row)))

(defn- attribute-sideloads
  "Attributes sideloaded entity idents to the primary rows that reference them,
   avoiding O(N^2) cross-attribution in batched entity reads while supporting
   same-table sideloads. Any sideloaded ident not matched to a specific row falls
   back to all primary rows so nothing is orphaned in client KV."
  [table primary-rows primary-id-set sideloaded]
  (let [primary-keys (keys primary-rows)]
    (cond
      (empty? sideloaded)
      (into {} (map (fn [id] [[table id] #{[table id]}])) primary-keys)

      (== 1 (count primary-rows))
      (let [id (first primary-keys)]
        {[table id] (into #{[table id]} sideloaded)})

      :else
      (let [row-vals (into {} (map (fn [[id row]] [id (row-ref-values row)])) primary-rows)
            ;; Map each sideloaded [st sid] to the primary ids referencing sid
            [matched unmatched]
            (reduce
              (fn [[m u] [_ sid :as sident]]
                (let [owners (into []
                               (keep (fn [[pid rvals]]
                                       (when (contains? rvals sid) pid)))
                               row-vals)]
                  (if (seq owners)
                    [(reduce (fn [m pid] (update m pid (fnil conj #{}) sident)) m owners) u]
                    [m (conj u sident)])))
              [{} #{}]
              sideloaded)]
        (into {}
          (map (fn [id]
                 [[table id]
                  (-> #{[table id]}
                      (into (get matched id))
                      (into unmatched))]))
          primary-keys)))))

(defn- run-entity-req [ctx {:keys [table view ids]}]
  (let [view (or view :default)
        id-set (set ids)
        raw (entity (assoc ctx ::table table) id-set view)
        rich? (rich-entity-result? raw id-set)
        entities (if rich? (:entities raw) {table (or raw {})})
        primary-rows (get entities table {})
        absent (into [] (comp (remove #(contains? primary-rows %)) (map (fn [id] [table id]))) id-set)
        views (mark-all-entities-views {} entities table view (:views raw))
        ;; Tags: every returned entity [t id] plus any explicit :tags from the handler
        entity-idents (for [[t rows] entities
                            id (keys rows)]
                        [t id])
        entity-ident-set (set entity-idents)
        tags (into entity-ident-set (:tags raw))
        ;; Sideloaded includes both other tables AND same-table rows not directly requested
        sideloaded (into #{}
                     (remove (fn [[t id]] (and (= table t) (contains? id-set id))))
                     entity-ident-set)
        requested-primary-rows (select-keys primary-rows id-set)
        entity-idents-map (or (when rich? (:entity-idents raw))
                              (attribute-sideloads table requested-primary-rows id-set sideloaded))
        extra-tags (set (:tags raw))
        ;; Separate extra tags that are specific entity idents in this batch vs general tags
        general-tags (into #{} (remove entity-ident-set) extra-tags)
        entity-tags (or (when rich? (:entity-tags raw))
                        (into {}
                          (map (fn [[t id :as ident]]
                                 (let [own-idents (get entity-idents-map ident #{ident})
                                       own-extra (into general-tags
                                                   (filter own-idents)
                                                   extra-tags)]
                                   [ident (into own-extra own-idents)])))
                          entity-ident-set))]
    {:entities entities
     :views views
     :absent absent
     :tags tags
     :entity-idents entity-idents-map
     :entity-tags entity-tags}))

(defn- run-query-req [ctx {:keys [query params] :as req}]
  (let [res (shadow.grove.remote.server/query (assoc ctx ::query query) params)]
    (if (contains? res :error)
      {:query-entry (cond-> {:ok? false :error (:error res)}
                      (seq (:tags res)) (assoc :tags (set (:tags res))))
       :entities {}
       :views {}
       :tags (set (:tags res))}
      (let [{:keys [table ids view entities tags auto-resolve?]} res
            view (or view :default)
            entities (or entities {})
            ;; If a query returned :ids + :table without inline entities, resolve via rs/entity
            ;; and strip any IDs rs/entity didn't return (enforcing entity-level access control)
            [entities final-ids resolved-views resolved-tags dropped-count]
            (if (and table (sequential? ids) (not (false? auto-resolve?)))
              (let [existing (get entities table {})
                    missing (into #{} (remove #(contains? existing %)) ids)]
                (if (seq missing)
                  (let [fetched (run-entity-req ctx {:table table :view view :ids missing})
                        merged (merge-entities entities (:entities fetched))
                        visible (get merged table {})
                        filtered (filterv #(contains? visible %) ids)]
                    [merged
                     filtered
                     (:views fetched)
                     (:tags fetched)
                     (- (count ids) (count filtered))])
                  [entities
                   (vec ids)
                   {}
                   #{}
                   0]))
              [entities ids {} #{} 0])

            ;; Mark views for inline entities provided by the query
            views (-> (mark-all-entities-views {} entities table view (:views res))
                      (merge-views resolved-views))

            ;; Query tags: explicit :tags (or default :table if normalized list) + returned entity idents
            query-tags (into #{}
                         (concat
                           (or tags (when table #{table}))
                           resolved-tags))
            entity-idents (for [[t rows] entities
                                id (keys rows)]
                            [t id])
            all-tags (into query-tags entity-idents)

            entry (-> res
                      (dissoc :entities :views :auto-resolve?)
                      (assoc :ok? true :tags query-tags)
                      (cond->
                        (some? final-ids) (assoc :ids final-ids)
                        (and (pos? dropped-count) (number? (:total res)))
                        (assoc :total (max (count final-ids) (- (:total res) dropped-count)))
                        (seq entity-idents) (assoc :idents (set entity-idents))))]
        {:query-entry entry
         :entities entities
         :views views
         :tags all-tags}))))

(defn- log-entry-tags [{:keys [stale push]}]
  (cond-> (or stale #{})
    push (into (concat
                 (:tags push)
                 (:stale push)
                 (:retract push)
                 (for [[t rows] (:data push)
                       id (keys rows)]
                   [t id])))))

(defn- missed-tags-since [hub read-cursor tags]
  (let [{:keys [cursor log]} @hub]
    (cond
      (= read-cursor cursor) #{}
      (empty? tags) #{}
      :else
      (let [oldest (if (seq log) (:cursor (first log)) (inc cursor))]
        (if (< (inc (long read-cursor)) oldest)
          (set tags)
          (into #{}
            (comp
              (filter #(> (:cursor %) read-cursor))
              (mapcat log-entry-tags)
              (filter tags))
            log))))))

(defn handle-read-batch
  "Executes a batch of entity/query read requests under ctx.
   Records authorized tags on conn-id in hub (if provided) and reports any
   tags that changed while the batch was running."
  [hub ctx conn-id requests]
  (let [read-cursor (if hub (:cursor @hub) 0)
        acc (reduce
              (fn [acc {:keys [op] :as req}]
                (case op
                  :entity
                  (try
                    (let [r (run-entity-req ctx req)]
                      (-> acc
                          (update :entities merge-entities (:entities r))
                          (update :views merge-views (:views r))
                          (update :absent into (:absent r))
                          (update :tags into (:tags r))
                          (update :entity-idents #(merge-with into % (:entity-idents r)))
                          (update :entity-tags #(merge-with into % (:entity-tags r)))))
                    (catch Exception e
                      (let [err {:message (or (.getMessage e) "entity read failed")
                                 :data (ex-data e)}
                            view (or (:view req) :default)]
                        (reduce
                          (fn [acc id]
                            (assoc-in acc [:errors [:entity (:table req) id view]] err))
                          acc
                          (:ids req)))))

                  :query
                  (let [qkey [(:query req) (:params req)]]
                    (try
                      (let [r (run-query-req ctx req)]
                        (-> acc
                            (assoc-in [:queries qkey] (:query-entry r))
                            (update :entities merge-entities (:entities r))
                            (update :views merge-views (:views r))
                            (update :tags into (:tags r))))
                      (catch Exception e
                        (assoc-in acc [:queries qkey]
                          {:ok? false
                           :error {:message (or (.getMessage e) "query failed")
                                   :data (ex-data e)}}))))

                  acc))
              {:entities {}
               :views {}
               :absent []
               :queries {}
               :errors {}
               :tags #{}
               :entity-idents {}
               :entity-tags {}}
              requests)]

    (when (and hub conn-id (seq (:tags acc)))
      (subscribe-tags! hub conn-id (:tags acc)))

    (let [hub-state (when hub @hub)
          stale (if hub
                  (missed-tags-since hub read-cursor (:tags acc))
                  #{})]
      (cond-> {:op ::r/read-result
               :ok? true
               :entities (:entities acc)
               :views (:views acc)
               :absent (:absent acc)
               :queries (:queries acc)}
        hub-state (assoc :epoch (:epoch hub-state) :cursor (:cursor hub-state))
        (seq (:errors acc)) (assoc :errors (:errors acc))
        (seq (:entity-idents acc)) (assoc :entity-idents (:entity-idents acc))
        (seq (:entity-tags acc)) (assoc :entity-tags (:entity-tags acc))
        (seq stale) (assoc :stale stale)))))

;; ---------------------------------------------------------------------------
;; Transact execution
;; ---------------------------------------------------------------------------

(defn handle-transact
  "Executes a single transact under ctx, notifies other connections via hub,
   and returns the response map for the caller."
  [hub ctx conn-id {:keys [tx params tx-id]}]
  (try
    (let [tx-ctx (cond-> (assoc ctx ::tx tx)
                   (some? tx-id) (assoc ::tx-id tx-id))
          res (transact tx-ctx params)]
      (if (contains? res :reject)
        {:op ::r/transact-result
         :ok? false
         :reject (:reject res)}
        (let [{:keys [result data views retract stale push]} res
              ;; Automatically derive entity idents from :data and :retract for hub broadcast
              data-idents (for [[t rows] data
                                id (keys rows)]
                            [t id])
              explicit-stale (set stale)
              broadcast-stale (into explicit-stale (concat data-idents retract))
              ;; Views for :data default to :default unless specified in :views
              data-views (when (seq data)
                           (mark-all-entities-views {} data nil :default views))]

          (when (and hub (or (seq broadcast-stale) (some? push)))
            (notify! hub {:stale broadcast-stale :push push :except conn-id}))

          (let [hub-state (when hub @hub)
                ;; Only return explicit :stale tags to the caller (never echo back :data/:retract
                ;; idents that the caller just received authoritatively in this reply, preserving
                ;; the 1-RTT fast path).
                conn-tags (when (and hub conn-id)
                            (get-in hub-state [:conns conn-id :tags]))
                caller-stale (if (seq conn-tags)
                               (into #{} (filter conn-tags) explicit-stale)
                               explicit-stale)]
            (cond-> {:op ::r/transact-result
                     :ok? true}
              hub-state (assoc :epoch (:epoch hub-state) :cursor (:cursor hub-state))
              (contains? res :result) (assoc :result result)
              (seq data) (assoc :data data)
              (seq data-views) (assoc :views data-views)
              (seq retract) (assoc :retract (vec retract))
              (seq caller-stale) (assoc :stale caller-stale))))))
    (catch Exception e
      {:op ::r/transact-result
       :ok? false
       :reject {:message (or (.getMessage e) "transact failed")
                :data (ex-data e)}})))

;; ---------------------------------------------------------------------------
;; Live Subscriptions: dirty-marking, scheduling, flushing, subscribe/unsubscribe
;; ---------------------------------------------------------------------------

(defn- clamp-interval [requested min-ms max-ms default-ms]
  (let [raw (if (and (number? requested) (>= (double requested) 0.0))
              (long requested)
              (long (or default-ms 5000)))
        lo (long (max 0 (long (or min-ms 0))))
        hi (when (number? max-ms) (long (max lo (long max-ms))))
        v (max lo raw)]
    (if hi (min hi v) v)))

(defn- next-due-at
  "Computes the next flush timestamp when a subscription becomes dirty at `now`."
  [mode now next-at interval-ms]
  (let [now (long now)
        next-at (long next-at)
        interval-ms (long interval-ms)]
    (if (and (= :interval mode) (pos? interval-ms))
      (if (< now next-at)
        next-at
        (+ next-at (* (inc (quot (- now next-at) interval-ms)) interval-ms)))
      ;; :throttle (leading+trailing) or interval-ms == 0
      (if (>= now next-at)
        now
        next-at))))

(defn- earliest-due-at [state]
  (reduce-kv
    (fn [best _ {:keys [subs]}]
      (reduce-kv
        (fn [best _ {:keys [dirty? poll? next-at]}]
          (if (or dirty? poll?)
            (let [t (long next-at)]
              (if (or (nil? best) (< t (long best))) t best))
            best))
        best
        subs))
    nil
    (:conns state)))

(defn- ensure-executor! ^ScheduledExecutorService [hub]
  (or (:executor @hub)
      (let [tf (reify ThreadFactory
                 (newThread [_ r]
                   (doto (Thread. r "shadow-grove-remote-hub")
                     (.setDaemon true))))
            exec (Executors/newSingleThreadScheduledExecutor tf)]
        (swap! hub assoc :executor exec)
        exec)))

(defn- schedule-next-tick! [hub]
  (let [{:keys [scheduler? stopped? sched-lock]} @hub]
    (when (and scheduler? (not stopped?))
      (locking sched-lock
        (let [{:keys [stopped? scheduled-task scheduled-at now-fn] :as state} @hub]
          (when-not stopped?
            (let [earliest (earliest-due-at state)]
              (cond
                (nil? earliest)
                (when scheduled-task
                  (.cancel ^ScheduledFuture scheduled-task false)
                  (swap! hub assoc :scheduled-task nil :scheduled-at nil))

                (and scheduled-task
                     (not (.isDone ^ScheduledFuture scheduled-task))
                     (some? scheduled-at)
                     (<= (long scheduled-at) (long earliest)))
                nil

                :else
                (do
                  (when scheduled-task
                    (.cancel ^ScheduledFuture scheduled-task false))
                  (let [exec (ensure-executor! hub)
                        delay-ms (max 0 (- (long earliest) (long (now-fn))))
                        task-box (volatile! nil)
                        ^Runnable task-fn
                        (fn []
                          (locking sched-lock
                            (when (identical? (:scheduled-task @hub) @task-box)
                              (swap! hub assoc :scheduled-task nil :scheduled-at nil)))
                          (try
                            (flush-subscriptions! hub)
                            (catch Throwable _
                              nil)))
                        fut (.schedule exec task-fn (long delay-ms) TimeUnit/MILLISECONDS)]
                    (vreset! task-box fut)
                    (swap! hub assoc :scheduled-task fut :scheduled-at earliest))))))))))
  nil)

(defn- mark-subs-dirty! [hub tags]
  (let [state @hub
        sub-tag->conns (:sub-tag->conns state)
        targets (reduce
                  (fn [acc tag]
                    (if-some [entries (get sub-tag->conns tag)]
                      (into acc entries)
                      acc))
                  #{}
                  tags)]
    (when (seq targets)
      (let [now (long ((:now-fn state)))]
        (swap! hub
          (fn [state]
            (reduce
              (fn [state [conn-id sid]]
                (if-some [{:keys [mode next-at interval-ms]} (get-in state [:conns conn-id :subs sid])]
                  (let [due (next-due-at mode now next-at interval-ms)]
                    (update-in state [:conns conn-id :subs sid] assoc
                      :dirty? true
                      :next-at due))
                  state))
              state
              targets)))
        (schedule-next-tick! hub))))
  nil)

(defn- emit-sub-value! [hub conn-id sid gen val]
  (let [now (long ((:now-fn @hub)))
        updated? (volatile! false)]
    (swap! hub
      (fn [state]
        (if-some [{:keys [mode next-at interval-ms] sub-gen :gen} (get-in state [:conns conn-id :subs sid])]
          (if (identical? gen sub-gen)
            (do
              (vreset! updated? true)
              (update-in state [:conns conn-id :subs sid] assoc
                :dirty? true
                :has-pending? true
                :pending-val val
                :next-at (next-due-at mode now next-at interval-ms)))
            state)
          state)))
    (when @updated?
      (schedule-next-tick! hub)))
  nil)

(defn- eval-sub-value [shared-cache {:keys [sub params read-fn shared? ctx-fn has-pending? pending-val]}]
  (if has-pending?
    {:ok? true :val pending-val}
    (let [cache-key (when shared? [sub params])]
      (if (and cache-key (contains? @shared-cache cache-key))
        (get @shared-cache cache-key)
        (let [res (try
                    (let [ctx (if (fn? ctx-fn) (ctx-fn) {})]
                      {:ok? true :val (read-fn (assoc ctx ::sub sub))})
                    (catch Exception e
                      {:ok? false
                       :error {:message (or (.getMessage e) "subscription read failed")
                               :data (ex-data e)}}))]
          (when cache-key
            (vswap! shared-cache assoc cache-key res))
          res)))))

(defn flush-subscriptions!
  "Evaluates all due dirty or polling subscriptions in the hub, suppresses unchanged values,
   and pushes batched ::r/sub-data / ::r/sub-error messages per connection.
   Called automatically by the hub scheduler; can also be invoked directly in tests."
  [hub]
  (let [now (long ((:now-fn @hub)))
        claimed (volatile! [])
        state (swap! hub
                (fn [state]
                  (let [items (transient [])
                        next-conns
                        (reduce-kv
                          (fn [conns conn-id {:keys [send ctx-fn subs] :as conn}]
                            (if (empty? subs)
                              conns
                              (let [changed? (volatile! false)
                                    next-subs
                                    (reduce-kv
                                      (fn [subs sid {:keys [dirty? poll? next-at interval-ms sub params
                                                            read-fn shared? last-val gen
                                                            has-pending? pending-val fallback-ctx-fn]
                                                     :as entry}]
                                        (if (and (or dirty? poll?)
                                                 (<= (long next-at) now))
                                          (do
                                            (vreset! changed? true)
                                            (conj! items
                                              {:conn-id conn-id
                                               :sid sid
                                               :gen gen
                                               :send send
                                               :sub sub
                                               :params params
                                               :read-fn read-fn
                                               :shared? shared?
                                               :ctx-fn (or ctx-fn fallback-ctx-fn)
                                               :last-val last-val
                                               :has-pending? has-pending?
                                               :pending-val pending-val})
                                            (assoc subs sid
                                              (assoc entry
                                                :dirty? false
                                                :has-pending? false
                                                :pending-val nil
                                                :next-at (+ now (long interval-ms)))))
                                          subs))
                                      subs
                                      subs)]
                                (if @changed?
                                  (assoc conns conn-id (assoc conn :subs next-subs))
                                  conns))))
                          (:conns state)
                          (:conns state))]
                    (vreset! claimed (persistent! items))
                    (assoc state :conns next-conns))))
        {:keys [dispatch-fn]} state
        due @claimed]

    (when (seq due)
      (let [shared-cache (volatile! {})
            per-conn-values (volatile! {})
            per-conn-errors (volatile! {})
            last-val-updates (volatile! [])
            send-fns (volatile! {})]

        (doseq [{:keys [conn-id sid gen send last-val read-fn has-pending?] :as item} due
                :when (or has-pending? (fn? read-fn))]
          (let [{:keys [ok? val error]} (eval-sub-value shared-cache item)]
            (if ok?
              (when (or (identical? unset last-val) (not= last-val val))
                (vswap! last-val-updates conj [conn-id sid gen val])
                (vswap! per-conn-values assoc-in [conn-id sid] val)
                (vswap! send-fns assoc conn-id send))
              (do
                (vswap! per-conn-errors assoc-in [conn-id sid] error)
                (vswap! send-fns assoc conn-id send)))))

        (when (seq @last-val-updates)
          (swap! hub
            (fn [state]
              (reduce
                (fn [state [conn-id sid gen val]]
                  (if (identical? gen (get-in state [:conns conn-id :subs sid :gen]))
                    (update-in state [:conns conn-id :subs sid] assoc
                      :last-val val
                      :last-sent-at now)
                    state))
                state
                @last-val-updates))))

        (doseq [[conn-id values] @per-conn-values
                :let [send-fn (get @send-fns conn-id)]
                :when send-fn]
          (safe-send! hub dispatch-fn conn-id send-fn
            {:op ::r/sub-data
             :values values}))

        (doseq [[conn-id errors] @per-conn-errors
                :let [send-fn (get @send-fns conn-id)]
                :when send-fn]
          (safe-send! hub dispatch-fn conn-id send-fn
            {:op ::r/sub-error
             :errors errors}))))

    (schedule-next-tick! hub)
    nil))

(defn handle-subscribe
  "Registers or updates live subscriptions for conn-id and pushes initial snapshots."
  [hub ctx conn-id subs]
  (when (and hub conn-id (seq subs) (contains? (:conns @hub) conn-id))
    (let [now (long ((:now-fn @hub)))
          shared-cache (volatile! {})
          init-values (volatile! {})
          init-errors (volatile! {})
          old-stop-fns (volatile! [])
          starts-to-run (volatile! [])]

      (doseq [{:keys [id sub params interval mode]} subs
              :when (some? id)]
        (let [existing (get-in @hub [:conns conn-id :subs id])]
          (if (and existing
                   (= sub (:sub existing))
                   (= params (:params existing)))
            ;; Idempotent update (e.g. client merged/relaxed interval or mode)
            (let [{:keys [min-interval-ms max-interval-ms default-interval-ms spec-mode]} existing
                  next-interval (clamp-interval interval min-interval-ms max-interval-ms default-interval-ms)
                  next-mode (or mode spec-mode :throttle)]
              (swap! hub
                (fn [state]
                  (if-some [{:keys [dirty? poll? last-sent-at next-at] :as cur}
                            (get-in state [:conns conn-id :subs id])]
                    (let [base-due (+ (long (or last-sent-at now)) (long next-interval))
                          adjusted-due (cond
                                         (and dirty? (= :throttle next-mode) (>= now base-due)) now
                                         (or dirty? poll?) (min (long next-at) base-due)
                                         :else base-due)]
                      (assoc-in state [:conns conn-id :subs id]
                        (assoc cur
                          :interval-ms next-interval
                          :mode next-mode
                          :next-at adjusted-due)))
                    state))))

            ;; New subscription (or replacing a different sub under the same id)
            (let [read-cursor (:cursor @hub)
                  spec-res (try
                             (let [res (subscribe (assoc ctx ::sub sub) params)]
                               (if (and (map? res) (contains? res :error))
                                 {:ok? false :error (:error res)}
                                 {:ok? true :spec res}))
                             (catch Exception e
                               {:ok? false
                                :error {:message (or (.getMessage e) "subscribe failed")
                                        :data (ex-data e)}}))]
              (if-not (:ok? spec-res)
                (vswap! init-errors assoc id (:error spec-res))
                (let [{:keys [read start tags poll? min-interval-ms max-interval-ms
                              default-interval-ms shared?]
                       spec-mode :mode} (:spec spec-res)
                      tag-set (into #{} tags)
                      poll? (if (some? poll?)
                              (boolean poll?)
                              (and (empty? tag-set) (fn? read)))
                      eff-mode (or mode spec-mode :throttle)
                      eff-interval (clamp-interval interval min-interval-ms max-interval-ms default-interval-ms)
                      gen (Object.)
                      fallback-ctx-fn (constantly ctx)
                      init-read (when (fn? read)
                                  (eval-sub-value shared-cache
                                    {:sub sub
                                     :params params
                                     :read-fn read
                                     :shared? shared?
                                     :ctx-fn fallback-ctx-fn}))]
                  (if (and init-read (not (:ok? init-read)))
                    (vswap! init-errors assoc id (:error init-read))
                    (let [has-init? (some? init-read)
                          init-val (if has-init? (:val init-read) unset)
                          missed (when (seq tag-set)
                                   (missed-tags-since hub read-cursor tag-set))
                          dirty? (boolean (seq missed))
                          next-at (if dirty?
                                    (next-due-at eff-mode now (+ now eff-interval) eff-interval)
                                    (+ now eff-interval))
                          entry {:id id
                                 :gen gen
                                 :sub sub
                                 :params params
                                 :read-fn read
                                 :tags tag-set
                                 :poll? poll?
                                 :mode eff-mode
                                 :spec-mode spec-mode
                                 :min-interval-ms min-interval-ms
                                 :max-interval-ms max-interval-ms
                                 :default-interval-ms default-interval-ms
                                 :interval-ms eff-interval
                                 :shared? (boolean shared?)
                                 :fallback-ctx-fn fallback-ctx-fn
                                 :dirty? dirty?
                                 :has-pending? false
                                 :pending-val nil
                                 :next-at next-at
                                 :last-sent-at now
                                 :last-val init-val
                                 :stop-fn nil}
                          installed? (volatile! false)]
                      (swap! hub
                        (fn [state]
                          (if-not (contains? (:conns state) conn-id)
                            state
                            (let [prev (get-in state [:conns conn-id :subs id])]
                              (when-some [f (:stop-fn prev)]
                                (vswap! old-stop-fns conj f))
                              (vreset! installed? true)
                              (-> state
                                  (cond-> prev
                                    (update :sub-tag->conns remove-sub-tags conn-id id (:tags prev)))
                                  (assoc-in [:conns conn-id :subs id] entry)
                                  (update :sub-tag->conns add-sub-tags conn-id id tag-set))))))
                      (when @installed?
                        (when has-init?
                          (vswap! init-values assoc id init-val))
                        (when (fn? start)
                          (vswap! starts-to-run conj [id gen start])))))))))))

      (doseq [f @old-stop-fns]
        (safe-stop-fn! f))

      (let [conn-send (get-in @hub [:conns conn-id :send])]
        (when conn-send
          (when (seq @init-values)
            (safe-send! hub nil conn-id conn-send
              {:op ::r/sub-data
               :values @init-values}))
          (when (seq @init-errors)
            (safe-send! hub nil conn-id conn-send
              {:op ::r/sub-error
               :errors @init-errors}))))

      (doseq [[sid gen start-fn] @starts-to-run]
        (let [emit! (fn [val] (emit-sub-value! hub conn-id sid gen val))
              stop-fn (try
                        (start-fn emit!)
                        (catch Exception e
                          (when-some [conn-send (get-in @hub [:conns conn-id :send])]
                            (safe-send! hub nil conn-id conn-send
                              {:op ::r/sub-error
                               :errors {sid {:message (or (.getMessage e) "subscription start failed")
                                             :data (ex-data e)}}}))
                          nil))]
          (when (fn? stop-fn)
            (let [attached? (volatile! false)]
              (swap! hub
                (fn [state]
                  (if (identical? gen (get-in state [:conns conn-id :subs sid :gen]))
                    (do
                      (vreset! attached? true)
                      (assoc-in state [:conns conn-id :subs sid :stop-fn] stop-fn))
                    state)))
              (when-not @attached?
                (safe-stop-fn! stop-fn))))))

      (schedule-next-tick! hub)))
  nil)

(defn handle-unsubscribe
  "Removes live subscriptions by client-assigned ids from conn-id."
  [hub conn-id ids]
  (when (and hub conn-id (seq ids))
    (let [stop-fns (volatile! [])]
      (swap! hub
        (fn [state]
          (if-not (contains? (:conns state) conn-id)
            state
            (reduce
              (fn [state sid]
                (if-some [{:keys [tags stop-fn]} (get-in state [:conns conn-id :subs sid])]
                  (do
                    (when (fn? stop-fn)
                      (vswap! stop-fns conj stop-fn))
                    (-> state
                        (update-in [:conns conn-id :subs] dissoc sid)
                        (update :sub-tag->conns remove-sub-tags conn-id sid tags)))
                  state))
              state
              ids))))
      (doseq [f @stop-fns]
        (safe-stop-fn! f))
      (schedule-next-tick! hub)))
  nil)

;; ---------------------------------------------------------------------------
;; Transport Adapters: WebSocket + HTTP POST + SSE
;; ---------------------------------------------------------------------------

(defn handle-message
  "Dispatches a single wire message (used by both WebSocket and single-endpoint HTTP).
   Returns the reply map, or nil for fire-and-forget ops like ::r/forget, ::r/subscribe, ::r/unsubscribe."
  [hub ctx conn-id {:keys [op call-id] :as msg}]
  (let [reply
        (case op
          ::r/read
          (handle-read-batch hub ctx conn-id (:requests msg))

          ::r/transact
          (handle-transact hub ctx conn-id msg)

          ::r/forget
          (do (forget-tags! hub conn-id (:tags msg))
              nil)

          ::r/subscribe
          (handle-subscribe hub ctx conn-id (:subs msg))

          ::r/unsubscribe
          (handle-unsubscribe hub conn-id (:ids msg))

          ::r/sync
          (if hub
            (sync-since hub conn-id (:since msg))
            {:op ::r/sync-result :ok? true :resync? true})

          {:op ::r/error :ok? false :error {:message (str "unknown op " op)}})]
    (when reply
      (cond-> reply
        (some? call-id) (assoc :call-id call-id)))))

(defn ws-listener
  "Returns a Ring WebSocket listener map ({:on-open :on-message :on-close}).

   opts:
   :read-str  - (fn [text]) -> clj map (Transit or EDN)
   :write-str - (fn [clj-map]) -> text
   :send-fn   - (fn [socket text]) e.g. ring.websocket/send
   :ctx       - (fn []) -> fresh ctx map per message (recommended for DB snapshots / auth),
                or a static ctx map
   :topics    - optional initial fixed topics (set/seq or (fn [ctx] #{...}))
   :owner     - optional owner value (or (fn [ctx] ...)) bound to this connection"
  [hub {:keys [read-str write-str send-fn ctx topics owner]}]
  (let [conn-id-ref (volatile! nil)
        get-ctx (if (fn? ctx) ctx (constantly (or ctx {})))]
    {:on-open
     (fn [socket]
       (let [open-ctx (get-ctx)
             topic-set (if (fn? topics) (topics open-ctx) topics)
             owner-val (if (fn? owner) (owner open-ctx) (or owner (::conn-owner open-ctx)))
             send-msg! (fn [msg] (send-fn socket (write-str msg)))
             conn-id (connect! hub send-msg! {:topics topic-set :owner owner-val :ctx get-ctx})]
         (vreset! conn-id-ref conn-id)
         nil))

     :on-message
     (fn [socket text]
       (let [conn-id @conn-id-ref
             reply
             (try
               (let [msg (read-str text)]
                 (if (map? msg)
                   (handle-message hub (get-ctx) conn-id msg)
                   {:op ::r/error :ok? false :error {:message "invalid message payload"}}))
               (catch Exception e
                 {:op ::r/error
                  :ok? false
                  :error {:message (or (.getMessage e) "failed to process message")}}))]
         (when reply
           (send-fn socket (write-str reply)))))

     :on-close
     (fn [socket code reason]
       (when-some [conn-id @conn-id-ref]
         (disconnect! hub conn-id)))}))

(defn- read-body-str [req]
  (let [body (:body req)]
    (cond
      (nil? body) ""
      (string? body) body
      :else (slurp body))))

(defn- authorized-conn-id
  "Validates x-grove-conn against the hub connection's :owner (if set)."
  [hub ctx conn-id]
  (when (and hub conn-id)
    (when-some [conn (get-in @hub [:conns conn-id])]
      (let [conn-owner (:owner conn)]
        (when (or (nil? conn-owner) (= conn-owner (::conn-owner ctx)))
          conn-id)))))

(defn ring-http-response
  "Handles a Ring HTTP POST request for shadow.grove.remote.
   Supports either a single endpoint (body has :op) or subpath routing
   (subpath \"read\", \"transact\", \"forget\", \"subscribe\", \"unsubscribe\", \"sync\").

   opts:
   :read-str     - (fn [string]) -> clj map
   :write-str    - (fn [clj-map]) -> string
   :content-type - response content-type (default \"application/transit+json; charset=utf-8\")"
  ([hub ctx opts req]
   (ring-http-response hub ctx opts req nil))
  ([hub ctx {:keys [read-str write-str content-type]
             :or {content-type "application/transit+json; charset=utf-8"}}
    req subpath]
   (let [parsed (try
                  (let [v (read-str (read-body-str req))]
                    (if (map? v)
                      {:ok? true :body v}
                      {:ok? false :message "request body must be a map"}))
                  (catch Exception e
                    {:ok? false :message (or (.getMessage e) "invalid request body")}))]
     (if-not (:ok? parsed)
       {:status 400
        :headers {"content-type" content-type
                  "cache-control" "no-store"}
        :body (write-str {:op ::r/error :ok? false :error {:message (:message parsed)}})}
       (let [body (:body parsed)
             raw-conn-id (get-in req [:headers "x-grove-conn"])
             conn-id (authorized-conn-id hub ctx raw-conn-id)
             msg (if (:op body)
                   body
                   (assoc body :op
                     (case subpath
                       "read" ::r/read
                       "transact" ::r/transact
                       "forget" ::r/forget
                       "subscribe" ::r/subscribe
                       "unsubscribe" ::r/unsubscribe
                       "sync" ::r/sync
                       nil)))
             reply (handle-message hub ctx conn-id msg)]
         (if (nil? reply)
           {:status 204
            :headers {"cache-control" "no-store"}}
           {:status (if (and (= ::r/transact-result (:op reply)) (false? (:ok? reply))) 422 200)
            :headers {"content-type" content-type
                      "cache-control" "no-store"}
            :body (write-str reply)}))))))

(defn sse-connect!
  "Connects an SSE stream to the hub.
   write-event! is (fn [event-name data-str]) provided by the server (e.g. Ring/Jetty/shadow.http).
   Returns conn-id (pass to disconnect! in a finally block when the stream closes)."
  [hub {:keys [write-str write-event! topics conn-id owner ctx]}]
  (connect! hub
    (fn [msg]
      (let [ev-name (case (:op msg)
                      ::r/hello "hello"
                      ::r/stale "stale"
                      ::r/push "push"
                      ::r/sub-data "sub-data"
                      ::r/sub-error "sub-error"
                      "message")]
        (write-event! ev-name (write-str msg))))
    {:topics topics
     :conn-id conn-id
     :owner owner
     :ctx ctx}))
