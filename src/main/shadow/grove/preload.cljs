(ns shadow.grove.preload
  (:require
    [goog.functions :as gfn]
    [goog.style :as gs]
    [clojure.core.protocols :as cp]
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
    [shadow.grove :as sg]
    [shadow.grove.db :as db]
    [shadow.grove.db.ident :as db-ident]
    [shadow.grove.runtime :as rt]
    [shadow.grove.ui.portal]
    [shadow.grove.ui.suspense]
    [shadow.grove.ui.loadable :as loadable]
    [shadow.grove.ui.vlist :as vlist]
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

(extend-type db-ident/Ident
  cp/Datafiable
  (datafy [this]
    [(db/ident-key this)
     (db/ident-val this)]))

(deftype IdentFormatter []
  Object
  (header [this obj]
    (when (db/ident? obj)
      #js ["span" "#gbd/ident ["
           #js ["object" #js {:object (db/ident-key obj)}]
           " "
           #js ["object" #js {:object (db/ident-val obj)}]
           "]"]))

  (hasBody [this obj]
    false)
  (body [this m]
    nil))

(when-let [^js f js/goog.global.devtoolsFormatters]
  (doto f
    (.push (IdentFormatter.))
    ))


(defn get-databases [{:keys [runtime]} msg]
  (shared/reply runtime msg
    {:op :db/list-databases
     ;; just keywords or more details? don't actually have any?
     :databases
     (-> (keys @rt/known-runtimes-ref)
         (set))}))

(defn get-tables [{:keys [runtime]} {:keys [db] :as msg}]
  (let [env-ref (get @rt/known-runtimes-ref db)
        data-ref (get @env-ref ::rt/data-ref)
        data @data-ref
        {::db/keys [ident-types]} (meta data)]

    (shared/reply runtime msg
      {:op :db/list-tables
       ;; just keywords or more details? don't actually have any?
       :tables (conj ident-types :db/globals)})))

(defn get-table-columns [{:keys [runtime]} {:keys [db table] :as msg}]
  (let [env-ref (get @rt/known-runtimes-ref db)
        data-ref (get @env-ref ::rt/data-ref)
        db @data-ref

        ;; FIXME: likely doesn't need all rows, should just take a random sample
        known-keys-of-table
        (->> (db/all-of db table)
             (mapcat keys)
             (set))]

    (shared/reply runtime msg
      {:op :db/list-table-columns
       :columns known-keys-of-table})))

(defn get-rows [{:keys [runtime]} {:keys [db table offset count] :as msg}]
  (let [env-ref (get @rt/known-runtimes-ref db)
        data-ref (get @env-ref ::rt/data-ref)
        data @data-ref
        rows
        (->> (db/all-of data table)
             (sort-by :db/ident)
             (vec))]

    ;; FIXME: slice data, don't send everything

    (shared/reply runtime msg
      {:op :db/list-rows
       :rows rows})))

(defn get-entry
  [{:keys [runtime]}
   {:keys [db table row] :as msg}]
  (let [env-ref (get @rt/known-runtimes-ref db)
        data-ref (get @env-ref ::rt/data-ref)
        db @data-ref
        ident (if (= table :db/globals) row (db/make-ident table row))
        val (get db ident)]

    (shared/reply runtime msg
      {:op :db/entry :row val})))

(cljs-shared/add-plugin! ::db-explorer #{}
  (fn [{:keys [runtime] :as env}]
    (let [svc
          {:runtime runtime}]

      ;; maybe just return the ops?
      ;; dunno if this extra layer is needed
      (p/add-extension runtime
        ::db-explorer
        {:ops
         {:db/get-databases #(get-databases svc %)
          :db/get-tables #(get-tables svc %)
          :db/get-table-columns #(get-table-columns svc %)
          :db/get-rows #(get-rows svc %)
          :db/get-entry #(get-entry svc %)}
         ;; :on-tool-disconnect #(tool-disconnect svc %)
         })
      svc))
  (fn [{:keys [runtime] :as svc}]
    (p/del-extension runtime ::db-explorer)))

(set! *warn-on-infer* false)

(defn as-embedded-value [{:keys [obj-support] :as ctx} value]
  ;; sending as edn-limit, so that large values don't blow everything up
  ;; we don't have transit handlers for every possible thing that may show up
  ;; edn is more forgiving here
  ;; FIXME: figure out a good max size? make this configurable?
  (let [[limit? edn] (lw/pr-str-limit value 10000)]
    (if limit?
      ;; FIXME: UI can't handle this yet, so no need to send for now
      {:preview edn #_#_:oid (obj-support/register obj-support value {})}
      {:edn edn})))

(extend-protocol dp/ISnapshot
  default
  (snapshot [this ctx]
    (js/console.log "unknown snapshot" (pr-str (type this)) this)
    {:type (pr-str (type this))})

  shadow.arborist/TreeRoot
  (snapshot [this ctx]
    {:type 'shadow.arborist/TreeRoot
     :container (let [container (.-container this)]
                  (str "[:" (.toLowerCase (.-tagName container))
                       (when-some [id (.-id container)]
                         (str "#" id))
                       " ...]")
                  )
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

  shadow.arborist.collections/KeyedCollection
  (snapshot [this ctx]
    {:type 'shadow.arborist.collections/KeyedCollection
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

  vlist/VirtualList
  (snapshot [this ctx]
    {:type 'shadow.grove.ui.vlist/VirtualList
     :children [(dp/snapshot (.-node (.-box-root this)) ctx)]})

  shadow.grove.components/ManagedComponent
  (snapshot [this ctx]
    {:type 'shadow.grove.components/ManagedComponent
     :name (-> this .-config .-component-name)
     :instance-id (.-instance-id this)
     :args
     (let [args (.-args this)
           args-config (-> this .-config .-debug-info (:args))]
       (reduce-kv
         (fn [v idx arg-name]
           (conj v {:name arg-name :value (as-embedded-value ctx (nth args idx))}))
         []
         args-config))
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
  (->> (js/document.querySelectorAll "[data-grove-root]")
       (array-seq)
       (map #(.-sg$root ^js %))
       (map #(dp/snapshot % env))
       (vec)))

(defn take-snapshot [{:keys [runtime] :as env} msg]
  (shared/reply runtime msg
    {:op ::snapshot
     :snapshot
     (->> (js/document.querySelectorAll "[data-grove-root]")
          (array-seq)
          (map #(.-sg$root ^js %))
          (map #(dp/snapshot % env))
          (vec))}))

(defonce devtools-ref (atom nil))

(defn runtime-notify [svc {:keys [event-op client-id client-info] :as msg}]
  (case event-op
    :client-connect
    (swap! devtools-ref update :devtools conj client-id)
    :client-disconnect
    (swap! devtools-ref update :devtools disj client-id)
    ))

(defn clients [svc {:keys [clients] :as msg}]
  (swap! devtools-ref assoc :devtools (->> clients (map :client-id) (set))))

(defonce border-highlight
  (doto (js/document.createElement "div")
    (gs/setStyle
      #js {"border" "2px solid red"
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

(cljs-shared/add-plugin! ::tree #{:obj-support}
  (fn [{:keys [runtime obj-support] :as env}]
    (let [svc {:runtime runtime :obj-support obj-support :devtools #{}}]

      (reset! devtools-ref svc)

      (p/add-extension runtime
        ::tree
        {:ops
         {::take-snapshot #(take-snapshot svc %)
          ::runtime-notify #(runtime-notify svc %)
          ::clients #(clients svc %)
          ::m/highlight-component #(highlight-component svc %)
          ::m/remove-highlight #(remove-highlight svc %)}
         :on-welcome
         (fn []
           (shared/relay-msg runtime
             {:op :request-clients
              :notify true
              :reply-op ::clients
              :notify-op ::runtime-notify
              :query [:eq :type :shadow.grove.devtools]
              }))
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

