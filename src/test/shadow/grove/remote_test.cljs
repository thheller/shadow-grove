(ns shadow.grove.remote-test
  (:require
    ["jsdom" :refer (JSDOM)]
    [cljs.reader :as reader]
    [cljs.test :as ct :refer (deftest is async)]
    [shadow.grove :as sg :refer (defc deftx <<)]
    [shadow.grove.edn :as edn]
    [shadow.grove.impl :as impl]
    [shadow.grove.remote :as remote]))

;; ---------------------------------------------------------------------------
;; Test harness
;; ---------------------------------------------------------------------------

(defn make-dom []
  (let [dom (JSDOM. "<!DOCTYPE html>")
        doc (-> dom .-window .-document)]
    (set! js/global -document doc)
    (.-body doc)))

(defn wait
  ([] (wait 15))
  ([ms] (js/Promise. (fn [resolve] (js/setTimeout resolve ms)))))

(defn run-steps [done steps]
  (-> (reduce
        (fn [p step]
          (.then p (fn [] (step) (wait))))
        (js/Promise.resolve nil)
        steps)
      (.catch (fn [e]
                (js/console.error e)
                (is (nil? e) "step threw")))
      (.then done)))

;; Mini in-memory fake server implementing the exact remote wire protocol
(defn make-fake-server []
  (let [db (atom {:item {1 {:item/id 1 :item/label "Alpha" :item/notes "Alpha detail" :item/owner 10}
                         2 {:item/id 2 :item/label "Beta"  :item/notes "Beta detail"  :item/owner 20}
                         3 {:item/id 3 :item/label "Gamma" :item/notes "Gamma detail" :item/owner 10}}
                  :owner {10 {:owner/id 10 :owner/name "Ada"}
                          20 {:owner/id 20 :owner/name "Linus"}}
                  :templates ["home.html" "blog.html"]})
        log (atom [])
        cursor (atom 1)
        epoch "test-epoch"
        push-ref (atom nil)
        defer-tx? (atom false)
        deferred-txs (atom [])]

    {:db db
     :log log
     :cursor cursor
     :push-ref push-ref
     :defer-tx? defer-tx?
     :deferred-txs deferred-txs
     :flush-tx!
     (fn []
       (let [fns @deferred-txs]
         (reset! deferred-txs [])
         (doseq [f fns] (f))))
     :transport
     (remote/local-transport
       {:on-start
        (fn [hooks]
          (reset! push-ref hooks)
          ((:hello! hooks) {:op ::remote/hello :conn-id "local-1" :epoch epoch :cursor @cursor}))
        :handler
        (fn [{:keys [op] :as msg} reply]
          (swap! log conj msg)
          (case op
            ::remote/read
            (let [res (reduce
                        (fn [acc {:keys [op table view ids query params]}]
                          (case op
                            :entity
                            (let [rows (get @db table {})
                                  found (into {}
                                          (keep (fn [id]
                                                  (when-some [row (get rows id)]
                                                    [id (if (= :summary view)
                                                          (select-keys row [:item/id :item/label])
                                                          row)])))
                                          ids)
                                  absent (into [] (comp (remove #(contains? found %)) (map #(vector table %))) ids)
                                  sideload (if (and (= :item table) (= :detail view))
                                             (select-keys (:owner @db) (keep :item/owner (vals found)))
                                             {})
                                  eidents (into {}
                                            (map (fn [[id row]]
                                                   [[table id]
                                                    (cond-> #{[table id]}
                                                      (and (= :detail view) (:item/owner row))
                                                      (conj [:owner (:item/owner row)]))]))
                                            found)]
                              (-> acc
                                  (update-in [:entities table] merge found)
                                  (cond-> (seq sideload)
                                    (update-in [:entities :owner] merge sideload))
                                  (update :views merge
                                    (into {} (map (fn [id] [[table id] #{view}])) (keys found))
                                    (into {} (map (fn [oid] [[:owner oid] #{:default}])) (keys sideload)))
                                  (update :absent into absent)
                                  (update :entity-idents merge eidents)))

                            :query
                            (case query
                              :templates
                              (assoc-in acc [:queries [query params]]
                                {:ok? true :value (:templates @db) :tags #{:templates}})

                              :items/page
                              (let [{:keys [page page-size] :or {page 0 page-size 2}} params
                                    all (->> (vals (:item @db)) (sort-by :item/id) (vec))
                                    offset (* page page-size)
                                    slice (subvec all (min offset (count all)) (min (+ offset page-size) (count all)))
                                    rows (into {} (map (fn [r] [(:item/id r) (select-keys r [:item/id :item/label])])) slice)]
                                (-> acc
                                    (update-in [:entities :item] merge rows)
                                    (update :views merge (into {} (map (fn [id] [[:item id] #{:summary}])) (keys rows)))
                                    (assoc-in [:queries [query params]]
                                      {:ok? true
                                       :table :item
                                       :ids (mapv :item/id slice)
                                       :total (count all)
                                       :idents (into #{} (map #(vector :item (:item/id %))) slice)
                                       :tags #{:items/all}})))

                              :items/fail
                              (assoc-in acc [:queries [query params]]
                                {:ok? false :error {:message "boom"}})

                              acc)))
                        {:op ::remote/read-result
                         :ok? true
                         :epoch epoch
                         :cursor @cursor
                         :entities {}
                         :views {}
                         :absent []
                         :queries {}
                         :entity-idents {}}
                        (:requests msg))]
              (reply res))

            ::remote/transact
            (let [{:keys [tx params]} msg
                  run!
                  (fn []
                    (case tx
                      :item/rename
                      (let [{:keys [id label]} params]
                        (if (= "!" (subs (str label) 0 1))
                          (reply {:op ::remote/transact-result :ok? false :reject "invalid label"})
                          (let [updated (assoc (get-in @db [:item id]) :item/label label)]
                            (swap! db assoc-in [:item id] updated)
                            (swap! cursor inc)
                            (reply {:op ::remote/transact-result
                                    :ok? true
                                    :epoch epoch
                                    :cursor @cursor
                                    :result {:renamed id}
                                    :data {:item {id updated}}
                                    :views {[:item id] #{:detail}}
                                    :stale #{:items/all}}))))

                      :item/rename-stale-only
                      (let [{:keys [id label]} params]
                        (swap! db assoc-in [:item id :item/label] label)
                        (swap! cursor inc)
                        (reply {:op ::remote/transact-result
                                :ok? true
                                :epoch epoch
                                :cursor @cursor
                                :stale #{[:item id]}}))

                      :item/delete
                      (let [{:keys [id]} params]
                        (swap! db update :item dissoc id)
                        (swap! cursor inc)
                        (reply {:op ::remote/transact-result
                                :ok? true
                                :epoch epoch
                                :cursor @cursor
                                :retract [[:item id]]
                                :stale #{:items/all}}))))]
              (if @defer-tx?
                (swap! deferred-txs conj run!)
                (run!)))

            ::remote/forget
            nil

            ::remote/sync
            (let [[req-epoch req-cursor] (:since msg)]
              (if (and (= req-epoch epoch) (= req-cursor @cursor))
                (reply {:op ::remote/sync-result :ok? true :epoch epoch :cursor @cursor :resync? false :stale #{} :pushes []})
                (reply {:op ::remote/sync-result :ok? true :epoch epoch :cursor @cursor :resync? false :stale #{[:item 1]} :pushes []})))

            nil))})}))

;; ---------------------------------------------------------------------------
;; 1. Core: suspended? query stays suspended + demand! lifecycle
;; ---------------------------------------------------------------------------

(defc ui-suspending-reader [qfn arg]
  (bind v (sg/query qfn arg))
  (render (<< [:span (str v)])))

(deftest suspended-query-stays-suspended-and-demands-track
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::core-demand-rt)
        demands-log (atom [])
        ?need (fn [env k]
                (sg/demand! env [:need k])
                (if-some [v (get-in env [::box k])]
                  v
                  (sg/suspend!)))]

    (sg/add-kv-table rt-ref ::box {} {})
    (sg/reg-demand-handler! rt-ref ::test
      (fn [prev next]
        (swap! demands-log conj [prev next])))

    (async done
      (run-steps done
        [(fn []
           ;; Two readers share the same suspended query; BOTH must suspend!
           (sg/render rt-ref root
             (sg/suspense {:fallback (<< [:em "wait"]) :timeout 0}
               (<< (ui-suspending-reader ?need :a)
                   (ui-suspending-reader ?need :a))))
           (is (= "wait" (.-textContent root)))
           (is (= [[nil #{[:need :a]}]] @demands-log)))

         (fn []
           (sg/run-tx! rt-ref (fn [env _ _] (assoc-in env [::box :a] "ok"))))

         (fn []
           (is (= "okok" (.-textContent root))))

         (fn []
           ;; Unmounting drops the demand (next = nil)
           (sg/render rt-ref root (<< [:div "gone"]))
           (is (= "gone" (.-textContent root)))
           (is (= [#{[:need :a]} nil] (last @demands-log))))]))))

;; ---------------------------------------------------------------------------
;; 2. Batching, Named Views (:summary -> :detail), Sideloading, Absent
;; ---------------------------------------------------------------------------

(defc ui-item-summary [id]
  (bind item (remote/entity :item id :summary))
  (render (<< [:span (if item (:item/label item) "NONE") ";"])))

(defc ui-item-detail [id]
  (bind item (remote/entity :item id :detail))
  (bind owner (when-some [oid (:item/owner item)]
                (remote/entity :owner oid)))
  (render (<< [:div (str (:item/label item) "|" (:item/notes item) "|" (:owner/name owner))])))

(defc ui-templates []
  (bind tpls (remote/query :templates))
  (render (<< [:span (str (count tpls))])))

(defc ui-fail-inline []
  (bind res (remote/query :items/fail nil {:suspend? false}))
  (render (<< [:span (str (name (:state res)) ":" (get-in res [:error :message]))])))

(deftest batching-named-views-sideloading-and-projections
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::batch-rt)
        srv (make-fake-server)]

    (remote/init! rt-ref
      {:transport (:transport srv)
       :tables {:item {:primary-key :item/id
                       :views {:detail {:satisfies #{:summary :default}}
                               :summary {:satisfies #{:default}}}}
                :owner {:primary-key :owner/id}}
       :grace-ms 0})

    (async done
      (run-steps done
        [(fn []
           ;; Mount 3 summary lookups (1, 2, 999 absent) + 1 projection query on the SAME frame!
           (sg/render rt-ref root
             (<< (sg/suspense {:fallback (<< [:em "loading"]) :timeout 0}
                   (<< (ui-item-summary 1)
                       (ui-item-summary 2)
                       (ui-item-summary 999)
                       (ui-templates)))
                 (ui-fail-inline)))
           (is (= "loadingloading:" (.-textContent root))))

         (fn []
           ;; ALL 4 demands coalesced into ONE single read batch!
           (let [reads (filter #(= ::remote/read (:op %)) @(:log srv))]
             (is (= 1 (count reads)))
             ;; The 3 entity demands were coalesced into 1 entity request with :ids #{1 2 999}!
             (let [ereqs (filter #(= :entity (:op %)) (:requests (first reads)))]
               (is (= 1 (count ereqs)))
               (is (= #{1 2 999} (:ids (first ereqs))))))
           (is (= "Alpha;Beta;NONE;2error:boom" (.-textContent root))))

         ;; Now mount :detail for item 1 -> summary doesn't satisfy detail, so fetches :detail
         ;; Sideloaded :owner 10 makes (remote/entity :owner 10) an instant hit (zero 2nd RTT!)
         (fn []
           (reset! (:log srv) [])
           (sg/render rt-ref root
             (sg/suspense {:fallback (<< [:em "loading-detail"]) :timeout 0}
               (ui-item-detail 1))))

         (fn []
           (let [reads (filter #(= ::remote/read (:op %)) @(:log srv))]
             (is (= 1 (count reads)))
             (is (= [{:op :entity :table :item :view :detail :ids #{1}}]
                    (:requests (first reads)))))
           (is (= "Alpha|Alpha detail|Ada" (.-textContent root))))

         ;; Now mount :summary for item 1 -> :detail satisfies :summary -> ZERO network requests!
         (fn []
           (reset! (:log srv) [])
           (sg/render rt-ref root (ui-item-summary 1))
           (is (= "Alpha;" (.-textContent root)))
           (is (empty? @(:log srv))))

         (fn []
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))

;; ---------------------------------------------------------------------------
;; 3. Optimistic Mutations: 1-RTT :data Settlement, Rollback on :reject, No Flicker
;; ---------------------------------------------------------------------------

(deftx rename-item! [id label]
  [tx-env _ _]
  (-> tx-env
      (assoc-in [:item id :item/label] label)
      (remote/transact :item/rename {:id id :label label}
        {:on-result {:e ::rename-done}})))

(deftx rename-stale-only! [id label]
  [tx-env _ _]
  (-> tx-env
      (assoc-in [:item id :item/label] label)
      (remote/transact :item/rename-stale-only {:id id :label label})))

(deftx delete-item! [id]
  [tx-env _ _]
  (remote/transact tx-env :item/delete {:id id}))

(deftest optimistic-overlay-settlement-and-rollback
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::tx-rt)
        srv (make-fake-server)
        results (atom [])]

    (sg/reg-event rt-ref ::rename-done
      (fn [tx-env {:keys [call-result]}]
        (swap! results conj call-result)
        tx-env))

    (remote/init! rt-ref
      {:transport (:transport srv)
       :tables {:item {:primary-key :item/id
                       :views {:detail {:satisfies #{:summary :default}}}}
                :owner {:primary-key :owner/id}}
       :grace-ms 0})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root
             (sg/suspense {:fallback "wait" :timeout 0}
               (ui-item-detail 1))))

         (fn []
           (is (= "Alpha|Alpha detail|Ada" (.-textContent root)))
           ;; Defer server response so we can inspect optimistic state + push while in-flight
           (reset! (:defer-tx? srv) true)
           (sg/run-tx! rt-ref (rename-item! 1 "Optimistic")))

         (fn []
           ;; Visible immediately!
           (is (= "Optimistic|Alpha detail|Ada" (.-textContent root)))
           (is (= 1 (:pending (remote/stats rt-ref))))

           ;; While in-flight, another user pushes a change to :item/notes ->
           ;; :item/notes updates, while optimistic :item/label stays on top (no flicker!)
           ((:push! @(:push-ref srv))
            {:op ::remote/push
             :data {:item {1 {:item/id 1 :item/label "Alpha" :item/notes "Updated by Bob"}}}
             :views {[:item 1] #{:detail}}})
           )

         (fn []
           (is (= "Optimistic|Updated by Bob|Ada" (.-textContent root)))
           ;; Now complete server transact -> 1-RTT settlement via :data!
           (reset! (:log srv) [])
           ((:flush-tx! srv)))

         (fn []
           (is (= "Optimistic|Alpha detail|Ada" (.-textContent root)))
           (is (= 0 (:pending (remote/stats rt-ref))))
           (is (= [{:ok? true :result {:renamed 1}}] @results))
           ;; Zero extra read needed for item 1 because server returned :data!
           (is (empty? (filter #(= ::remote/read (:op %)) @(:log srv)))))

         ;; Now test rejection -> automatic rollback!
         (fn []
           (reset! (:defer-tx? srv) true)
           (sg/run-tx! rt-ref (rename-item! 1 "!BadName")))

         (fn []
           (is (= "!BadName|Alpha detail|Ada" (.-textContent root)))
           ((:flush-tx! srv)))

         (fn []
           ;; Rolled back cleanly to "Optimistic"!
           (is (= "Optimistic|Alpha detail|Ada" (.-textContent root)))
           (is (= 0 (:pending (remote/stats rt-ref))))
           (is (= {:ok? false :reject "invalid label"} (last @results))))

         ;; Test stale-only transact (no :data in reply -> waits for refetch, zero flicker)
         (fn []
           (reset! (:defer-tx? srv) false)
           (sg/run-tx! rt-ref (rename-stale-only! 1 "Refetched")))

         (fn []
           (is (= "Refetched|Alpha detail|Ada" (.-textContent root)))
           (is (= 0 (:pending (remote/stats rt-ref)))))

         ;; Test :retract
         (fn []
           (sg/run-tx! rt-ref (delete-item! 1)))

         (fn []
           (is (= "||" (.-textContent root)))
           ;; Aborted tx (fails inside kv-interceptor-after) must NOT record pending/base or send
           (reset! (:log srv) [])
           (let [prev-err (::sg/event-error-handler @rt-ref)]
             (swap! rt-ref assoc ::sg/event-error-handler (fn [_ _ _ _] ::swallowed))
             (sg/run-tx! rt-ref
               (fn [tx-env _ _]
                 (-> tx-env
                     (assoc-in [:item 2 :item/label] "NeverCommitted")
                     (remote/transact :item/rename {:id 2 :label "NeverCommitted"})
                     ;; causes kv-interceptor-after to throw during commit
                     (assoc :owner "invalid-not-a-map"))))
             (swap! rt-ref assoc ::sg/event-error-handler prev-err))
           (is (= 0 (:pending (remote/stats rt-ref))))
           (is (empty? (:base @(::remote/state @rt-ref))))
           (is (empty? @(:log srv)))
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))

;; ---------------------------------------------------------------------------
;; 4. Windowed pagination + Sync (Stale SWR + Push + Reconnect Catch-up)
;; ---------------------------------------------------------------------------

(defn ?window-items [env win]
  (remote/window env :items/page {} win {:page-size 2}))

(defc ui-window [offset num]
  (bind res (sg/query ?window-items {:offset offset :num num}))
  (render
    (<< [:div (str (:item-count res) ":" (apply str (interpose "," (map :item/label (:slice res)))))])))

(deftest windowed-pagination-and-sync
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::win-rt)
        srv (make-fake-server)]

    (remote/init! rt-ref
      {:transport (:transport srv)
       :tables {:item {:primary-key :item/id
                       :views {:detail {:satisfies #{:summary :default}}}}}
       :grace-ms 0})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root
             (sg/suspense {:fallback "loading-win" :timeout 0}
               (ui-window 0 2))))

         (fn []
           (is (= "3:Alpha,Beta" (.-textContent root)))
           ;; Scroll across page boundary (offset 1, num 2 -> page 0 & page 1)
           (sg/render rt-ref root
             (sg/suspense {:fallback "loading-win" :timeout 0}
               (ui-window 1 2))))

         (fn []
           (is (= "3:Beta,Gamma" (.-textContent root)))
           ;; External change on server + push ::stale (SWR: revalidates without blanking!)
           (swap! (:db srv) assoc-in [:item 2 :item/label] "Beta-Synced")
           ((:push! @(:push-ref srv)) {:op ::remote/stale :tags #{[:item 2] :items/all}}))

         (fn []
           (is (= "3:Beta-Synced,Gamma" (.-textContent root)))
           ;; Reconnect catch-up via ::sync
           (swap! (:db srv) assoc-in [:item 1 :item/label] "Alpha-Catchup")
           (swap! (:cursor srv) inc)
           (sg/render rt-ref root (ui-window 0 2)))

         (fn []
           (is (= "3:Alpha,Beta-Synced" (.-textContent root)))
           ((:disconnect! @(:push-ref srv)))
           ((:hello! @(:push-ref srv)) {:op ::remote/hello :conn-id "local-2" :epoch "test-epoch" :cursor 99}))

         (fn []
           (is (= "3:Alpha-Catchup,Beta-Synced" (.-textContent root)))
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))

;; ---------------------------------------------------------------------------
;; 5. Eviction: LRU + Grace + read-index Pinning (sg/kv-lookup) + forget!
;; ---------------------------------------------------------------------------

(defc ui-plain-kv-reader [id]
  (bind item (sg/kv-lookup :item id))
  (render (<< [:span (str "kv:" (:item/label item))])))

(deftest eviction-respects-lru-and-read-index
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::evict-rt)
        srv (make-fake-server)]

    (remote/init! rt-ref
      {:transport (:transport srv)
       :tables {:item {:primary-key :item/id
                       :views {:detail {:satisfies #{:summary :default}}}}
                :owner {:primary-key :owner/id}}
       :grace-ms 10000
       ;; Only keep 1 inactive demand in LRU!
       :max-cached 1})

    (async done
      (run-steps done
        [(fn []
           ;; Load item 1 (:detail, sideloads :owner 10) and item 2 (:summary)
           (sg/render rt-ref root
             (sg/suspense {:fallback "wait" :timeout 0}
               (<< (ui-item-detail 1)
                   (ui-item-summary 2)))))

         (fn []
           (is (= "Alpha|Alpha detail|AdaBeta;" (.-textContent root)))
           ;; Unmount both remote demands, BUT mount a plain sg/kv-lookup on item 1!
           (sg/render rt-ref root (ui-plain-kv-reader 1)))

         (fn []
           (is (= "kv:Alpha" (.-textContent root)))
           ;; Force sweep! With max-cached = 1, oldest inactive demand is evicted,
           ;; then set grace-ms check by sweeping after mounting a 3rd demand or directly:
           ;; Let's mount & unmount item 3 so both 1 and 2 are beyond max-cached = 1!
           (sg/render rt-ref root
             (<< (ui-plain-kv-reader 1)
                 (sg/suspense {:fallback "w" :timeout 0} (ui-item-summary 3)))))

         (fn []
           (sg/render rt-ref root (ui-plain-kv-reader 1)))

         (fn []
           (reset! (:log srv) [])
           (remote/sweep! rt-ref)
           ;; Item 2 (not read anywhere) MUST be evicted from KV!
           (is (nil? (get-in @rt-ref [::sg/kv :item 2])))
           ;; Owner 10 (sideloaded by item 1, no longer read anywhere) MUST be evicted too!
           (is (nil? (get-in @rt-ref [::sg/kv :owner 10])))
           ;; Item 1 IS STILL PINNED in KV because ui-plain-kv-reader uses sg/kv-lookup!
           (is (= "Alpha" (get-in @rt-ref [::sg/kv :item 1 :item/label])))
           ;; Server was told to forget dropped tags
           (is (seq (filter #(= ::remote/forget (:op %)) @(:log srv))))
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))

;; ---------------------------------------------------------------------------
;; 6. Both Built-in Wire Transports: ws-transport & http-transport (+ SSE)
;; ---------------------------------------------------------------------------

(deftype FakeWS [url listeners ^:mutable readyState sent]
  Object
  (addEventListener [_ type f] (swap! listeners assoc type f))
  (send [_ text] (swap! sent conj text))
  (close [_] (set! readyState 3)))

(deftype FakeEventSource [url listeners ^:mutable readyState]
  Object
  (addEventListener [_ type f] (swap! listeners assoc type f))
  (close [_] (set! readyState 2)))

(deftest ws-transport-roundtrip
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::ws-transport-rt)
        sockets (atom [])]
    (set! js/global -self #js {:location #js {:href "https://example.com/"}})
    (set! js/global -WebSocket
      (js/Object.assign
        (fn [url]
          (let [s (FakeWS. url (atom {}) 0 (atom []))]
            (swap! sockets conj s)
            s))
        #js {:CONNECTING 0 :OPEN 1 :CLOSING 2 :CLOSED 3}))

    (edn/init! rt-ref {})
    (remote/init! rt-ref
      {:transport (remote/ws-transport {:url "/api/ws" :format :edn :ws-id ::test-ws})
       :tables {:item {:primary-key :item/id}}
       :grace-ms 0})

    (async done
      (run-steps done
        [(fn []
           ;; Mount before socket opens -> queued until ::hello arrives
           (sg/render rt-ref root
             (sg/suspense {:fallback "ws-wait" :timeout 0}
               (ui-item-summary 1)))
           (is (= "ws-wait" (.-textContent root))))

         (fn []
           (let [^FakeWS s (last @sockets)]
             (is (= "wss://example.com/api/ws" (.-url s)))
             (set! (.-readyState s) 1)
             ((get @(.-listeners s) "open") #js {})
             ;; Server sends ::hello
             ((get @(.-listeners s) "message")
              #js {:data (pr-str {:op ::remote/hello :conn-id "ws-1" :epoch "e1" :cursor 1})})))

         (fn []
           ;; Client flushed queued read batch!
           (let [^FakeWS s (last @sockets)
                 sent (mapv reader/read-string @(.-sent s))
                 read-msg (first sent)]
             (is (= 1 (count sent)))
             (is (= ::remote/read (:op read-msg)))
             ;; Server replies with matching :call-id
             ((get @(.-listeners s) "message")
              #js {:data (pr-str {:op ::remote/read-result
                                  :call-id (:call-id read-msg)
                                  :ok? true
                                  :epoch "e1"
                                  :cursor 1
                                  :entities {:item {1 {:item/id 1 :item/label "FromWS"}}}
                                  :views {[:item 1] #{:summary}}
                                  :absent []
                                  :queries {}})})))

         (fn []
           (is (= "FromWS;" (.-textContent root)))
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))

(deftest http-transport-pull-and-sse
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::http-transport-rt)
        fetches (atom [])
        sses (atom [])]
    (set! js/global -EventSource
      (js/Object.assign
        (fn [url]
          (let [es (FakeEventSource. url (atom {}) 1)]
            (swap! sses conj es)
            es))
        #js {:CONNECTING 0 :OPEN 1 :CLOSED 2}))
    (set! js/global -fetch
      (fn [url opts]
        (let [body (reader/read-string (.-body opts))]
          (swap! fetches conj {:url url :headers (js->clj (.-headers opts)) :body body})
          (js/Promise.resolve
            #js {:ok true
                 :status 200
                 :text (fn []
                         (js/Promise.resolve
                           (pr-str
                             (case (:op body)
                               ::remote/read
                               {:op ::remote/read-result
                                :ok? true
                                :epoch "e1"
                                :cursor 1
                                :entities {:item {1 {:item/id 1 :item/label "FromHTTP"}}}
                                :views {[:item 1] #{:summary}}
                                :absent []
                                :queries {}}
                               {:ok? true}))))}))))

    (edn/init! rt-ref {})
    (remote/init! rt-ref
      {:transport (remote/http-transport {:url "/api/remote" :sse-url "/api/remote/sse" :format :edn})
       :tables {:item {:primary-key :item/id}}
       :grace-ms 0})

    (async done
      (run-steps done
        [(fn []
           ;; SSE delivers hello with conn-id first
           (let [^FakeEventSource es (last @sses)]
             ((get @(.-listeners es) "hello")
              #js {:data (pr-str {:op ::remote/hello :conn-id "sse-42" :epoch "e1" :cursor 1})}))
           (sg/render rt-ref root
             (sg/suspense {:fallback "http-wait" :timeout 0}
               (ui-item-summary 1))))

         (fn []
           (is (= "FromHTTP;" (.-textContent root)))
           ;; POST went to /api/remote/read with x-grove-conn header!
           (is (= "/api/remote/read" (:url (first @fetches))))
           (is (= "sse-42" (get-in (first @fetches) [:headers "x-grove-conn"])))
           ;; SSE pushes direct update (::remote/push)
           (let [^FakeEventSource es (last @sses)]
             ((get @(.-listeners es) "push")
              #js {:data (pr-str {:op ::remote/push
                                  :epoch "e1"
                                  :cursor 2
                                  :data {:item {1 {:item/id 1 :item/label "FromSSEPush"}}}
                                  :views {[:item 1] #{:summary}}})})))

         (fn []
           (is (= "FromSSEPush;" (.-textContent root)))
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))

;; ---------------------------------------------------------------------------
;; 7. Regression tests: optimistic dissoc, server attr deletion, stale-only unmounted,
;;    optimistic create over absent, multi-transact in one deftx
;; ---------------------------------------------------------------------------

(deftx clear-notes! [id]
  [tx-env _ _]
  (-> tx-env
      (update-in [:item id] dissoc :item/notes)
      (remote/transact :item/rename {:id id :label "!reject-dissoc"})))

(deftx create-over-absent! [id label]
  [tx-env _ _]
  (-> tx-env
      (assoc-in [:item id] {:item/id id :item/label label :item/notes "fresh"})
      (remote/transact :item/create {:id id :label label})))

(deftx two-transacts! [id]
  [tx-env _ _]
  (-> tx-env
      (assoc-in [:item id :item/label] "DoubleTx")
      (remote/transact :item/rename {:id id :label "DoubleTx"})
      (remote/transact :item/rename {:id id :label "DoubleTx"})))

(deftest optimistic-edge-cases-and-attr-deletion
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::opt-edge-rt)
        srv (make-fake-server)]

    (remote/init! rt-ref
      {:transport (:transport srv)
       :tables {:item {:primary-key :item/id
                       :views {:detail {:satisfies #{:summary :default}}
                               :summary {:satisfies #{:default}}}}
                :owner {:primary-key :owner/id}}
       :grace-ms 0})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root
             (sg/suspense {:fallback "wait" :timeout 0}
               (<< (ui-item-detail 1)
                   (ui-item-summary 999)))))

         (fn []
           (is (= "Alpha|Alpha detail|AdaNONE;" (.-textContent root)))
           ;; 1. Optimistic dissoc of :item/notes survives concurrent push on :item/label, then rolls back on reject!
           (reset! (:defer-tx? srv) true)
           (sg/run-tx! rt-ref (clear-notes! 1)))

         (fn []
           (is (= "Alpha||AdaNONE;" (.-textContent root)))
           ((:push! @(:push-ref srv))
            {:op ::remote/push
             :data {:item {1 {:item/id 1 :item/label "Alpha-Push"}}}
             :views {[:item 1] #{:summary}}}))

         (fn []
           ;; :item/label updated from push while :item/notes stayed dissoc'd!
           (is (= "Alpha-Push||AdaNONE;" (.-textContent root)))
           ((:flush-tx! srv)))

         (fn []
           ;; Rejected -> :item/notes rolled back cleanly!
           (is (= "Alpha-Push|Alpha detail|AdaNONE;" (.-textContent root)))
           ;; 2. Full authoritative read where server omits :item/notes removes the ghost attribute!
           (swap! (:db srv) update-in [:item 1] dissoc :item/notes)
           ((:push! @(:push-ref srv)) {:op ::remote/stale :tags #{[:item 1]}}))

         (fn []
           (is (= "Alpha||AdaNONE;" (.-textContent root)))
           ;; 3. Optimistic create over previously cached :absent? entity (999) is immediately visible!
           (reset! (:defer-tx? srv) true)
           (sg/run-tx! rt-ref (create-over-absent! 999 "Born")))

         (fn []
           (is (= "Alpha||AdaBorn;" (.-textContent root)))
           ;; Clean up deferred create tx
           (reset! (:deferred-txs srv) [])
           (swap! (::remote/state @rt-ref) assoc :sending nil :pending [] :base {})
           ;; 4. Multiple remote/transact calls in one deftx create only 1 pending overlay entry and send :tx-id
           (reset! (:log srv) [])
           (reset! (:defer-tx? srv) false)
           (sg/run-tx! rt-ref (two-transacts! 1)))

         (fn []
           (let [tx-msgs (filter #(= ::remote/transact (:op %)) @(:log srv))]
             (is (= 2 (count tx-msgs)))
             (is (every? #(some? (:tx-id %)) tx-msgs)))
           (is (= 0 (:pending (remote/stats rt-ref))))
           ;; 5. Stale-only transact with NO mounted demand keeps succeeded optimistic write in KV
           (sg/render rt-ref root (<< [:div "unmounted"]))
           (sg/run-tx! rt-ref (rename-stale-only! 1 "KeptWithoutDemand")))

         (fn []
           (is (= "KeptWithoutDemand" (get-in @rt-ref [::sg/kv :item 1 :item/label])))
           (is (= 0 (:pending (remote/stats rt-ref))))
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))

;; ---------------------------------------------------------------------------
;; 8. Reactive :stale state, SWR background error preservation, failed query recovery,
;;    and :unknown? transact failure revalidation
;; ---------------------------------------------------------------------------

(defc ui-templates-inline []
  (bind {:keys [state value error]} (remote/query :templates nil {:suspend? false}))
  (render (<< [:span (str (name state) ":" (count value) ":" (get error :message ""))])))

(defc ui-conn-status []
  (bind connected? (sg/kv-lookup ::remote/status :connected?))
  (render (<< [:span (if connected? "UP" "DOWN")])))

(deftest reactive-stale-swr-error-and-recovery
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::swr-rt)
        fail-reads? (atom false)
        fail-query? (atom true)
        defer-reads? (atom false)
        deferred-reads (atom [])
        push-ref (atom nil)
        tx-unknown? (atom false)
        tx-results (atom [])
        server-label (atom "Initial")]

    (sg/reg-event rt-ref ::tx-cb
      (fn [tx-env {:keys [call-result]}]
        (swap! tx-results conj call-result)
        tx-env))

    (remote/init! rt-ref
      {:transport
       (remote/local-transport
         {:on-start (fn [hooks]
                      (reset! push-ref hooks)
                      ((:hello! hooks) {:op ::remote/hello :conn-id "c1" :epoch "e1" :cursor 1}))
          :handler
          (fn [{:keys [op requests]} reply]
            (case op
              ::remote/read
              (let [run!
                    (fn []
                      (if @fail-reads?
                        (reply {:ok? false :error {:message "net-down"}})
                        (let [res (reduce
                                    (fn [acc {:keys [op table view ids query params]}]
                                      (case op
                                        :entity
                                        (-> acc
                                            (assoc-in [:entities table 1] {:item/id 1 :item/label @server-label})
                                            (assoc-in [:views [table 1]] #{(or view :default)}))
                                        :query
                                        (case query
                                          :templates
                                          (assoc-in acc [:queries [query params]]
                                            {:ok? true :value ["a.html" "b.html"] :tags #{:templates}})
                                          :flaky
                                          (if @fail-query?
                                            (assoc-in acc [:queries [query params]]
                                              {:ok? false :error {:message "temp-err"}})
                                            (assoc-in acc [:queries [query params]]
                                              {:ok? true :value "recovered" :tags #{:flaky}}))
                                          acc)))
                                    {:op ::remote/read-result :ok? true :epoch "e1" :cursor 1
                                     :entities {} :views {} :absent [] :queries {}}
                                    requests)]
                          (reply res))))]
                (if @defer-reads?
                  (swap! deferred-reads conj run!)
                  (run!)))

              ::remote/transact
              (if @tx-unknown?
                (do (reset! server-label "CommittedBeforeDrop")
                    (reply {:ok? false :unknown? true :reject ::remote/closed}))
                (reply {:ok? true}))

              nil))})
       :tables {:item {:primary-key :item/id}}
       :grace-ms 0})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root
             (<< (ui-conn-status) "|"
                 (ui-templates-inline) "|"
                 (sg/suspense {:fallback "w" :timeout 0} (ui-item-summary 1)))))

         (fn []
           (is (= "UP|ready:2:|Initial;" (.-textContent root)))
           ;; Disconnect updates reactive ::remote/status KV
           ((:disconnect! @push-ref)))

         (fn []
           (is (= "DOWN|ready:2:|Initial;" (.-textContent root)))
           ;; Defer next read and trigger ::stale -> non-suspending query MUST reactively show :stale!
           (reset! defer-reads? true)
           ((:push! @push-ref) {:op ::remote/stale :tags #{:templates}}))

         (fn []
           (is (= "DOWN|stale:2:|Initial;" (.-textContent root)))
           ;; Fail the background SWR refresh -> cached value MUST be preserved and error surfaced!
           (reset! fail-reads? true)
           (let [fns @deferred-reads]
             (reset! deferred-reads [])
             (doseq [f fns] (f))))

         (fn []
           (is (= "DOWN|ready:2:net-down|Initial;" (.-textContent root)))
           ;; Now test :unknown? transact failure -> passes :unknown? true AND revalidates item 1!
           (reset! fail-reads? false)
           (reset! defer-reads? false)
           (reset! tx-unknown? true)
           (sg/run-tx! rt-ref
             (fn [tx-env _ _]
               (-> tx-env
                   (assoc-in [:item 1 :item/label] "OptimisticBeforeDrop")
                   (remote/transact :item/rename {:id 1} {:on-result {:e ::tx-cb}})))))

         (fn []
           (is (= {:ok? false :reject ::remote/closed :unknown? true} (last @tx-results)))
           ;; Revalidation fetched server truth ("CommittedBeforeDrop") instead of staying on old "Initial"!
           (is (= "DOWN|ready:2:net-down|CommittedBeforeDrop;" (.-textContent root)))
           ;; Test failed query recovery via remote/invalidate! on query-id
           (sg/render rt-ref root
             (sg/suspense {:fallback "w" :timeout 0}
               (ui-suspending-reader (fn [env _] (remote/query env :flaky nil {:suspend? false})) nil))))

         (fn []
           (is (= "{:state :error, :error {:message \"temp-err\"}}" (.-textContent root)))
           (reset! fail-query? false)
           (remote/invalidate! rt-ref #{:flaky}))

         (fn []
           (is (= "{:state :ready, :value \"recovered\"}" (.-textContent root)))
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))

;; ---------------------------------------------------------------------------
;; 9. Pinned-orphan eviction cleanup, ::window-meta eviction, window out-of-range,
;;    and remote/clear!
;; ---------------------------------------------------------------------------

(deftest pinned-orphan-cleanup-window-meta-and-clear
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::orphan-rt)
        srv (make-fake-server)]

    (remote/init! rt-ref
      {:transport (:transport srv)
       :tables {:item {:primary-key :item/id
                       :views {:detail {:satisfies #{:summary :default}}
                               :summary {:satisfies #{:default}}}}
                :owner {:primary-key :owner/id}}
       :grace-ms 0
       :max-cached 0})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root
             (sg/suspense {:fallback "w" :timeout 0}
               (<< (ui-item-summary 1)
                   (ui-window 0 2)))))

         (fn []
           (is (= "Alpha;3:Alpha,Beta" (.-textContent root)))
           ;; ::window-meta was populated
           (is (= 3 (get-in @rt-ref [::sg/kv ::remote/window-meta [:items/page {}] :total])))
           ;; Unmount remote demands, keep plain sg/kv-lookup on item 1
           (sg/render rt-ref root (ui-plain-kv-reader 1)))

         (fn []
           (reset! (:log srv) [])
           (remote/sweep! rt-ref)
           ;; Item 1 is pinned by sg/kv-lookup -> stays in KV and NOT forgotten on server!
           (is (= "Alpha" (get-in @rt-ref [::sg/kv :item 1 :item/label])))
           (let [forgotten (into #{} (mapcat :tags) (filter #(= ::remote/forget (:op %)) @(:log srv)))]
             (is (not (contains? forgotten [:item 1]))))
           ;; Window queries were evicted -> ::window-meta cleaned up!
           (is (nil? (get-in @rt-ref [::sg/kv ::remote/window-meta [:items/page {}]])))
           ;; Now unmount sg/kv-lookup -> next sweep MUST evict item 1 and forget it!
           (sg/render rt-ref root (<< [:div "empty"])))

         (fn []
           (reset! (:log srv) [])
           (remote/sweep! rt-ref)
           (is (nil? (get-in @rt-ref [::sg/kv :item 1])))
           (let [forgotten (into #{} (mapcat :tags) (filter #(= ::remote/forget (:op %)) @(:log srv)))]
             (is (contains? forgotten [:item 1])))
           ;; Reload window, then test out-of-range window offset and remote/clear!
           (sg/render rt-ref root
             (sg/suspense {:fallback "w" :timeout 0}
               (ui-window 0 2))))

         (fn []
           (is (= "3:Alpha,Beta" (.-textContent root)))
           (reset! (:log srv) [])
           ;; Offset 50 >= total 3 -> must NOT fetch out-of-range page!
           (sg/render rt-ref root (ui-window 50 2)))

         (fn []
           (is (= "3:" (.-textContent root)))
           (is (empty? (filter #(= ::remote/read (:op %)) @(:log srv))))
           ;; remote/clear! wipes all remote KV tables and metadata
           (sg/render rt-ref root (<< [:div "logged-out"]))
           (remote/clear! rt-ref {:reconnect? false})
           (is (empty? (get-in @rt-ref [::sg/kv :item])))
           (is (empty? (get-in @rt-ref [::sg/kv ::remote/queries])))
           (is (empty? (get-in @rt-ref [::sg/kv ::remote/entity-meta])))
           (is (empty? (get-in @rt-ref [::sg/kv ::remote/window-meta])))
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))

;; ---------------------------------------------------------------------------
;; 10. WS transport: queues transacts until ready, timeouts, and re-init isolation
;; ---------------------------------------------------------------------------

(deftest ws-transport-queues-transact-timeout-and-reinit
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::ws-robust-rt)
        sockets (atom [])
        tx-results (atom [])]
    (set! js/global -self #js {:location #js {:href "https://example.com/"}})
    (set! js/global -WebSocket
      (js/Object.assign
        (fn [url]
          (let [s (FakeWS. url (atom {}) 0 (atom []))]
            (swap! sockets conj s)
            s))
        #js {:CONNECTING 0 :OPEN 1 :CLOSING 2 :CLOSED 3}))

    (sg/reg-event rt-ref ::ws-tx-res
      (fn [tx-env {:keys [call-result]}]
        (swap! tx-results conj call-result)
        tx-env))

    (edn/init! rt-ref {})
    (remote/init! rt-ref
      {:transport (remote/ws-transport {:url "/api/ws" :format :edn :ws-id ::robust-ws :timeout-ms 25})
       :tables {:item {:primary-key :item/id}}
       :grace-ms 0})

    (async done
      (run-steps done
        [(fn []
           ;; Fire a transact BEFORE socket is open -> must queue (NOT fail immediately!)
           (sg/run-tx! rt-ref
             (fn [tx-env _ _]
               (remote/transact tx-env :item/rename {:id 1 :label "Queued"}
                 {:on-result {:e ::ws-tx-res}}))))

         (fn []
           ;; Still queued, no failure yet!
           (is (empty? @tx-results))
           ;; Open socket + send ::hello -> queued transact is now sent!
           (let [^FakeWS s (last @sockets)]
             (set! (.-readyState s) 1)
             ((get @(.-listeners s) "open") #js {})
             ((get @(.-listeners s) "message")
              #js {:data (pr-str {:op ::remote/hello :conn-id "w1" :epoch "e1" :cursor 1})})))

         (fn []
           (let [^FakeWS s (last @sockets)
                 sent (mapv reader/read-string @(.-sent s))]
             (is (= 1 (count sent)))
             (is (= ::remote/transact (:op (first sent))))
             (is (some? (:tx-id (first sent))))))

         ;; Wait for 25ms timeout to fire
         (fn [])
         (fn [])

         (fn []
           ;; Timed out cleanly with :unknown? true, outbox unwedged!
           (is (= [{:ok? false :reject ::remote/timeout :unknown? true}] @tx-results))
           (is (nil? (:sending @(::remote/state @rt-ref))))
           ;; Re-init with a new transport and fire old socket's close -> must NOT corrupt new instance!
           (let [^FakeWS old-s (last @sockets)]
             (remote/init! rt-ref
               {:transport (remote/local-transport
                             {:on-start (fn [h] ((:hello! h) {:op ::remote/hello :conn-id "new" :epoch "e2" :cursor 1}))
                              :handler (fn [_ _])})
                :tables {:item {:primary-key :item/id}}
                :grace-ms 0})
             ((get @(.-listeners old-s) "close") #js {:code 1000 :reason "old"})))

         (fn []
           (is (true? (:connected? (remote/stats rt-ref))))
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))

;; ---------------------------------------------------------------------------
;; 11. Live Subscriptions: 1-tx push, interval merging, grace, reconnect, errors
;; ---------------------------------------------------------------------------

(defc ui-sub-suspend [interval]
  (bind v (remote/subscribe :home/summary nil {:interval interval}))
  (render (<< [:span (str "S:" (:power v))])))

(defc ui-sub-inline [sub-id interval mode]
  (bind res (remote/subscribe sub-id nil {:interval interval :mode mode :suspend? false}))
  (render (<< [:span (str (name (:state res)) ":" (or (:power (:value res)) (:message (:error res)) "nil")
                          (when (and (= :ready (:state res)) (:error res))
                            (str "|err=" (get-in res [:error :message]))))])))

(deftest subscriptions-lifecycle-interval-merge-reconnect-and-errors
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::sub-rt)
        log (atom [])
        hooks-ref (atom nil)
        active-ids (atom #{})
        summary-val (atom {:power 100})]

    (remote/init! rt-ref
      {:transport
       (remote/local-transport
         {:on-start
          (fn [hooks]
            (reset! hooks-ref hooks)
            ((:hello! hooks) {:op ::remote/hello :conn-id "sub-c1" :epoch "ep1" :cursor 1}))
          :handler
          (fn [{:keys [op subs ids] :as msg} _reply]
            (swap! log conj msg)
            (case op
              ::remote/subscribe
              (let [new-subs (remove #(contains? @active-ids (:id %)) subs)
                    _ (swap! active-ids into (map :id) new-subs)
                    vals (reduce
                           (fn [acc {:keys [id sub]}]
                             (if (= :home/summary sub)
                               (assoc acc id @summary-val)
                               acc))
                           {}
                           new-subs)
                    errs (reduce
                           (fn [acc {:keys [id sub]}]
                             (if (= :sub/broken sub)
                               (assoc acc id {:message "sub-failed"})
                               acc))
                           {}
                           new-subs)]
                (when (seq vals)
                  ((:push! @hooks-ref) {:op ::remote/sub-data :values vals}))
                (when (seq errs)
                  ((:push! @hooks-ref) {:op ::remote/sub-error :errors errs})))

              ::remote/unsubscribe
              (swap! active-ids #(apply disj % ids))

              nil))})
       :tables {}
       :grace-ms 0})

    (async done
      (run-steps done
        [(fn []
           ;; Mount suspending subscriber (10s, :interval) + failing inline subscriber in same frame
           (sg/render rt-ref root
             (<< (sg/suspense {:fallback (<< [:em "wait"]) :timeout 0}
                   (ui-sub-suspend 10000))
                 (ui-sub-inline :sub/broken 5000 :throttle)))
           (is (= "waitloading:nil" (.-textContent root))))

         (fn []
           ;; Both subscriptions batched into ONE ::remote/subscribe message!
           (let [sub-msgs (filter #(= ::remote/subscribe (:op %)) @log)]
             (is (= 1 (count sub-msgs)))
             (is (= 2 (count (:subs (first sub-msgs))))))
           (is (= "S:100error:sub-failed" (.-textContent root)))
           (is (= 2 (:live-subs (remote/stats rt-ref)))))

         ;; Server pushes an update -> UI updates in 1 tx, zero ::remote/read messages!
         (fn []
           (reset! log [])
           (let [sid (get-in @(::remote/state @rt-ref) [:subs [:home/summary nil] :id])]
             ((:push! @hooks-ref) {:op ::remote/sub-data :values {sid {:power 250}}})))

         (fn []
           (is (= "S:250error:sub-failed" (.-textContent root)))
           (is (empty? @log)))

         ;; Mount a second reader for :home/summary with shorter interval (2000) & :throttle mode ->
         ;; Client merges into ONE server subscription and sends updated interval/mode under same id!
         (fn []
           (reset! log [])
           (sg/render rt-ref root
             (<< (ui-sub-suspend 10000)
                 (ui-sub-inline :home/summary 2000 :throttle))))

         (fn []
           (let [sub-msgs (filter #(= ::remote/subscribe (:op %)) @log)
                 unsub-msgs (filter #(= ::remote/unsubscribe (:op %)) @log)]
             ;; :sub/broken unsubscribed immediately (sub-grace-ms = 0), :home/summary updated to 2000/:throttle
             (is (= 1 (count unsub-msgs)))
             (is (= 1 (count sub-msgs)))
             (is (= 1 (count (:subs (first sub-msgs)))))
             (let [s (first (:subs (first sub-msgs)))]
               (is (= :home/summary (:sub s)))
               (is (= 2000 (:interval s)))
               (is (= :throttle (:mode s)))))
           (is (= "S:250ready:250" (.-textContent root))))

         ;; Background error on ready sub preserves last value and exposes :error!
         (fn []
           (let [sid (get-in @(::remote/state @rt-ref) [:subs [:home/summary nil] :id])]
             ((:push! @hooks-ref) {:op ::remote/sub-error :errors {sid {:message "temp-err"}}})))

         (fn []
           (is (= "S:250ready:250|err=temp-err" (.-textContent root))))

         ;; Unmount faster reader -> client relaxes interval back to 10000 (mode nil)!
         (fn []
           (reset! log [])
           (sg/render rt-ref root (ui-sub-suspend 10000)))

         (fn []
           (let [sub-msgs (filter #(= ::remote/subscribe (:op %)) @log)]
             (is (= 1 (count sub-msgs)))
             (let [s (first (:subs (first sub-msgs)))]
               (is (= :home/summary (:sub s)))
               (is (= 10000 (:interval s)))
               (is (nil? (:mode s)))))
           (is (= "S:250" (.-textContent root))))

         ;; Reconnect (::hello) keeps rendered value (no suspense flash!) and re-subscribes automatically
         (fn []
           (reset! log [])
           (reset! active-ids #{})
           (reset! summary-val {:power 400})
           ((:hello! @hooks-ref) {:op ::remote/hello :conn-id "sub-c2" :epoch "ep1" :cursor 5})
           ;; Synchronously still renders last value (no flash to loading/wait)
           (is (= "S:250" (.-textContent root))))

         (fn []
           (let [sub-msgs (filter #(= ::remote/subscribe (:op %)) @log)]
             (is (= 1 (count sub-msgs))))
           (is (= "S:400" (.-textContent root))))

         ;; Unmount last subscriber -> sends ::remote/unsubscribe and evicts ::subs
         (fn []
           (reset! log [])
           (sg/render rt-ref root (<< [:div "done"])))

         (fn []
           (let [unsub-msgs (filter #(= ::remote/unsubscribe (:op %)) @log)]
             (is (= 1 (count unsub-msgs))))
           (is (zero? (:subs (remote/stats rt-ref))))
           (is (empty? (get-in @rt-ref [::sg/kv ::remote/subs])))
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))

(deftest subscriptions-grace-period-keeps-sub-alive-across-remount
  (let [root (make-dom)
        rt-ref (sg/get-runtime ::sub-grace-rt)
        log (atom [])
        hooks-ref (atom nil)]

    (remote/init! rt-ref
      {:transport
       (remote/local-transport
         {:on-start
          (fn [hooks]
            (reset! hooks-ref hooks)
            ((:hello! hooks) {:op ::remote/hello :conn-id "g1" :epoch "ep1" :cursor 1}))
          :handler
          (fn [{:keys [op subs] :as msg} _reply]
            (swap! log conj msg)
            (when (= ::remote/subscribe op)
              ((:push! @hooks-ref)
               {:op ::remote/sub-data
                :values (into {} (map (fn [{:keys [id]}] [id {:power 77}])) subs)})))})
       :tables {}
       :grace-ms 30000
       :sub-grace-ms 5000
       :sweep-interval-ms 0})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-sub-inline :home/summary 10000 :interval)))

         (fn []
           (is (= "ready:77" (.-textContent root)))
           (reset! log [])
           ;; Unmount -> during grace period, must NOT unsubscribe yet!
           (sg/render rt-ref root (<< [:div "away"])))

         (fn []
           (is (empty? @log))
           (is (= 1 (:subs (remote/stats rt-ref))))
           (is (= 0 (:live-subs (remote/stats rt-ref))))
           ;; Remount within grace -> instant cached value, zero subscribe/unsubscribe chatter!
           (sg/render rt-ref root (ui-sub-inline :home/summary 10000 :interval))
           (is (= "ready:77" (.-textContent root))))

         (fn []
           (is (empty? @log))
           ;; Unmount again, backdate :released-at past sub-grace-ms, run sweep! -> unsubscribes & evicts!
           (sg/render rt-ref root (<< [:div "away-2"])))

         (fn []
           (swap! (::remote/state @rt-ref) assoc-in [:subs [:home/summary nil] :released-at] 0)
           (remote/sweep! rt-ref))

         (fn []
           (is (= 1 (count (filter #(= ::remote/unsubscribe (:op %)) @log))))
           (is (zero? (:subs (remote/stats rt-ref))))
           (is (empty? (get-in @rt-ref [::sg/kv ::remote/subs])))
           (sg/unmount-root root)
           (remote/stop! rt-ref))]))))
