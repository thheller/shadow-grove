(ns shadow.grove.preload
  (:require
    [clojure.string :as str]
    [cognitect.transit :as transit]
    [goog.functions :as gfn]
    [goog.style :as gs]
    [clojure.core.protocols :as cp]
    [shadow.grove.db.ident :as ident]
    [shadow.grove.impl :as impl]
    [shadow.grove.kv :as kv]
    [shadow.remote.runtime.api :as p]
    [shadow.remote.runtime.shared :as shared]
    [shadow.remote.runtime.writer :as lw]
    [shadow.remote.runtime.obj-support :as obj-support]
    [shadow.cljs.devtools.client.shared :as cljs-shared]
    [shadow.cljs.devtools.client.env :as client-env]
    [shadow.arborist.protocols :as ap]
    [shadow.arborist :as sa]
    [shadow.arborist.common]
    [shadow.arborist.collections]
    [shadow.grove :as sg :refer (<< defc)]
    [shadow.grove.trace :as trace]
    [shadow.grove.runtime :as rt]
    [shadow.grove.ui.portal]
    [shadow.grove.ui.suspense]
    [shadow.grove.ui.loadable :as loadable]
    [shadow.grove.components :as comp]
    [shadow.grove.devtools.protocols :as dp]
    [shadow.lazy :as lazy]
    [shadow.grove.devtools :as-alias m]
    ))

(set! *warn-on-infer* false)

(defonce perf-data-ref (atom {}))

(defn safe-inc [x]
  (if (nil? x)
    1
    (inc x)))

(extend-type sa/TreeRoot
  cp/Datafiable
  (datafy [this]
    {:container (.-container this)
     :env (.-env this)
     :root (.-root this)}))

(extend-type js/Map
  cp/Datafiable
  (datafy [this]
    (persistent!
      (reduce
        (fn [m k]
          (assoc! m k (.get this k)))
        (transient {})
        (.keys this)))))

(extend-type js/Set
  cp/Datafiable
  (datafy [this]
    (persistent!
      (reduce
        (fn [s v]
          (conj! s v))
        (transient #{})
        (.values this)))))

(set! *warn-on-infer* false)

(defn as-embedded-value [{:keys [obj-support] :as ctx} value]
  ;; sending as edn-limit, so that large values don't blow everything up
  ;; we don't have transit handlers for every possible thing that may show up
  ;; edn is more forgiving here
  ;; FIXME: figure out a good max size? make this configurable?
  (let [s (lw/pr-str-limit value 10000)]
    (if (str/starts-with? s "1,") ;; limit reached
      ;; FIXME: UI can't handle this yet, so no need to send for now
      {:preview (subs s 2) #_#_:oid (obj-support/register obj-support value {})}
      {:edn (subs s 2)})))

(extend-protocol dp/ISnapshot
  default
  (snapshot [this ctx]
    ;; (js/console.log "unknown snapshot" (pr-str (type this)) this)
    {:type (pr-str (type this))})

  shadow.arborist/TreeRoot
  (snapshot [this ctx]
    {:type 'shadow.arborist/TreeRoot
     :container (let [container (.-container this)]
                  (str "[:" (.toLowerCase (.-tagName container))
                       (when-let [id (.-id container)]
                         (when (not= id "")
                           (str "#" id)))
                       " ...]"))
     ;; always managed root, can skip it in UI
     :children [(dp/snapshot (-> this .-root .-node) ctx)]})

  shadow.arborist.common/ManagedRoot
  (snapshot [this ctx]
    {:type 'shadow.arborist.common/ManagedRoot
     :children
     [(when-some [node (.-node this)]
        (dp/snapshot node ctx))]})

  shadow.arborist.common/ManagedText
  (snapshot [this ctx]
    {:type 'shadow.arborist.common/ManagedText
     :val (.-val this)})

  shadow.arborist.fragments/ManagedFragment
  (snapshot [this ctx]
    (assoc (-> this .-code .-debug-info)
      :type 'shadow.arborist.fragments/ManagedFragment
      :children
      (->> (.-exports this)
           (array-seq)
           (filter #(satisfies? ap/IManaged %))
           (map #(dp/snapshot % ctx))
           (vec))))

  shadow.arborist.collections/SimpleCollection
  (snapshot [this ctx]
    {:type 'shadow.arborist.collections/SimpleCollection
     :children
     (->> (.-items this)
          (array-seq)
          (map #(dp/snapshot (.-managed %) ctx))
          (vec))})

  shadow.arborist.collections/DiffCollection
  (snapshot [this ctx]
    {:type 'shadow.arborist.collections/DiffCollection
     :keyed? (some? (.-key-fn this))
     :children
     (->> (.-items this)
          (array-seq)
          (map #(dp/snapshot (.-managed %) ctx))
          (vec))})


  shadow.grove.ui.suspense/SuspenseRoot
  (snapshot [this ctx]
    {:type 'shadow.grove.ui.suspense/SuspenseRoot
     :children
     [(when-some [node (.-display this)]
        (dp/snapshot node ctx))
      (when-some [node (.-offscreen this)]
        (dp/snapshot node ctx))]})

  shadow.grove.ui.portal/PortalNode
  (snapshot [this ctx]
    {:type 'shadow.grove.ui.portal/PortalNode
     :children [(dp/snapshot (.-node (.-root this)) ctx)]})

  shadow.grove.ui.loadable/LoadableRoot
  (snapshot [this ctx]
    {:type 'shadow.grove.ui.loadable/LoadableRoot
     :loaded (lazy/ready? (.-loadable this))
     :children [(dp/snapshot (.-managed this) ctx)]})



  shadow.grove.components/ManagedComponent
  (snapshot [this ctx]
    {:type 'shadow.grove.components/ManagedComponent
     :name (-> this .-config .-component-name)
     :instance-id (.-instance-id this)
     :suspended? (.-suspended? this)
     :destroyed? (.-destroyed? this)
     :error? (.-error? this)
     :file (some-> this .-config .-debug-info (:file))
     :line (some-> this .-config .-debug-info (:line))
     :column (some-> this .-config .-debug-info (:column))
     :args
     (let [args (.-args this)
           args-config (-> this .-config .-debug-info (:args))]
       (reduce-kv
         (fn [v idx arg-name]
           (conj v {:name arg-name :value (as-embedded-value ctx (nth args idx))}))
         []
         args-config))
     :refs
     (let [values (.-ref-values this)
           names (-> this .-config .-debug-info (:refs))]
       (reduce-kv
         (fn [v idx ref-name]
           (conj v {:name ref-name :value (as-embedded-value ctx (aget values idx))}))
         []
         (or names [])))
     :slots
     (let [slots (-> this .-config .-slots)]
       (->> (range (alength slots))
            (map (fn [idx]
                   (let [config (aget slots idx)
                         value (aget (.-slot-values this) idx)
                         value (as-embedded-value ctx value)]
                     (-> (.-debug-info config)
                         (assoc
                           :idx idx
                           :depends-on (.-depends-on config)
                           :affects (.-affects config)
                           :value value)))))
            (vec)))
     ;; FIXME: should this just keep the managedroot in snapshot?
     ;; its always the managed root, so we can skip this in visuals
     :children [(dp/snapshot (-> this .-root .-node) ctx)]})
  )

(defn take-snapshot* [env]
  (->> (vals @rt/known-runtimes-ref)
       (mapcat (fn [rt-ref]
                 (::sg/roots @rt-ref)))
       (map #(dp/snapshot % env))
       (vec)))

(defn take-snapshot [{:keys [runtime] :as env} msg]
  (shared/reply runtime msg
    {:op ::snapshot
     :snapshot (take-snapshot* env)}))

(defn component-snapshot []
  ;; more lightweight variant for trace
  ;; goal is to show component hierarchy properly, so only need id/name/parent
  ;; maybe more later
  (let [m
        (reduce-kv
          (fn [m instance-id instance]
            (let [config (.-config instance)
                  parent-env (.-parent-env instance)
                  parent (::comp/component parent-env)]

              (assoc m
                instance-id
                (-> {:instance-id instance-id
                     :component-name (.-component-name config)
                     ;; useful info only if snapshot is done before updates are done
                     ;; can show which components are going to update because of which slots
                     ;; but while update is in progress a component render may pass changed
                     ;; data to children, which will make them update. so not totally accurate
                     ;; but should make for good visualization?
                     :dirty-slots (.-dirty-slots instance)}
                    (cond->
                      parent
                      (assoc :parent-id (.-instance-id parent)))))))

          {}
          @comp/instances-ref)

        tree-walker
        (js/document.createTreeWalker
          js/document.body
          js/NodeFilter.SHOW_COMMENT)]

    ;; adds an :idx value to each component denoting its relative position in the DOM
    ;; used for ordering since components only manage a single child and thus have no notion of ordering
    (loop [m m
           idx 0]
      (if-not (.nextNode tree-walker)
        m
        (let [node (.-currentNode tree-walker)
              text (.-textContent node)]

          (cond
            ;; component marker-before
            (str/starts-with? text "component:")
            (let [id (.-instance-id (.-shadow$instance node))]
              (recur (assoc-in m [id :idx] idx) (inc idx)))

            :else
            (recur m idx)))))))

(defonce devtools-ref (atom nil))

(defn extension-info []
  (doto (client-env/devtools-info)
    (unchecked-set "client_id" (shared/get-client-id (:runtime @devtools-ref)))
    ))

(defonce border-highlight
  (doto (js/document.createElement "div")
    (gs/setStyle
      #js {"border" "2px solid red"
           "background-color" "red"
           "opacity" "0.2"
           "position" "absolute"
           "pointer-events" "none"
           "z-index" "1000"
           "top" "0px"
           "left" "0px"
           "width" "0px"
           "height" "0px"})))

(defn outline-component [component]
  (let [range
        (doto (js/document.createRange)
          (.setStart (.-marker-before component) 0)
          (.setEnd (.-marker-after component) 0))

        rect
        (.getBoundingClientRect range)

        style
        #js {"top" (str (.-top rect) "px")
             "left" (str (.-left rect) "px")
             "height" (str (.-height rect) "px")
             "width" (str (.-width rect) "px")}]

    (gs/setStyle border-highlight style)))

(defn highlight-component [svc {:keys [component] :as msg}]
  (when-some [component (get @comp/instances-ref component)]
    (when-not (.-isConnected border-highlight)
      (js/document.body.appendChild border-highlight))
    (outline-component component)))

(defn remove-highlight [svc msg]
  (.remove border-highlight))

(defn request-log [svc {:keys [name component type idx] :as msg}]
  (when-some [comp (get @comp/instances-ref component)]
    (let [val (case type
                :arg
                (nth (.-args comp) idx)
                :slot
                (aget (.-slot-values comp) idx))]

      (js/console.group "DEVTOOLS" name)
      (js/console.log val)
      (js/console.groupEnd))))


;; FIXME: this needs some kind of garbage collection
;; these will pile up quick and they potentially hold large structures
;; want to hold on to quite a few so that fresh devtools don't start out empty
(defonce tx-seq-ref (atom 0))
(defonce tx-ref (atom {}))

(defn count-tx-keys [tx-info key]
  (reduce-kv
    (fn [c kv-table kv-summary]
      (let [key-set (get kv-summary key)]
        (+ c (count key-set))))
    0
    tx-info))

(defn as-stream-event [{::sg/keys [tx-info] :as entry}]
  (-> entry
      (select-keys
        ;; FIXME: event is possible very large, should maybe only send it in full when requested
        [::m/ts ::m/tx-id ::sg/app-id ::sg/event ::sg/fx])
      (assoc :count-new (count-tx-keys tx-info :keys-new))
      (assoc :count-updated (count-tx-keys tx-info :keys-updated))
      (assoc :count-removed (count-tx-keys tx-info :keys-removed))
      (assoc :type :tx-report)))

(defn stream-sub [{:keys [streams-ref runtime] :as svc} {:keys [from] :as msg}]
  (swap! streams-ref conj from)

  (shared/reply runtime msg
    {:op ::m/stream-start

     ;; send stored events in order they appeared
     :events
     (->> @tx-ref
          (sort-by first)
          (map second)
          (map as-stream-event)
          (vec))}))

(defn get-runtimes [{:keys [runtime] :as svc} msg]
  (shared/reply runtime msg
    {:op ::m/runtimes
     :runtimes (set (keys @rt/known-runtimes-ref))}))

(defn get-db-copy [{:keys [runtime] :as svc} {:keys [app-id] :as msg}]
  (shared/reply runtime msg
    {:op ::m/db-copy
     :db (-> @rt/known-runtimes-ref (get app-id) (deref) (get ::rt/data-ref) (deref) (deref))}))

(defn make-tx-diff [{::sg/keys [tx-info] :as tx}]
  (reduce-kv
    (fn [m kv-table {:keys [data data-before] :as kv-summary}]
      (-> m
          (update :added into
            (->> (:keys-new kv-summary)
                 (mapv (fn [key]
                         {:key key
                          :kv-table kv-table
                          :val (get data key)}))))
          (update :updated into
            (->> (:keys-updated kv-summary)
                 (mapv (fn [key]
                         {:key key
                          :kv-table kv-table
                          :before (get data-before key)
                          :after (get data key)}))))
          (update :removed into
            (->> (:keys-removed kv-summary)
                 (mapv (fn [key]
                         {:key key
                          :kv-table kv-table
                          :val (get data-before key)}))))))
    {:added []
     :updated []
     :removed []}
    tx-info))

(comment
  (keys @tx-ref)
  (make-tx-diff (get @tx-ref 9)))

(defn get-tx-diff
  [{:keys [runtime] :as svc} {:keys [event-id tx-id] :as msg}]

  ;; FIXME: event-id is from the devtools ui, this should not be handled here

  (let [tx (get @tx-ref tx-id)]
    (if-not tx
      (shared/reply runtime msg
        {:op ::m/tx-not-found
         :event-id event-id})

      (shared/reply runtime msg
        {:op ::m/tx-diff
         :event-id event-id
         :diff (make-tx-diff tx)}
        ))))

;; data view support. a devtools UI subscribes with what it shows (a table, a filter,
;; the selected entry). this pushes that after a tx or a render, when it changed.
;; one sub per devtools client, a new kv-sub replaces the previous one.

;; {client-id sub}
(defonce kv-subs-ref (atom {}))

(def kv-row-limit 1000)

;; values are sent as data. one that prints longer than the limit is replaced by a
;; {::m/truncated "preview"} map. maps get a limit per attribute, so one large
;; attribute doesn't take the whole row with it.
(def kv-row-print-limit 5000)
(def kv-attr-print-limit 1000)
(def kv-entry-print-limit 100000)
(def kv-search-print-limit 10000)

;; values are immutable, so guarded and printed versions are cached per value
(defonce kv-row-cache (js/WeakMap.))
(defonce kv-search-cache (js/WeakMap.))

(defn kv-cached [^js cache v f]
  (if-not (coll? v)
    (f v)
    (let [cached (.get cache v)]
      (if-not (undefined? cached)
        cached
        (let [res (f v)]
          (.set cache v res)
          res)))))

(defn kv-guard [v limit]
  (cond
    (string? v)
    (if (> (.-length v) limit)
      {::m/truncated (subs v 0 limit)}
      v)

    (or (nil? v) (number? v) (boolean? v) (keyword? v) (symbol? v) (uuid? v))
    v

    :else
    (let [s (lw/pr-str-limit v limit)]
      (if (str/starts-with? s "1,")
        {::m/truncated (subs s 2)}
        v))))

(defn kv-guard-row-val* [v]
  (let [guarded (kv-guard v kv-row-print-limit)]
    (if (and (map? v) (not (identical? guarded v)))
      (reduce-kv
        (fn [m k v]
          (assoc m k (kv-guard v kv-attr-print-limit)))
        {}
        v)
      guarded)))

(defn kv-search-text* [v]
  (str/lower-case (subs (lw/pr-str-limit v kv-search-print-limit) 2)))

(defn kv-matches? [needle k v]
  (or (str/includes? (kv-cached kv-search-cache k kv-search-text*) needle)
      (str/includes? (kv-cached kv-search-cache v kv-search-text*) needle)))

(defn kv-read-index
  "{kv-table #{key}} of what the mounted UI reads. the read index doesn't know runtimes,
   two runtimes with a table of the same name share its entries"
  []
  (impl/reduce-read-keys
    (fn [m kv-table key]
      (update m kv-table (fnil conj #{}) key))
    {}))

(defn kv-table-data [app-id kv-table]
  (some-> (get @rt/known-runtimes-ref app-id) (deref) (::sg/kv) (get kv-table)))

(defn kv-table-refs
  "{attr other-table} from :joins and :attrs {attr {:references other-table}}"
  [config]
  (reduce-kv
    (fn [m attr {:keys [references]}]
      (if references
        (assoc m attr references)
        m))
    (or (:joins config) {})
    (or (:attrs config) {})))

(defn kv-summary [read-index]
  (->> (for [[app-id rt-ref] @rt/known-runtimes-ref
             [kv-table data] (::sg/kv @rt-ref)]
         {:app-id app-id
          :kv-table kv-table
          :count (count data)
          :read-count (count (get read-index kv-table))
          :table-read? (boolean (impl/table-read? kv-table))})
       ;; namespaced tables usually belong to libraries, those go last
       (sort-by (fn [{:keys [app-id kv-table]}]
                  [(str app-id)
                   (boolean (and (keyword? kv-table) (namespace kv-table)))
                   (str kv-table)]))
       (vec)))

(defn kv-sort-entries [entries]
  (try
    (vec (sort-by first entries))
    (catch :default e
      ;; keys of mixed types don't compare
      (vec (sort-by #(pr-str (first %)) entries)))))

(defn kv-table-state [kv-table data read-keys {filter-text :filter :keys [only-read?]}]
  (let [needle
        (when (seq filter-text)
          (str/lower-case filter-text))

        entries
        (persistent!
          (reduce-kv
            (fn [acc k v]
              (if (and (or (not only-read?) (contains? read-keys k))
                       (or (nil? needle) (kv-matches? needle k v)))
                (conj! acc [k v])
                acc))
            (transient [])
            data))

        matched
        (count entries)

        entries
        (if (<= matched kv-row-limit)
          entries
          ;; keep the rows the UI reads when cutting
          (let [{read true other false} (group-by #(contains? read-keys (first %)) entries)]
            (into [] (take kv-row-limit) (concat read other))))

        config
        (::kv/config (meta data))]

    {:kv-table kv-table
     :count (count data)
     :matched matched
     :primary-key (:primary-key config)
     :refs (kv-table-refs config)
     :table-read? (boolean (impl/table-read? kv-table))
     :rows
     (->> (kv-sort-entries entries)
          (mapv (fn [[k v]]
                  {:key k
                   :read? (contains? read-keys k)
                   :val (kv-cached kv-row-cache v kv-guard-row-val*)})))}))

(defn kv-fn-name [f]
  ;; "home$admin$db$_QMARK_devices" -> "home.admin.db/?devices"
  (let [n (when (fn? f) (.-name f))]
    (if-not (and (string? n) (not= "" n))
      (if (fn? f) "anonymous fn" (pr-str f))
      (let [s (demunge n)
            i (str/last-index-of s "/")]
        (if (nil? i)
          s
          (str (str/replace (subs s 0 i) "/" ".") (subs s i)))))))

(defn kv-guard-arg [v]
  (kv-cached kv-row-cache v kv-guard-row-val*))

(defn kv-component-path
  "component names from the root down to component"
  [component]
  (loop [c component
         path ()]
    (if (nil? c)
      (vec path)
      (recur (::comp/component (.-parent-env c))
        (conj path (.-component-name (.-config c)))))))

(defn kv-describe-slot-ref
  "the slot a read happens in, a component bind usually"
  [ref]
  (let [provider (when (instance? rt/SlotRef ref) (.-provider ^rt/SlotRef ref))
        slot-idx (when (instance? rt/SlotRef ref) (.-idx ^rt/SlotRef ref))]
    (if-not (instance? comp/ManagedComponent provider)
      ;; a custom slot provider, or something that isn't a slot
      {:provider (pr-str (type (or provider ref)))}
      (let [config (.-config provider)
            debug-info (.-debug-info config)
            slot-debug-info (some-> (.-slots config) (aget slot-idx) (.-debug-info))
            args (.-args provider)]
        {:instance-id (.-instance-id provider)
         :component (.-component-name config)
         :file (:file debug-info)
         :line (or (:line slot-debug-info) (:line debug-info))
         :column (or (:column slot-debug-info) (:column debug-info))
         :slot (:name slot-debug-info)
         :path (kv-component-path provider)
         :args
         (reduce-kv
           (fn [v idx arg-name]
             (conj v {:name arg-name :val (kv-guard-arg (nth args idx nil))}))
           []
           (or (:args debug-info) []))}))))

(defn kv-subs->vec [subs]
  ;; one reader as is, a js/Set for more, see impl/subs-add
  (cond
    (nil? subs) []
    (instance? js/Set subs) (let [arr (array)] (.forEach subs #(.push arr %)) (vec arr))
    :else [subs]))

(defn kv-key-readers
  "readers of key in kv-table, straight from the read index in impl"
  [kv-table key]
  (if-some [^impl/TableIndex ti (impl/km-get impl/read-index kv-table)]
    (kv-subs->vec (impl/km-get (.-keys ti) key))
    []))

(defn kv-table-readers [kv-table]
  (if-some [^impl/TableIndex ti (impl/km-get impl/read-index kv-table)]
    (kv-subs->vec (.-all ti))
    []))

(defn kv-describe-reader
  "a reader is the slot ref of a sg/kv-lookup, or a query shared by the slots using it"
  [reader]
  (if (instance? impl/Query reader)
    (let [[query-fn query-args] (.-query-key ^impl/Query reader)]
      {:query (kv-fn-name query-fn)
       ;; the rest args of sg/query, a seq
       :query-args (let [args (kv-guard-arg query-args)]
                     (if (seq? args) (vec args) args))
       :slots (mapv kv-describe-slot-ref (kv-subs->vec (.-subs ^impl/Query reader)))})
    (kv-describe-slot-ref reader)))

(defn kv-push! [runtime read-index summary client-id {:keys [sub-id app-id kv-table entry] prev :last :as sub}]
  (let [data (when kv-table (kv-table-data app-id kv-table))
        read-keys (get read-index kv-table #{})
        entry-val (when (and data entry) (get data (first entry)))
        ;; who reads, only for what is shown. readers can change while the read keys stay
        ;; the same, another component starting to read a key that already was
        table-readers (when data (mapv kv-describe-reader (kv-table-readers kv-table)))
        entry-readers (when (and data entry) (mapv kv-describe-reader (kv-key-readers kv-table (first entry))))]

    ;; most txs and renders don't change what a sub shows
    (when-not (and prev
                   (= sub-id (:sub-id prev))
                   (identical? data (:data prev))
                   (identical? entry-val (:entry-val prev))
                   (= read-keys (:read-keys prev))
                   (= summary (:summary prev))
                   (= table-readers (:table-readers prev))
                   (= entry-readers (:entry-readers prev)))

      (swap! kv-subs-ref assoc-in [client-id :last]
        {:sub-id sub-id
         :data data
         :entry-val entry-val
         :read-keys read-keys
         :summary summary
         :table-readers table-readers
         :entry-readers entry-readers})

      (shared/relay-msg runtime
        {:op ::m/kv-update
         :to client-id
         :sub-id sub-id
         :tables summary
         :table
         (when data
           (-> (kv-table-state kv-table data read-keys sub)
               (assoc :app-id app-id :table-readers table-readers)))
         :entry
         (when (and data entry)
           (let [key (first entry)]
             {:key key
              :found? (contains? data key)
              :read? (contains? read-keys key)
              :readers entry-readers
              :val (kv-guard entry-val kv-entry-print-limit)}))}))))

(def kv-push-tick false)
(def kv-push-timeout nil)
(def kv-push-last 0)

(defn kv-push-all! []
  (set! kv-push-tick false)
  (when kv-push-timeout
    (js/clearTimeout kv-push-timeout)
    (set! kv-push-timeout nil))
  (set! kv-push-last (js/Date.now))
  (let [subs @kv-subs-ref
        runtime (:runtime @devtools-ref)]
    (when (and runtime (seq subs))
      (let [read-index (kv-read-index)
            summary (kv-summary read-index)]
        (reduce-kv
          (fn [_ client-id sub]
            ;; this runs after every tx, a failure shouldn't take the next sub with it
            (try
              (kv-push! runtime read-index summary client-id sub)
              (catch :default e
                (js/console.warn "devtools data view update failed" e))))
          nil
          subs)))))

(defn schedule-kv-push! []
  (when (and (not kv-push-tick) (seq @kv-subs-ref))
    (let [wait (- (+ kv-push-last 100) (js/Date.now))]
      (if (pos? wait)
        ;; at most one push per 100ms. the timeout makes sure the last change goes out,
        ;; but the app tab is usually in the background while looking at the devtools,
        ;; where timers are throttled to 1s. in a busy app the next tx or render after
        ;; the 100ms gets here first and pushes right away.
        (when-not kv-push-timeout
          (set! kv-push-timeout (js/setTimeout kv-push-all! wait)))
        ;; next-tick runs after the render microtask of a tx, both go out as one update
        (do (set! kv-push-tick true)
            (rt/next-tick kv-push-all!))))))

(defn kv-sub [svc {:keys [from] :as msg}]
  (swap! kv-subs-ref assoc from (select-keys msg [:sub-id :app-id :kv-table :filter :only-read? :entry]))
  ;; someone is waiting for this one
  (set! kv-push-last 0)
  (schedule-kv-push!))

(defn kv-unsub [svc {:keys [from] :as msg}]
  (swap! kv-subs-ref dissoc from))

(defn kv-log-entry [svc {:keys [app-id kv-table key] :as msg}]
  (js/console.group "DEVTOOLS" (str kv-table) (pr-str key))
  (js/console.log (get (kv-table-data app-id kv-table) key))
  (js/console.groupEnd))

(defn tx-reporter [report]
  (let [tx-id
        (swap! tx-seq-ref inc)

        report
        (assoc report
          ::m/tx-id tx-id
          ::m/ts (js/Date.now))]

    ;; store so that devtools can query db diff on demand
    ;; FIXME: should really garbage collect these at some point, they are going to pile up quick
    (swap! tx-ref assoc tx-id report)

    ;; a tx can change tables the UI doesn't read, so it doesn't always cause a render
    (schedule-kv-push!)

    (when-some [devtools @devtools-ref]
      (let [streams-ref (:streams-ref devtools)
            streams @streams-ref]
        (when (seq streams)
          (shared/relay-msg (:runtime devtools)
            {:op ::m/stream-update
             :to streams
             :event (as-stream-event report)
             }))))))

(defn header-val? [x]
  (or (string? x) (keyword? x)))

(defn dev-log-handler [src-info log-struct]
  ;; (dev-log "SOME-LABEL" x)
  ;; (dev-log "SOME-LABEL" ::foo {:x "data"})
  ;; (dev-log ::foo {:x "data"})
  ;; trying to extra a reasonable line used in UI event log
  (let [header (vec (take-while header-val? log-struct))
        header (->> header
                    (map str)
                    (str/join " "))]

    ;; FIXME: should these also be stored in tx-ref?
    (when-some [devtools @devtools-ref]
      (let [streams-ref (:streams-ref devtools)
            streams @streams-ref]
        (when (seq streams)
          (shared/relay-msg (:runtime devtools)
            {:op ::m/stream-update
             :to streams
             :event {:type :dev-log
                     ::m/ts (js/Date.now)
                     :header header
                     :log log-struct
                     :src-info src-info}
             }))))))

;; register immediately when preload is loaded
;; otherwise may miss some events before actually connected
;; want to ideally gather all events
(set! impl/tx-reporter tx-reporter)
(set! sg/dev-log-handler dev-log-handler)

(defn now []
  (js/performance.now))

(defonce trace-id-seq (atom 0))
(defn next-trace-id []
  (swap! trace-id-seq inc))

(defonce traces-ref
  (atom {}))

(def active-trace nil)

(defn begin-trace [kind]
  (if active-trace
    (js/console.error "was already tracing?" kind active-trace)
    (set! active-trace
      {:ts (now)
       :ts-date (js/Date.now)
       :trace-id (next-trace-id)
       :kind kind
       :snapshot (component-snapshot)
       :log #js []})))

(defn add-trace [& args]
  (if-not active-trace
    (js/console.error "not tracing?" args)
    ;; logging as vectors since maps repeat keys a lot and make network messages huge
    ;; structure is fixed, so easy to turn into maps later
    (do (.push (:log active-trace) (into [(- (now) (:ts active-trace))] args))
        (dec (alength (:log active-trace))))))

(defn add-components [{:keys [snapshot snapshot-after] :as trace}]
  ;; only add component infos for components actually used in this trace
  ;; avoids sending too much noise
  ;; needs to send with every trace so we have actual definition used
  ;; and not something maybe outdated (via redef at REPL/hot-reload)
  (let [used
        (-> #{}
            (into (map :component-name) (vals snapshot))
            (into (map :component-name) (vals snapshot-after)))]

    (assoc trace
      :components
      (reduce-kv
        (fn [m component-name config]
          (if-not (contains? used component-name)
            m
            (assoc m
              component-name
              (assoc (.-debug-info config)
                :component-name (.-component-name config)
                :slots
                (->> (.-slots config)
                     (map-indexed
                       (fn [idx info]
                         (assoc (.-debug-info info) :idx idx)))
                     (vec))))))

        {}
        @comp/components-ref))))

(defn broadcast-trace [trace])

(defn end-trace []
  (let [trace
        (-> active-trace
            (assoc :ts-end (now) :snapshot-after (component-snapshot))
            (add-components)
            (update :log vec))]

    (swap! traces-ref assoc (:trace-id trace) trace)

    (set! active-trace nil)

    ;; renders mount and unmount readers, which changes what the data view highlights
    (schedule-kv-push!)

    ;; sending snapshots can be done async. mostly to separate sending
    ;; from actual work done. tracing overhead so far is already too big
    (js/setTimeout #(broadcast-trace trace) 25)))


(set! js/shadow.grove.trace
  #js {:component_create
       (fn component_create [component]
         (add-trace :component-create (.-instance-id component)))

       :component_dom_sync
       (fn component_dom_sync [component dirty-from-args dirty-slots]
         (add-trace :component-dom-sync (.-instance-id component) dirty-from-args dirty-slots))

       :component_dom_sync_done
       (fn component_dom_sync_done [component t]
         (add-trace :component-dom-sync-done (.-instance-id component) t))

       :component_slot_cleanup
       (fn component_slot_cleanup [component slot-idx]
         (add-trace :component-slot-cleanup (.-instance-id component) slot-idx))

       :component_slot_cleanup_done
       (fn component_slot_cleanup_done [component slot-idx t]
         (add-trace :component-slot-cleanup-done t))

       :component_run_slot
       (fn component_run_slot [component slot-idx]
         (let [t (add-trace :component-run-slot (.-instance-id component) slot-idx)]
           [t (aget (.-slot-values component) slot-idx)]))

       :component_run_slot_done
       (fn component_run_slot_done [component slot-idx [t oval]]
         (let [nval (aget (.-slot-values component) slot-idx)]
           (add-trace :component-run-slot-done t (= oval nval) rt/*claimed* rt/*ready*)))

       :component_after_render_cleanup
       (fn component_after_render_cleanup [component]
         (add-trace :component-after-render-cleanup (.-instance-id component)))

       :component_after_render_cleanup_done
       (fn component_after_render_cleanup_done [component t]
         (add-trace :component-after-render-cleanup-done t))

       :component_destroy
       (fn component_destroy [component]
         (add-trace :component-destroy (.-instance-id component)))

       :component_destroy_done
       (fn component_destroy_done [component t]
         (add-trace :component-destroy-done t))

       :component_work
       (fn component_work [component dirty-slots]
         (add-trace :component-work (.-instance-id component) dirty-slots))

       :component_work_done
       (fn component_work_done [component t]
         (add-trace :component-work-done t))

       :component_render
       (fn component_render [component updated-slots]
         (add-trace :component-render (.-instance-id component) updated-slots))

       :component_render_done
       (fn component_render_done [component t]
         (add-trace :component-render-done t))

       :run_action
       (fn run_action [scheduler trigger]
         (add-trace :run-action trigger))

       :run_action_done
       (fn run_action_done [scheduler trigger t]
         (add-trace :run-action-done t))

       :run_work
       (fn run_work [scheduler trigger]
         (add-trace :run-work trigger))

       :run_work_done
       (fn run_work_done [scheduler trigger t]
         (add-trace :run-work-done t))

       :run_microtask
       (fn run_microtask [scheduler trigger]
         (begin-trace :microtask))

       :run_microtask_done
       (fn run_microtask_done [scheduler trigger t]
         (end-trace))

       :render_root
       (fn render_root []
         ;; it is valid if render is called while in render
         ;; might be in intermediate step with 3rd party stuff rendering some embedded things
         (when-not active-trace
           (begin-trace :render-root)))

       :render_root_done
       (fn render_root_done [t]
         (when t
           ;; only end when we started it
           (end-trace)))

       :render_direct
       (fn render_direct []
         ;; used by managed-root, which my either update during a regular update cycle
         ;; or on its own time, which we still want to trace, so join if active otherwise state
         (when-not active-trace
           (begin-trace :render-direct)))

       :render_direct_done
       (fn render_direct_done [t]
         (when t
           ;; only end when we started it
           (end-trace)))
       })

(set! js/shadow.grove.components -handle-error-state!
  (fn [^ManagedComponent component ex]
    ;; make it retry next render no matter what
    (.set-render-required! component)

    (ap/update! (.-root component)
      (<< [:div (.. component -config -component-name) " failed to run properly!"]))

    (js/console.error "component failed run properly!" component)
    (js/console.error ex)
    ))


(cljs-shared/add-plugin! ::tree #{:obj-support}
  (fn [{:keys [runtime obj-support] :as env}]
    (cljs-shared/add-transit-writers! runtime
      ;; FIXME: installing a default handler may not be a good idea
      ;; It is often way better to error out early, since likely it was a value what wasn't supposed to be sent anyway
      ;; but for our case grove tx events may contain anything
      ;; and UI just wants to display them in some way
      {"default"
       (transit/write-handler
         (fn [x]
           "transit-unknown")
         (fn [x]
           ;; fallback to EDN, so that unknown things do not error out
           ;; edn prints everything, so more forgiving for types we don't care to restore anyway
           (pr-str x)))})

    (let [streams-ref
          (atom #{})

          svc
          {:runtime runtime
           :obj-support obj-support
           :streams-ref streams-ref
           :devtools #{}}]

      (reset! devtools-ref svc)

      (p/add-extension runtime
        ::tree
        {:ops
         {::m/take-snapshot #(take-snapshot svc %)
          ::m/get-tx-diff #(get-tx-diff svc %)
          ::m/get-runtimes #(get-runtimes svc %)
          ::m/get-db-copy #(get-db-copy svc %)
          ::m/stream-sub #(stream-sub svc %)
          ::m/request-log #(request-log svc %)
          ::m/highlight-component #(highlight-component svc %)
          ::m/remove-highlight #(remove-highlight svc %)
          ::m/kv-sub #(kv-sub svc %)
          ::m/kv-unsub #(kv-unsub svc %)
          ::m/kv-log-entry #(kv-log-entry svc %)}

         ;; a devtools UI that went away without kv-unsub
         :on-client-not-found
         (fn [{:keys [client-id]}]
           (swap! kv-subs-ref dissoc client-id))

         :on-disconnect
         (fn []
           (reset! streams-ref #{})
           (reset! kv-subs-ref {})
           (set! impl/tx-reporter nil)
           (set! broadcast-trace (fn [trace])))

         :on-welcome
         (fn []
           ;; :on-disconnect removed it, a reconnect after a server restart needs it back
           (set! impl/tx-reporter tx-reporter)

           (js/console.log "shadow-grove devtools ready!"
             (str (client-env/get-url-base)
                  "/classpath/shadow/grove/devtools.html?runtime="
                  (:client-id @(:state-ref runtime))))


           ;; FIXME: should the UI opt-in for these first?
           (doseq [trace (vals @traces-ref)]
             (shared/relay-msg runtime
               {:op ::m/work-finished
                ;; send to all runtimes matching query, so we do not have to keep that info replicated on this end
                :to-query [:eq :type :shadow.grove.devtools]
                :work-snapshot trace}))

           ;; FIXME: this is dirty, but ensures messages are only sent when runtime is actually connected
           ;; this is handled poorly currently, but not cleaning it up now
           (set! broadcast-trace
             (fn [trace]
               (shared/relay-msg runtime
                 {:op ::m/work-finished
                  ;; send to all runtimes matching query, so we do not have to keep that info replicated on this end
                  :to-query [:eq :type :shadow.grove.devtools]
                  :work-snapshot trace})))
           )
         ;; :on-tool-disconnect #(tool-disconnect svc %)
         })
      svc))
  (fn [{:keys [runtime] :as svc}]
    (p/del-extension runtime ::tree)))


(defn find-owning-component [e]
  (loop [current (.. e -target)]
    (if (and (= "#comment" (.-nodeName current))
             (.-shadow$instance current))
      (.-shadow$instance current)
      (if-some [prev (.-previousSibling current)]
        (recur prev)
        (when-some [parent (.-parentNode current)]
          (recur parent))))))

;; doing this with direct DOM interop to avoid devtools grove stuff showing up in devtools
(defn select-element []
  (let [highlight-ref
        (atom nil)

        mouse-hook*
        (fn mouse-hook* [e]
          (when-some [component (find-owning-component e)]
            (when-not (identical? component @highlight-ref)
              (reset! highlight-ref component)
              (outline-component component)
              )))

        mouse-hook
        (gfn/throttle mouse-hook* 100)

        open-in-devtools
        (fn open-in-devtools []
          (when-some [selected @highlight-ref]
            (let [{:keys [runtime devtools] :as svc} @devtools-ref]
              (if (seq devtools)
                (shared/relay-msg runtime {:op ::m/focus-component
                                           :to devtools
                                           :component (.-instance-id selected)
                                           :snapshot (take-snapshot* svc)})

                (let [url (str (client-env/get-url-base) "/classpath/shadow/grove/devtools.html")]
                  (js/console.warn "DEVTOOLS NOT OPEN, open" url)
                  ;; FIXME: make this configurable somehow? could open iframe?
                  ;; FIXME: make actual browser extension?
                  )))))

        all-your-clicks
        (fn [e]
          (.preventDefault e)
          (.stopPropagation e)
          (.remove border-highlight)
          (js/document.removeEventListener "mousemove" mouse-hook)
          (open-in-devtools)
          )]

    (js/document.body.appendChild border-highlight)
    (js/document.addEventListener "mousemove" mouse-hook)
    (js/window.addEventListener "click" all-your-clicks #js {:once true :capture true})))

(defonce keyboard-hook
  (do (js/window.addEventListener
        "keydown"
        (fn [e]
          ;; FIXME: make this customizable somehow?
          ;; ctrl+shift+s
          (when (and (= (.-key e) "S") (.-ctrlKey e) (.-shiftKey e))
            (select-element))))
      true))

