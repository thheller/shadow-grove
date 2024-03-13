(ns shadow.grove.devtools.ui
  {:dev/always true}
  (:require
    [shadow.arborist.attributes :as attrs]
    [shadow.grove.db :as db]
    [shadow.grove.runtime :as rt]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools :as-alias m]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.devtools.edn :as edn]
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

(defc ui-slot [{:keys [type name value] :as slot} idx]
  (bind expanded-ref (atom false))
  (bind expanded (sg/watch expanded-ref))

  (render
    (let [{:keys [preview edn]} value]
      (<< [:tr {:class (css :mb-2 {:border "1px solid #ddd"})
                :on-click #(swap! expanded-ref not)}
           [:td {:class (css :p-1 :pr-2 :font-semibold :whitespace-nowrap)} name]
           [:td {:class (css :w-full)}
            [:div {:class (css :font-mono :overflow-auto)
                   :style/max-height (when-not expanded "140px")}
             (if preview
               (<< [:div {:class (css :font-bold :p-2)} "FIXME: value too large to print"]
                   [:div {:class (css :p-2 :truncate :whitespace-nowrap)} preview])
               (edn/render-edn-str edn))]
            ]]))))

(defc ui-detail [{:keys [name args slots] :as item}]
  (render
    (let [$section-label (css :font-semibold :text-sm)]
      (<< [:div {:class (css :text-xs :flex-1 :px-2 :pb-2 {:border-left "10px solid #eee"})}
           ;; [:div {:class (css :py-2 :font-bold :text-2xl)} name]
           (when (seq args)
             (<< [:div {:class (css :pb-2)}
                  [:div {:class $section-label} "Arguments"]
                  [:table {:class (css :w-full)}
                   [:tbody
                    (sg/simple-seq args ui-slot)]]]))

           (when (seq slots)
             (<< [:div {:class (css :w-full)}
                  [:div {:class $section-label} "Slots"]
                  [:div {:class (css :overflow-auto {:border-right "1px solid #eee"})}
                   [:table {:class (css :w-full {:border-right "0"})}
                    [:tbody
                     (sg/simple-seq slots ui-slot)]]]]))]))))

(declare ui-node)

(defn ui-node-children [runtime-ident item]
  (sg/simple-seq (:children item) #(ui-node runtime-ident %1)))

(defc ui-node [runtime-ident item]
  (bind selected
    (sg/db-read ::m/selected))

  (render
    (if-not item
      (<< [:div "nil"])
      (case (:type item)
        shadow.arborist/TreeRoot
        (<< [:div
             [:div {:class (css :font-mono)} (:container item)]
             [:div {:class (css :pl-2)}
              (ui-node-children runtime-ident item)]])

        shadow.arborist.common/ManagedRoot
        (<< [:div
             [:div "Managed Root"]
             [:div {:class (css :pl-2)}
              (ui-node-children runtime-ident item)]])

        shadow.arborist.common/ManagedText
        nil

        shadow.arborist.collections/SimpleCollection
        (<< [:div
             [:div (str "simple-seq [" (count (:children item)) "]")]
             [:div {:class (css :pl-2 {:border-left "1px solid #eee"})}
              (ui-node-children runtime-ident item)]])

        shadow.arborist.collections/KeyedCollection
        (<< [:div
             [:div (str "keyed-seq [" (count (:children item)) "]")]
             [:div {:class (css :pl-2 {:border-left "1px solid #eee"})}
              (ui-node-children runtime-ident item)]])

        shadow.grove.ui.suspense/SuspenseRoot
        (<< [:div
             [:div "Suspense Root"]
             [:div {:class (css :pl-2)}
              (ui-node-children runtime-ident item)]])

        shadow.grove.ui.portal/PortalNode
        (<< [:div
             [:div "Portal"]
             [:div {:class (css :pl-2)}
              (ui-node-children runtime-ident item)]])

        shadow.grove.components/ManagedComponent
        (<< [:div
             [:div {:class (css :font-semibold :cursor-pointer)
                    :on-click {:e ::select! :item (:instance-id item)}
                    :on-mouseout {:e ::remove-highlight! :runtime runtime-ident :item (:instance-id item)}
                    :on-mouseenter {:e ::highlight! :runtime runtime-ident :item (:instance-id item)}}
              (:name item)]
             (when (contains? selected (:instance-id item))
               (ui-detail item))
             [:div {:class (css :pl-2 {:border-left "1px solid #eee"})}
              (ui-node-children runtime-ident item)]])

        shadow.arborist.fragments/ManagedFragment
        (ui-node-children runtime-ident item)
        #_(<< [:div
               [:div (str (:ns item) "/L" (:line item) "-C" (:column item))]
               (ui-node-children item)])

        (<< [:div (or (str (:type item)) (pr-str (dissoc item :children)))]
            (when (:children item)
              (<< [:div {:class (css :pl-2)}
                   (ui-node-children runtime-ident item)]))
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
       :v (if (contains? selected item)
            (disj selected item)
            (conj selected item))}))

  (event ::deselect! [env ev e]
    (.preventDefault e)
    (sg/run-tx env
      {:e :form/set-attr
       :a [runtime-ident :selected]
       :v nil}))

  (render
    (<< [:div {:class (css :flex-1)}
         [:div {:class (css :bg-white :p-4)}
          [:button {:class (css :cursor-pointer :text-lg :block :border :px-4 :rounded :whitespace-nowrap :bg-blue-200 [:hover :bg-blue-400])
                    :on-click ::load-snapshot!} "Update Snapshot"]]
         [:div {:class (css :text-sm :px-4)}
          (sg/simple-seq snapshot #(ui-node runtime-ident %1))]])))

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

(defc ui-root []
  (bind runtimes
    (sg/db-read
      (fn [env db]
        (->> (db/all-of db ::m/runtime)
             (filter #(contains? (:supported-ops %) :shadow.grove.preload/take-snapshot))
             (remove :disconnected)
             (sort-by :client-id)
             (vec)))))

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