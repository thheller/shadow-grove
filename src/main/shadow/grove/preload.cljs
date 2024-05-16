(ns shadow.grove.preload
  (:require
    [clojure.string :as str]
    [cognitect.transit :as transit]
    [goog.functions :as gfn]
    [goog.style :as gs]
    [clojure.core.protocols :as cp]
    [shadow.grove.db.ident :as ident]
    [shadow.grove.impl :as impl]
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

(defonce devtools-ref (atom nil))

(defn extension-info []
  (doto (client-env/devtools-info)
    (unchecked-set "client_id" (shared/get-client-id (:runtime @devtools-ref)))
    ))


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


(def traces (js/Array.))

(defn notify-work-finished [{:keys [runtime] :as svc} trace-array]
  ;; FIXME: should the UI opt-in for these first?

  (let [{:keys [devtools]} @devtools-ref]

    ;; devtools can request these when wanted
    ;; sending them always leads to far too much data on the websocket
    ;; and causes intermittent disconnects since it can't keep up
    (.push traces trace-array)

    (when (seq devtools)
      (shared/relay-msg runtime
        {:op ::m/work-finished
         :to devtools
         :work-tasks (alength trace-array)}))))


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

(defn as-stream-event [{::impl/keys [tx-info] :as entry}]
  (-> entry
      (select-keys
        [:ts :tx-id :app-id :event :fx])
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

(defn make-tx-diff [{::impl/keys [tx-info] :as tx}]
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

(defn tx-reporter [report]
  (let [tx-id
        (swap! tx-seq-ref inc)

        report
        (assoc report
          :tx-id tx-id
          :ts (js/Date.now))]

    ;; store so that devtools can query db diff on demand
    ;; FIXME: should really garbage collect these at some point, they are going to pile up quick
    (swap! tx-ref assoc tx-id report)

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
                     :ts (js/Date.now)
                     :header header
                     :log log-struct
                     :src-info src-info}
             }))))))

;; register immediately when preload is loaded
;; otherwise may miss some events before actually connected
;; want to ideally gather all events
(set! impl/tx-reporter tx-reporter)
(set! sg/dev-log-handler dev-log-handler)

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
          ::runtime-notify #(runtime-notify svc %)
          ::clients #(clients svc %)
          ::m/get-tx-diff #(get-tx-diff svc %)
          ::m/get-runtimes #(get-runtimes svc %)
          ::m/get-db-copy #(get-db-copy svc %)
          ::m/stream-sub #(stream-sub svc %)
          ::m/request-log #(request-log svc %)
          ::m/highlight-component #(highlight-component svc %)
          ::m/remove-highlight #(remove-highlight svc %)}

         :on-disconnect
         (fn []
           (reset! streams-ref #{})
           (set! impl/tx-reporter nil)
           (set! sg/work-finish-trigger nil))

         :on-welcome
         (fn []
           (js/console.log "shadow-grove devtools ready!"
             (str (client-env/get-url-base)
                  "/classpath/shadow/grove/devtools.html?runtime="
                  (:client-id @(:state-ref runtime))))
           (set! sg/work-finish-trigger #(notify-work-finished svc %))

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

