(ns shadow.grove.devtools.ui
  {:dev/always true}
  (:require
    [shadow.arborist.attributes :as attrs]
    [shadow.grove.db :as db]
    [shadow.grove.runtime :as rt]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools :as-alias m]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.ui.edn :as edn]
    [shadow.grove :as sg :refer (defc << css)]))

(defn form-set-attr!
  {::ev/handle :form/set-attr}
  [env {:keys [a v]}]
  (update env :db assoc-in (if (keyword? a) [a] a) v))

(defn set-snapshot!
  {::ev/handle ::set-snapshot!}
  [env {:keys [ident call-result] :as msg}]
  (let [snapshot (:snapshot call-result)]
    (assoc-in env [:db ident :snapshot] snapshot)
    ))

(defn load-snapshot [env {:keys [client-id] :as runtime}]
  (relay-ws/call! @(::rt/runtime-ref env)
    {:op :shadow.grove.preload/take-snapshot
     :to client-id}
    {:e ::set-snapshot!
     :ident (:db/ident runtime)}))

(defn highlight!
  {::ev/handle ::highlight!}
  [env {:keys [item runtime] :as ev} e]
  (when (contains? (get-in env [:db runtime :supported-ops]) ::m/highlight-component)
    (sg/queue-fx env
      :relay-send
      [{:op ::m/highlight-component
        :component item
        :to (get-in env [:db runtime :client-id])}])))

(defn request-log!
  {::ev/handle ::request-log!}
  [env {:keys [type name idx instance-id runtime] :as ev} e]
  (when (contains? (get-in env [:db runtime :supported-ops]) ::m/request-log)
    (sg/queue-fx env
      :relay-send
      [{:op ::m/request-log
        :type type
        :idx idx
        :component instance-id
        :name name
        :to (get-in env [:db runtime :client-id])}])))

(defn remove-highlight!
  {::ev/handle ::remove-highlight!}
  [env {:keys [item runtime] :as ev} e]
  (when (contains? (get-in env [:db runtime :supported-ops]) ::m/highlight-component)
    (sg/queue-fx env
      :relay-send
      [{:op ::m/remove-highlight
        :to (get-in env [:db runtime :client-id])}])))

(def $container
  (css :pl-2))

(defc ui-slot [item {:keys [type name value] :as slot} idx type]
  (bind expanded-ref (atom false))
  (bind expanded (sg/watch expanded-ref))

  (render
    (let [{:keys [preview edn]} value]
      (<< [:div {:class (css :py-0.5 :pr-2 :font-semibold :whitespace-nowrap :border-t :cursor-help)
                 :title (str "click to log " name " in runtime console")
                 :on-click {:e ::request-log! :name name :instance-id (:instance-id item) :idx idx :type type}} name]
          [:div {:class (css :py-0.5 :font-mono :overflow-auto :border-t)
                 :style/max-height (when-not expanded "142px")
                 :on-click #(swap! expanded-ref not)}
           (if preview
             (<< [:div
                  [:div {:class (css :font-bold :p-2)} "FIXME: value too large to print"]
                  [:div {:class (css :p-2 :truncate :whitespace-nowrap)} preview]])
             (edn/render-edn-str edn))]
          ))))

(defc ui-detail [{:keys [args slots] :as item}]
  (render
    (let [$section-label (css :font-semibold :py-2 {:grid-column "span 2"})]
      (<< [:div {:class (css :pl-2
                          :overflow-hidden
                          {:border-left "6px solid #eee"
                           :display "grid"
                           :grid-template-columns "min-content minmax(25%, auto)"
                           :grid-row-gap "0"})}
           (when (seq args)
             (<< [:div {:class $section-label} "Arguments"]
                 (sg/simple-seq args #(ui-slot item %1 %2 :arg))))

           (when (seq slots)
             (<< [:div {:class $section-label} "Slots"]
                 (sg/simple-seq slots #(ui-slot item %1 %2 :slot))))]))))

(declare ui-node)

(defn ui-node-children [ctx item]
  (sg/simple-seq (:children item)
    (fn [item idx]
      (ui-node (update ctx :path conj idx) item))))

(defc ui-node [ctx item]
  (bind selected
    (sg/db-read ::m/selected))

  (render
    (if-not item
      (<< [:div "nil"])
      (case (:type item)
        shadow.arborist/TreeRoot
        (<< [:div {:class (css :font-mono)} (:container item)]
            (ui-node-children ctx item))

        shadow.arborist.common/ManagedRoot
        (<< [:div "Managed Root"]
            [:div {:class (css :pl-2)}
             (ui-node-children ctx item)])

        shadow.arborist.common/ManagedText
        nil

        shadow.arborist.collections/SimpleCollection
        (<< [:div (str "simple-seq [" (count (:children item)) "]")]
            [:div {:class (css :pl-2 {:border-left "1px solid #eee"})}
             (ui-node-children ctx item)])

        shadow.arborist.collections/KeyedCollection
        (<< [:div (str "keyed-seq [" (count (:children item)) "]")]
            [:div {:class (css :pl-2 {:border-left "1px solid #eee"})}
             (ui-node-children ctx item)])

        shadow.grove.ui.suspense/SuspenseRoot
        (<< [:div "Suspense Root"]
            [:div {:class (css :pl-2)}
             (ui-node-children ctx item)])

        shadow.grove.ui.portal/PortalNode
        (<< [:div "Portal"]
            [:div {:class (css :pl-2)}
             (ui-node-children ctx item)])

        shadow.grove.components/ManagedComponent
        (let [comp-ctx (update ctx :path conj (:name item))]
          (<< [:div {:class (css :font-semibold :cursor-pointer :whitespace-nowrap)
                     :on-click {:e ::select! :item (:path comp-ctx)}
                     :on-mouseout {:e ::remove-highlight! :runtime (:runtime-ident ctx) :item (:instance-id item)}
                     :on-mouseenter {:e ::highlight! :runtime (:runtime-ident ctx) :item (:instance-id item)}}
               (:name item)]
              ;; can't use :instance-id for tracking which component should show details
              ;; as every hot-reload re-creates the component, thus changing the instance id
              ;; instead we track which one we were looking at via the path in the tree
              ;; which might still change and close the one we were looking at
              ;; but can't think of another way to do this currently
              (when (contains? selected (:path comp-ctx))
                (ui-detail item))
              [:div {:class (css :pl-2 {:border-left "1px solid #eee"})}
               (ui-node-children comp-ctx item)]))

        shadow.arborist.fragments/ManagedFragment
        (ui-node-children ctx item)
        #_(<< [:div
               [:div (str (:ns item) "/L" (:line item) "-C" (:column item))]
               (ui-node-children item)])

        (<< [:div (or (str (:type item)) (pr-str (dissoc item :children)))]
            (when (:children item)
              (<< [:div {:class (css :pl-2)}
                   (ui-node-children ctx item)]))
            )))))

(defc ui-runtime [^:stable runtime-ident]
  (bind {:keys [snapshot] :as runtime}
    (sg/db-read runtime-ident))

  (bind selected
    (sg/db-read ::m/selected))

  (effect :mount [env]
    (when-not snapshot
      (load-snapshot env runtime)))

  (event ::load-snapshot! [env ev e]
    (load-snapshot env runtime))

  (event ::select! [env {:keys [item] :as ev} e]
    (.preventDefault e)
    (sg/run-tx env
      {:e :form/set-attr
       :a ::m/selected
       :v (cond
            (contains? selected item)
            (disj selected item)

            (false? (.-ctrlKey e))
            #{item}

            :else
            (conj selected item))}))

  (event ::deselect! [env ev e]
    (.preventDefault e)
    (sg/run-tx env
      {:e :form/set-attr
       :a [runtime-ident :selected]
       :v nil}))

  (event ::request-log! [env ev e]
    (sg/dispatch-up! env (assoc ev :runtime runtime-ident)))

  (bind ctx
    {:runtime-ident runtime-ident
     :path []
     :level 0})

  (render
    (<< [:div {:class (css :flex-1 :text-sm)}
         #_[:div {:class (css :bg-white :p-2)}
            [:button {:class (css :cursor-pointer :text-lg :block :border :px-4 :rounded :whitespace-nowrap :bg-blue-200 [:hover :bg-blue-400])
                      :on-click ::load-snapshot!} "Update Snapshot"]]
         [:div {:class (css :pl-2 :py-2)}
          (sg/simple-seq snapshot #(ui-node ctx %1))]])))

(attrs/add-attr :form/value
  (fn [env ^js node oval nval]
    (set! node -grove$formValue nval)))

(defn get-form-value [^js x]
  (cond
    (instance? js/Event x)
    (get-form-value (-> x .-target))

    (instance? js/HTMLSelectElement x)
    (let [selected (.-selectedOptions x)]
      (if (.-multiple x)
        (->> selected (array-seq) (map get-form-value) (vec))
        (get-form-value (aget selected 0))))

    (instance? js/HTMLElement x)
    (-> x .-grove$formValue)
    :else
    (do (js/console.warn "failed to get form value from" x)
        (throw (ex-info "failed to get form value from unknown object" {:x x})))
    ))

(attrs/add-attr :form/field
  (fn [env ^js node oval nval]
    (let [old-fn (.-grove$formField node)]
      (when old-fn
        (.removeEventListener node "change" old-fn))


      ;; FIXME: node is of known type
      ;; add specific handlers per tag instead of get-form-value having to figure it out
      ;; might be better to use input even for input elements
      (let [ev-fn (fn [e]
                    (let [val (get-form-value node)]
                      (sg/run-tx env {:e :form/set-attr :a nval :v val})))]
        (set! node -grove$formField ev-fn)
        (.addEventListener node "change" ev-fn)
        ))))

(defn suitable-runtimes [env db]
  (->> (db/all-of db ::m/runtime)
       (filter #(contains? (:supported-ops %) :shadow.grove.preload/take-snapshot))
       (remove :disconnected)
       (sort-by :client-id)
       (vec)))

(defc ui-root []
  (bind runtimes
    (sg/db-read suitable-runtimes))

  (bind selected
    (sg/db-read ::m/selected-runtime))

  (render
    (cond
      (empty? runtimes)
      (<< [:div {:class (css :p-4 :text-lg)}
           "no runtimes found, need a runtime with shadow.grove.preload loaded!"])

      selected
      (ui-runtime selected)

      :else
      (<< [:div {:class (css :p-4)}
           [:h1 {:class (css :font-bold :text-2xl :pb-4)} "Runtimes"]
           (sg/simple-seq runtimes
             (fn [{:keys [client-id client-info supported-ops] :as runtime}]
               (<< [:div {:class (css :cursor-pointer :border :p-2 :mb-2 [:hover :border-green-500])
                          :on-click
                          {:e :form/set-attr
                           :a [::m/selected-runtime]
                           :v (:db/ident runtime)}}
                    (str "#" client-id " - " (pr-str (dissoc client-info :since :proc-id)))
                    ])))
           ]
          ))))