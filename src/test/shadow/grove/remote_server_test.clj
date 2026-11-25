(ns shadow.grove.remote-server-test
  (:require
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [shadow.grove.remote :as-alias r]
    [shadow.grove.remote.server :as rs]))

;; Test backend using an atom with 2 tables (:item, :owner) + a private row
(defonce test-db (atom nil))

(defn reset-db! []
  (reset! test-db
    {:item
     {1 {:item/id 1 :item/label "Alpha" :item/notes "Full alpha notes" :item/owner 10 :item/tenant :a}
      2 {:item/id 2 :item/label "Beta"  :item/notes "Full beta notes"  :item/owner 20 :item/tenant :a}
      3 {:item/id 3 :item/label "Secret" :item/notes "Private notes"   :item/owner 10 :item/tenant :b}}
     :owner
     {10 {:owner/id 10 :owner/name "Ada"}
      20 {:owner/id 20 :owner/name "Linus"}}
     :meta
     {:templates ["home.html" "about.html"]}}))

(use-fixtures :each
  (fn [f]
    (reset-db!)
    (f)))

(defn visible-item? [ctx row]
  (= (:tenant ctx :a) (:item/tenant row)))

;; Named views: :summary returns slim columns, :detail returns full row + sideloaded :owner!
(defmethod rs/entity ::item [ctx ids view]
  (let [items (:item @test-db)
        owners (:owner @test-db)
        matched (into {}
                  (keep (fn [id]
                          (when-some [row (get items id)]
                            (when (visible-item? ctx row)
                              [id (case view
                                    :summary (select-keys row [:item/id :item/label])
                                    :detail row
                                    (select-keys row [:item/id :item/label]))]))))
                  ids)]
    (if (= :detail view)
      (let [owner-ids (into #{} (keep :item/owner) (vals matched))
            sideload-owners (select-keys owners owner-ids)]
        {:entities {::item matched
                    ::owner sideload-owners}
         :views (into {}
                  (concat
                    (map (fn [id] [[::item id] #{:detail :summary :default}]) (keys matched))
                    (map (fn [oid] [[::owner oid] #{:default}]) (keys sideload-owners))))
         :tags (into #{} (map (fn [oid] [::owner oid])) owner-ids)})
      matched)))

(defmethod rs/entity ::owner [ctx ids _view]
  (select-keys (:owner @test-db) ids))

;; Non-entity projection query (returns an arbitrary Clojure value)
(defmethod rs/query ::templates [_ctx _params]
  {:value (get-in @test-db [:meta :templates])
   :tags #{::templates}})

;; Normalized paged query with inline entities (SQL style - 1 DB call)
(defmethod rs/query ::items-by-owner [ctx {:keys [owner-id page page-size] :or {page 0 page-size 10}}]
  (let [all (->> (vals (:item @test-db))
                 (filter #(and (visible-item? ctx %)
                               (or (nil? owner-id) (= owner-id (:item/owner %)))))
                 (sort-by :item/id)
                 (vec))
        offset (* page page-size)
        slice (subvec all (min offset (count all)) (min (+ offset page-size) (count all)))
        rows (into {} (map (fn [r] [(:item/id r) (select-keys r [:item/id :item/label])])) slice)]
    {:ids (mapv :item/id slice)
     :table ::item
     :view :summary
     :total (count all)
     :entities {::item rows}
     :tags (if owner-id #{[::owner-items owner-id]} #{::all-items})}))

;; Query returning only IDs (Datomic style - auto-resolved & access-filtered via rs/entity)
(defmethod rs/query ::items-ids-only [_ctx _params]
  {:ids [1 2 3 999]
   :table ::item
   :view :summary
   :total 4
   :tags #{::all-items}})

;; Small full-snapshot query (returns all entities across tables at once)
(defmethod rs/query ::snapshot [ctx _params]
  (let [items (into {} (filter #(visible-item? ctx (val %))) (:item @test-db))]
    {:value :ready
     :entities {::item items
                ::owner (:owner @test-db)}
     :tags #{::snapshot}}))

;; Mutations
(defmethod rs/transact ::rename [ctx {:keys [id label]}]
  (let [row (get-in @test-db [:item id])]
    (cond
      (or (nil? row) (not (visible-item? ctx row)))
      {:reject :not-found}

      (.startsWith ^String (str label) "!")
      {:reject "label cannot start with !"}

      :else
      (let [updated (assoc row :item/label label)]
        (swap! test-db assoc-in [:item id] updated)
        {:result {:id id :label label}
         :data {::item {id updated}}
         :views {[::item id] #{:detail :summary :default}}
         :stale #{[::owner-items (:item/owner updated)] ::all-items}}))))

(defmethod rs/transact ::delete [ctx {:keys [id]}]
  (let [row (get-in @test-db [:item id])]
    (if (or (nil? row) (not (visible-item? ctx row)))
      {:reject :not-found}
      (do (swap! test-db update :item dissoc id)
          {:retract [[::item id]]
           :stale #{[::owner-items (:item/owner row)] ::all-items}
           :push {:tags #{::snapshot}
                  :retract [[::item id]]}}))))

(defmethod rs/transact ::rename-push [ctx {:keys [id label]}]
  (let [updated (assoc (get-in @test-db [:item id]) :item/label label)]
    (swap! test-db assoc-in [:item id] updated)
    {:data {::item {id updated}}
     :push {:tags #{::snapshot}
            :data {::item {id updated}}}}))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest entity-views-sideloading-and-access-control
  (let [hub (rs/make-hub)
        sent (atom [])
        cid (rs/connect! hub #(swap! sent conj %))
        res (rs/handle-read-batch hub {:tenant :a} cid
              [{:op :entity :table ::item :view :detail :ids #{1 3 999}}])]
    (is (:ok? res))
    ;; Item 1 returned + Owner 10 sideloaded
    (is (= "Full alpha notes" (get-in res [:entities ::item 1 :item/notes])))
    (is (= "Ada" (get-in res [:entities ::owner 10 :owner/name])))
    ;; Item 3 (tenant :b) and 999 (missing) both reported as absent — indistinguishable!
    (is (= #{[::item 3] [::item 999]} (set (:absent res))))
    (is (not (contains? (get-in res [:entities ::item]) 3)))
    ;; Views recorded accurately
    (is (= #{:detail :summary :default} (get-in res [:views [::item 1]])))
    (is (= #{:default} (get-in res [:views [::owner 10]])))
    ;; Entity idents include sideloaded owner for client ref-counting
    (is (= #{[::item 1] [::owner 10]} (get-in res [:entity-idents [::item 1]])))))

(deftest query-shapes-and-auto-resolve
  (let [hub (rs/make-hub)
        res (rs/handle-read-batch hub {:tenant :a} nil
              [{:op :query :query ::templates :params nil}
               {:op :query :query ::items-by-owner :params {:owner-id 10 :page 0 :page-size 10}}
               {:op :query :query ::items-ids-only :params nil}])]
    ;; Plain value projection
    (is (= ["home.html" "about.html"] (get-in res [:queries [::templates nil] :value])))
    ;; Inline SQL-style query
    (is (= [1] (get-in res [:queries [::items-by-owner {:owner-id 10 :page 0 :page-size 10}] :ids])))
    (is (= 1 (get-in res [:queries [::items-by-owner {:owner-id 10 :page 0 :page-size 10}] :total])))
    (is (= "Alpha" (get-in res [:entities ::item 1 :item/label])))
    ;; Auto-resolved Datomic-style query strips unauthorized (3) and missing (999) IDs and adjusts :total!
    (is (= [1 2] (get-in res [:queries [::items-ids-only nil] :ids])))
    (is (= 2 (get-in res [:queries [::items-ids-only nil] :total])))
    (is (= "Beta" (get-in res [:entities ::item 2 :item/label])))))

(deftest hub-scoped-invalidation-and-forget
  (let [hub (rs/make-hub)
        sent-a (atom [])
        sent-b (atom [])
        cid-a (rs/connect! hub #(swap! sent-a conj %))
        cid-b (rs/connect! hub #(swap! sent-b conj %))]
    ;; Both get hello
    (is (= ::r/hello (:op (first @sent-a))))
    (reset! sent-a [])
    (reset! sent-b [])

    ;; Client A reads owner 10's items; Client B reads owner 20's items
    (rs/handle-read-batch hub {:tenant :a} cid-a
      [{:op :query :query ::items-by-owner :params {:owner-id 10 :page 0 :page-size 10}}])
    (rs/handle-read-batch hub {:tenant :a} cid-b
      [{:op :query :query ::items-by-owner :params {:owner-id 20 :page 0 :page-size 10}}])

    ;; Rename item 1 (owner 10) from outside (or another conn)
    (rs/handle-transact hub {:tenant :a} nil {:tx ::rename :params {:id 1 :label "Alpha 2"}})

    ;; Client A gets notified for item 1 and owner 10; Client B gets NOTHING!
    (is (= 1 (count @sent-a)))
    (is (= ::r/stale (:op (first @sent-a))))
    (is (= #{[::item 1] [::owner-items 10]} (:tags (first @sent-a))))
    (is (empty? @sent-b))

    ;; Client A forgets its tags -> no longer notified
    (reset! sent-a [])
    (rs/forget-tags! hub cid-a [[::item 1] [::owner-items 10]])
    (rs/notify! hub {:stale #{[::item 1] [::owner-items 10]}})
    (is (empty? @sent-a))

    ;; Disconnect cleans up inverted index completely
    (rs/disconnect! hub cid-a)
    (rs/disconnect! hub cid-b)
    (is (empty? (:conns @hub)))
    (is (empty? (:tag->conns @hub)))))

(deftest hub-push-data-and-transact-reject
  (let [hub (rs/make-hub)
        sent-a (atom [])
        sent-b (atom [])
        cid-a (rs/connect! hub #(swap! sent-a conj %))
        cid-b (rs/connect! hub #(swap! sent-b conj %))]
    (reset! sent-a [])
    (reset! sent-b [])

    ;; Both subscribe to ::snapshot
    (rs/handle-read-batch hub {:tenant :a} cid-a [{:op :query :query ::snapshot :params nil}])
    (rs/handle-read-batch hub {:tenant :a} cid-b [{:op :query :query ::snapshot :params nil}])

    ;; Client A runs ::rename-push -> A gets :data in transact reply (NOT echoed on socket), B gets ::r/push!
    (let [reply (rs/handle-transact hub {:tenant :a} cid-a {:tx ::rename-push :params {:id 1 :label "Pushed"}})]
      (is (:ok? reply))
      (is (= "Pushed" (get-in reply [:data ::item 1 :item/label])))
      (is (empty? @sent-a))
      (is (= 1 (count @sent-b)))
      (is (= ::r/push (:op (first @sent-b))))
      (is (= "Pushed" (get-in (first @sent-b) [:data ::item 1 :item/label]))))

    ;; Rejected transact sends no notifications
    (reset! sent-b [])
    (let [reply (rs/handle-transact hub {:tenant :a} cid-a {:tx ::rename :params {:id 1 :label "!bad"}})]
      (is (false? (:ok? reply)))
      (is (= "label cannot start with !" (:reject reply)))
      (is (empty? @sent-b)))))

(deftest reconnect-sync-since-catchup-and-resync
  (let [hub (rs/make-hub {:log-size 3})
        sent (atom [])
        cid (rs/connect! hub #(swap! sent conj %))
        {:keys [epoch cursor]} (first @sent)]
    (rs/handle-read-batch hub {:tenant :a} cid
      [{:op :entity :table ::item :view :summary :ids #{1 2}}])

    ;; Two changes happen while disconnected
    (rs/notify! hub {:stale #{[::item 1]}})
    (rs/notify! hub {:stale #{[::item 999]}}) ;; conn doesn't care about 999

    ;; Reconnect catch-up within log window returns only relevant missed tag
    (let [sync-res (rs/sync-since hub cid [epoch cursor])]
      (is (:ok? sync-res))
      (is (false? (:resync? sync-res)))
      (is (= #{[::item 1]} (:stale sync-res))))

    ;; Overflow ring buffer (log-size = 3) -> forces resync
    (dotimes [i 5]
      (rs/notify! hub {:stale #{[::item (+ 100 i)]}}))
    (let [sync-res (rs/sync-since hub cid [epoch cursor])]
      (is (:resync? sync-res)))

    ;; Different epoch (server restarted) -> forces resync
    (let [sync-res (rs/sync-since hub cid ["old-epoch" cursor])]
      (is (:resync? sync-res)))))

(deftest ring-http-and-ws-adapters
  (let [hub (rs/make-hub)
        codec {:read-str edn/read-string
               :write-str pr-str
               :content-type "application/edn; charset=utf-8"}
        ;; HTTP POST /read
        read-resp (rs/ring-http-response hub {:tenant :a} codec
                    {:body (pr-str {:requests [{:op :query :query ::templates :params nil}]})}
                    "read")
        read-body (edn/read-string (:body read-resp))]
    (is (= 200 (:status read-resp)))
    (is (= ["home.html" "about.html"] (get-in read-body [:queries [::templates nil] :value])))

    ;; HTTP POST /transact rejection -> 422
    (let [tx-resp (rs/ring-http-response hub {:tenant :a} codec
                    {:body (pr-str {:tx ::rename :params {:id 1 :label "!nope"}})}
                    "transact")]
      (is (= 422 (:status tx-resp)))
      (is (false? (:ok? (edn/read-string (:body tx-resp))))))

    ;; WebSocket listener round-trip
    (let [ws-out (atom [])
          listener (rs/ws-listener hub
                     (assoc codec
                       :send-fn (fn [_ text] (swap! ws-out conj (edn/read-string text)))
                       :ctx {:tenant :a}))]
      ((:on-open listener) :fake-socket)
      (is (= ::r/hello (:op (first @ws-out))))
      (reset! ws-out [])

      ((:on-message listener) :fake-socket
       (pr-str {:op ::r/read :call-id 7 :requests [{:op :entity :table ::item :view :summary :ids #{1}}]}))
      (is (= 1 (count @ws-out)))
      (is (= 7 (:call-id (first @ws-out))))
      (is (= "Alpha" (get-in (first @ws-out) [:entities ::item 1 :item/label])))

      ((:on-close listener) :fake-socket 1000 "bye")
      (is (empty? (:conns @hub))))))

(deftest sync-since-never-leaks-across-tenants-on-fresh-conn
  (let [hub (rs/make-hub)
        sent-a (atom [])
        sent-b (atom [])
        sent-fresh (atom [])
        cid-a (rs/connect! hub #(swap! sent-a conj %) {:topics #{::tenant-a}})
        cid-b (rs/connect! hub #(swap! sent-b conj %) {:topics #{::tenant-b}})
        start-cursor (:cursor (first @sent-a))
        epoch (:epoch (first @sent-a))]
    ;; Tenant A pushes sensitive data + stale tag
    (rs/notify! hub {:stale #{[::item 1] ::tenant-a}
                     :push {:tags #{::tenant-a}
                            :data {::item {1 {:item/id 1 :item/label "Tenant A Secret"}}}}})

    ;; Fresh reconnected socket with NO tags yet: must force :resync? true and leak NOTHING
    (let [cid-fresh (rs/connect! hub #(swap! sent-fresh conj %))
          res (rs/sync-since hub cid-fresh [epoch start-cursor])]
      (is (:resync? res))
      (is (nil? (:stale res)))
      (is (nil? (:pushes res))))

    ;; Unknown conn-id: must also force :resync? true and leak NOTHING
    (let [res (rs/sync-since hub "unknown-conn" [epoch start-cursor])]
      (is (:resync? res))
      (is (nil? (:stale res)))
      (is (nil? (:pushes res))))

    ;; Tenant B (subscribed only to ::tenant-b): sees zero Tenant A tags or pushes
    (let [res (rs/sync-since hub cid-b [epoch start-cursor])]
      (is (false? (:resync? res)))
      (is (empty? (:stale res)))
      (is (empty? (:pushes res))))

    ;; Tenant A (subscribed to ::tenant-a): receives its own push & stale tag
    (let [res (rs/sync-since hub cid-a [epoch start-cursor])]
      (is (false? (:resync? res)))
      (is (= #{::tenant-a} (:stale res)))
      (is (= 1 (count (:pushes res)))))))

(deftest handle-transact-preserves-1rtt-fast-path
  (let [hub (rs/make-hub)
        sent-a (atom [])
        sent-b (atom [])
        cid-a (rs/connect! hub #(swap! sent-a conj %))
        cid-b (rs/connect! hub #(swap! sent-b conj %))]
    (reset! sent-a [])
    (reset! sent-b [])

    ;; Both A and B subscribe to [::item 1]
    (rs/handle-read-batch hub {:tenant :a} cid-a [{:op :entity :table ::item :view :detail :ids #{1}}])
    (rs/handle-read-batch hub {:tenant :a} cid-b [{:op :entity :table ::item :view :detail :ids #{1}}])

    ;; A renames item 1; reply returns authoritative :data for [::item 1] + explicit :stale for list tags
    (let [reply (rs/handle-transact hub {:tenant :a} cid-a {:tx ::rename :params {:id 1 :label "Fast"} :tx-id 42})]
      (is (:ok? reply))
      (is (= "Fast" (get-in reply [:data ::item 1 :item/label])))
      ;; Caller's :stale must NOT echo [::item 1] back (otherwise caller would immediately refetch!)
      (is (not (contains? (:stale reply) [::item 1])))
      ;; Other client B DOES get [::item 1] invalidated via hub
      (is (= 1 (count @sent-b)))
      (is (= #{[::item 1]} (:tags (first @sent-b)))))))

(defmethod rs/entity ::tree-node [_ctx ids _view]
  ;; Self-referential sideload: child 100 references parent 200 in the same table
  {:entities {::tree-node {100 {:node/id 100 :node/parent 200}
                           200 {:node/id 200 :node/parent nil}}}})

(deftest batched-sideload-per-row-attribution-and-same-table
  (let [hub (rs/make-hub)
        res (rs/handle-read-batch hub {:tenant :a} nil
              [{:op :entity :table ::item :view :detail :ids #{1 2}}
               {:op :entity :table ::tree-node :view :default :ids #{100}}])]
    ;; Item 1 only holds owner 10; item 2 only holds owner 20 (no O(N^2) cross-attribution!)
    (is (= #{[::item 1] [::owner 10]} (get-in res [:entity-idents [::item 1]])))
    (is (= #{[::item 2] [::owner 20]} (get-in res [:entity-idents [::item 2]])))
    (is (not (contains? (get-in res [:entity-tags [::item 1]]) [::owner 20])))
    ;; Same-table sideloaded parent 200 is tracked under child 100
    (is (= #{[::tree-node 100] [::tree-node 200]} (get-in res [:entity-idents [::tree-node 100]])))))

(deftest ws-per-msg-ctx-http-owner-and-dispatch-fn
  (let [dispatched (atom 0)
        hub (rs/make-hub {:dispatch-fn (fn [f] (swap! dispatched inc) (f))})
        codec {:read-str edn/read-string
               :write-str pr-str
               :content-type "application/edn; charset=utf-8"}
        ctx-calls (atom 0)
        ws-out (atom [])
        listener (rs/ws-listener hub
                   (assoc codec
                     :send-fn (fn [_ text] (swap! ws-out conj (edn/read-string text)))
                     :ctx (fn [] (let [n (swap! ctx-calls inc)] {:tenant :a :seq n}))))]
    ;; on-open calls ctx once; each on-message gets a fresh ctx!
    ((:on-open listener) :sock)
    (is (= 1 @ctx-calls))
    (reset! ws-out [])
    ((:on-message listener) :sock (pr-str {:op ::r/read :call-id 1 :requests [{:op :query :query ::templates}]}))
    ((:on-message listener) :sock (pr-str {:op ::r/read :call-id 2 :requests [{:op :query :query ::templates}]}))
    (is (= 3 @ctx-calls))

    ;; Malformed message returns structured error instead of throwing
    (reset! ws-out [])
    ((:on-message listener) :sock "{:broken")
    (is (= ::r/error (:op (first @ws-out))))
    (is (false? (:ok? (first @ws-out))))

    ;; notify! used hub :dispatch-fn
    (rs/notify! hub {:stale #{::templates}})
    (is (= 1 @dispatched))
    ((:on-close listener) :sock 1000 "done")

    ;; HTTP malformed body -> 400
    (let [bad-resp (rs/ring-http-response hub {:tenant :a} codec {:body "{:broken"} "read")]
      (is (= 400 (:status bad-resp))))

    ;; HTTP x-grove-conn ownership check prevents spoofing another user's conn-id
    (let [sent-u1 (atom [])
          cid-u1 (rs/connect! hub #(swap! sent-u1 conj %) {:owner :user-1})]
      ;; User 2 sends User 1's conn-id -> must NOT subscribe User 1 to User 2's reads
      (rs/ring-http-response hub {:tenant :a ::rs/conn-owner :user-2} codec
        {:headers {"x-grove-conn" cid-u1}
         :body (pr-str {:requests [{:op :query :query ::templates}]})}
        "read")
      (is (empty? (get-in @hub [:conns cid-u1 :tags])))
      ;; User 1 sends its own conn-id -> subscribes normally
      (rs/ring-http-response hub {:tenant :a ::rs/conn-owner :user-1} codec
        {:headers {"x-grove-conn" cid-u1}
         :body (pr-str {:requests [{:op :query :query ::templates}]})}
        "read")
      (is (contains? (get-in @hub [:conns cid-u1 :tags]) ::templates)))))

;; ---------------------------------------------------------------------------
;; Live Subscriptions tests
;; ---------------------------------------------------------------------------

(defonce sub-state (atom {:power 100 :alert "ok" :reads 0 :poll-n 0}))

(defmethod rs/subscribe ::power-summary [_ctx _params]
  {:tags #{::power}
   :mode :interval
   :min-interval-ms 1000
   :default-interval-ms 5000
   :shared? true
   :read (fn [_ctx]
           (swap! sub-state update :reads inc)
           {:power (:power @sub-state)})})

(defmethod rs/subscribe ::alert-feed [_ctx _params]
  {:tags #{::alert}
   :mode :throttle
   :min-interval-ms 0
   :default-interval-ms 5000
   :read (fn [_ctx]
           {:alert (:alert @sub-state)})})

(defmethod rs/subscribe ::poll-counter [_ctx _params]
  {:default-interval-ms 2000
   :read (fn [_ctx]
           (:poll-n @sub-state))})

(defmethod rs/subscribe ::tx-summary [_ctx _params]
  {:tags #{::all-items}
   :mode :throttle
   :min-interval-ms 0
   :default-interval-ms 1000
   :read (fn [_ctx]
           (get-in @test-db [:item 1 :item/label]))})

(deftest subscriptions-interval-throttle-shared-and-transact
  (reset! sub-state {:power 100 :alert "ok" :reads 0 :poll-n 0})
  (let [clock (atom 10000)
        hub (rs/make-hub {:now-fn #(deref clock) :scheduler? false})
        sent-a (atom [])
        sent-b (atom [])
        cid-a (rs/connect! hub #(swap! sent-a conj %) {:ctx {:tenant :a}})
        cid-b (rs/connect! hub #(swap! sent-b conj %) {:ctx {:tenant :a}})]
    (reset! sent-a [])
    (reset! sent-b [])

    ;; Subscribe conn A to both ::power-summary (interval 10s) and ::alert-feed (throttle 5s)
    ;; Also test clamping: conn B asks for 100ms on ::power-summary -> clamped to min 1000ms
    (rs/handle-message hub {:tenant :a} cid-a
      {:op ::r/subscribe
       :subs [{:id 1 :sub ::power-summary :params nil :interval 10000}
              {:id 2 :sub ::alert-feed :params nil :interval 5000}]})
    (rs/handle-message hub {:tenant :a} cid-b
      {:op ::r/subscribe
       :subs [{:id 10 :sub ::power-summary :params nil :interval 100}]})

    ;; Initial snapshots pushed in a single batched ::r/sub-data per conn!
    (is (= 1 (count @sent-a)))
    (is (= {:op ::r/sub-data :values {1 {:power 100} 2 {:alert "ok"}}} (first @sent-a)))
    (is (= {:op ::r/sub-data :values {10 {:power 100}}} (first @sent-b)))
    (is (= 1000 (get-in @hub [:conns cid-b :subs 10 :interval-ms])))
    (reset! sent-a [])
    (reset! sent-b [])

    ;; Multiple rapid ::power updates at t=10500, t=12000:
    ;; Neither conn gets ::r/stale!
    (swap! sub-state assoc :power 200 :reads 0)
    (reset! clock 10500)
    (rs/notify! hub {:stale #{::power}})
    (rs/flush-subscriptions! hub)
    (is (empty? @sent-a))
    (is (empty? @sent-b))

    ;; At t=11000: conn B (clamped 1000ms) flushes, conn A (10000ms) still waits!
    (reset! clock 11000)
    (rs/flush-subscriptions! hub)
    (is (empty? @sent-a))
    (is (= [{:op ::r/sub-data :values {10 {:power 200}}}] @sent-b))
    (reset! sent-b [])

    ;; At t=20000: both conn A and conn B are due if power changes again -> :shared? true computes :read ONCE!
    (swap! sub-state assoc :power 300 :reads 0)
    (reset! clock 19500)
    (rs/notify! hub {:stale #{::power}})
    (reset! clock 20000)
    (rs/flush-subscriptions! hub)
    (is (= 1 (:reads @sub-state)))
    (is (= [{:op ::r/sub-data :values {1 {:power 300}}}] @sent-a))
    (is (= [{:op ::r/sub-data :values {10 {:power 300}}}] @sent-b))
    (reset! sent-a [])
    (reset! sent-b [])

    ;; Value equality suppression: notify without actual value change sends nothing!
    (reset! clock 25000)
    (rs/notify! hub {:stale #{::power}})
    (reset! clock 30000)
    (rs/flush-subscriptions! hub)
    (is (empty? @sent-a))
    (is (empty? @sent-b))

    ;; :mode :interval after quiet period stays aligned (last next-at was 30000; dirty at 45000 waits until 50000)
    (swap! sub-state assoc :power 400)
    (reset! clock 45000)
    (rs/notify! hub {:stale #{::power}})
    (rs/flush-subscriptions! hub)
    (is (empty? @sent-a))
    (reset! clock 50000)
    (rs/flush-subscriptions! hub)
    (is (= [{:op ::r/sub-data :values {1 {:power 400}}}] @sent-a))
    (reset! sent-a [])

    ;; :mode :throttle (leading + trailing): quiet since t=10000 (next-at=15000), dirty at t=50000 -> fires ASAP!
    (swap! sub-state assoc :alert "door-open")
    (rs/notify! hub {:stale #{::alert}})
    (rs/flush-subscriptions! hub)
    (is (= [{:op ::r/sub-data :values {2 {:alert "door-open"}}}] @sent-a))
    (reset! sent-a [])

    ;; Burst during cooldown (t=51000, t=52000) -> waits until t=55000 (trailing edge)!
    (reset! clock 51000)
    (swap! sub-state assoc :alert "window-1")
    (rs/notify! hub {:stale #{::alert}})
    (rs/flush-subscriptions! hub)
    (is (empty? @sent-a))
    (reset! clock 52000)
    (swap! sub-state assoc :alert "window-2")
    (rs/notify! hub {:stale #{::alert}})
    (rs/flush-subscriptions! hub)
    (is (empty? @sent-a))
    (reset! clock 55000)
    (rs/flush-subscriptions! hub)
    (is (= [{:op ::r/sub-data :values {2 {:alert "window-2"}}}] @sent-a))
    (reset! sent-a [])

    ;; Idempotent interval update: relax conn A sub 1 to 2000ms without re-sending unchanged value
    (rs/handle-message hub {:tenant :a} cid-a
      {:op ::r/subscribe
       :subs [{:id 1 :sub ::power-summary :params nil :interval 2000}]})
    (is (empty? @sent-a))
    (is (= 2000 (get-in @hub [:conns cid-a :subs 1 :interval-ms])))

    ;; handle-transact with :except cid-a STILL marks cid-a's subscription dirty!
    (rs/handle-message hub {:tenant :a} cid-a
      {:op ::r/subscribe
       :subs [{:id 3 :sub ::tx-summary :params nil :interval 0}]})
    (is (= [{:op ::r/sub-data :values {3 "Alpha"}}] @sent-a))
    (reset! sent-a [])
    (reset! clock 56000)
    (let [reply (rs/handle-transact hub {:tenant :a} cid-a {:tx ::rename :params {:id 1 :label "Alpha New"}})]
      (is (:ok? reply)))
    (rs/flush-subscriptions! hub)
    (is (= [{:op ::r/sub-data :values {3 "Alpha New"}}] @sent-a))
    (reset! sent-a [])

    ;; Unsubscribe & disconnect clean up inverted index completely
    (rs/handle-message hub {:tenant :a} cid-a {:op ::r/unsubscribe :ids [1 2 3]})
    (is (empty? (get-in @hub [:conns cid-a :subs])))
    (rs/disconnect! hub cid-a)
    (rs/disconnect! hub cid-b)
    (is (empty? (:conns @hub)))
    (is (empty? (:sub-tag->conns @hub)))))

(deftest subscriptions-polling-custom-source-errors-sse-and-scheduler
  (reset! sub-state {:power 100 :alert "ok" :reads 0 :poll-n 1})
  (let [clock (atom 1000)
        hub (rs/make-hub {:now-fn #(deref clock) :scheduler? false})
        stopped? (atom false)
        emitter (atom nil)
        sent (atom [])]
    (defmethod rs/subscribe ::custom-stream [_ctx _params]
      {:mode :throttle
       :min-interval-ms 0
       :default-interval-ms 1000
       :start (fn [emit!]
                (reset! emitter emit!)
                (fn [] (reset! stopped? true)))})

    (let [cid (rs/connect! hub #(swap! sent conj %) {:ctx {:tenant :a}})]
      (reset! sent [])
      ;; Subscribe to polling sub, custom stream, and unknown sub in one batch
      (rs/handle-message hub {:tenant :a} cid
        {:op ::r/subscribe
         :subs [{:id 1 :sub ::poll-counter :params nil :interval 2000}
                {:id 2 :sub ::custom-stream :params nil :interval 1000}
                {:id 3 :sub ::no-such-sub :params nil}]})
      ;; Initial value for poll-counter + error for unknown sub
      (is (= {:op ::r/sub-data :values {1 1}} (first @sent)))
      (is (= ::r/sub-error (:op (second @sent))))
      (is (contains? (:errors (second @sent)) 3))
      (reset! sent [])

      ;; Poll at t=3000 with unchanged value -> nothing sent
      (reset! clock 3000)
      (rs/flush-subscriptions! hub)
      (is (empty? @sent))

      ;; Poll at t=5000 with changed value -> pushed!
      (swap! sub-state assoc :poll-n 2)
      (reset! clock 5000)
      (rs/flush-subscriptions! hub)
      (is (= [{:op ::r/sub-data :values {1 2}}] @sent))
      (reset! sent [])

      ;; Custom push source emits twice within cooldown -> throttled to 1 leading + 1 trailing
      (@emitter :ev-1)
      (rs/flush-subscriptions! hub)
      (is (= [{:op ::r/sub-data :values {2 :ev-1}}] @sent))
      (reset! sent [])
      (reset! clock 5200)
      (@emitter :ev-2)
      (@emitter :ev-3)
      (rs/flush-subscriptions! hub)
      (is (empty? @sent))
      (reset! clock 6000)
      (rs/flush-subscriptions! hub)
      (is (= [{:op ::r/sub-data :values {2 :ev-3}}] @sent))

      ;; Disconnect stops custom source!
      (rs/disconnect! hub cid)
      (is (true? @stopped?)))

    ;; HTTP + SSE adapter test
    (let [sse-events (atom [])
          codec {:read-str edn/read-string :write-str pr-str :content-type "application/edn; charset=utf-8"}
          cid (rs/sse-connect! hub
                {:write-str pr-str
                 :write-event! (fn [ev data] (swap! sse-events conj [ev (edn/read-string data)]))
                 :ctx {:tenant :a}})]
      (reset! sse-events [])
      (let [resp (rs/ring-http-response hub {:tenant :a} codec
                   {:headers {"x-grove-conn" cid}
                    :body (pr-str {:subs [{:id 1 :sub ::poll-counter :params nil :interval 1000}]})}
                   "subscribe")]
        (is (= 204 (:status resp)))
        (is (= [["sub-data" {:op ::r/sub-data :values {1 2}}]] @sse-events)))
      (let [resp (rs/ring-http-response hub {:tenant :a} codec
                   {:headers {"x-grove-conn" cid}
                    :body (pr-str {:ids [1]})}
                   "unsubscribe")]
        (is (= 204 (:status resp))))
      (rs/disconnect! hub cid)))

  ;; Real background scheduler test (async push on daemon ScheduledExecutorService)
  (reset! sub-state {:power 10 :alert "ok" :reads 0 :poll-n 0})
  (let [hub (rs/make-hub)
        p (promise)
        cid (rs/connect! hub
              (fn [msg]
                (when (and (= ::r/sub-data (:op msg))
                           (= {:power 99} (get-in msg [:values 1])))
                  (deliver p msg)))
              {:ctx {:tenant :a}})]
    (try
      (rs/handle-message hub {:tenant :a} cid
        {:op ::r/subscribe
         :subs [{:id 1 :sub ::alert-feed :params nil :interval 25}]})
      ;; Also test real scheduled flush with 30ms interval
      (let [p2 (promise)]
        (defmethod rs/subscribe ::fast-tick [_ctx _params]
          {:tags #{::fast}
           :mode :interval
           :min-interval-ms 20
           :default-interval-ms 25
           :read (fn [_ctx] {:power (:power @sub-state)})})
        (let [cid2 (rs/connect! hub
                     (fn [msg]
                       (when (and (= ::r/sub-data (:op msg))
                                  (= {:power 99} (get-in msg [:values 7])))
                         (deliver p2 msg)))
                     {:ctx {:tenant :a}})]
          (rs/handle-message hub {:tenant :a} cid2
            {:op ::r/subscribe
             :subs [{:id 7 :sub ::fast-tick :params nil :interval 25}]})
          (swap! sub-state assoc :power 99)
          (rs/notify! hub {:stale #{::fast}})
          (is (= {:op ::r/sub-data :values {7 {:power 99}}} (deref p2 1000 :timeout)))))
      (finally
        (rs/stop-hub! hub)))))
