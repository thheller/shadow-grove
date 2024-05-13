(ns shadow.grove.devtools.ui.tree
  (:require
    [shadow.grove.devtools :as-alias m]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.events :as ev]
    [shadow.grove.runtime :as rt]
    [shadow.grove.ui.edn :as edn]))

(defn set-snapshot!
  {::ev/handle ::m/set-snapshot!}
  [tx {:keys [target-id call-result] :as msg}]
  (let [snapshot (:snapshot call-result)]
    (assoc-in tx [::m/target target-id :snapshot] snapshot)
    ))

(defn select-target!
  {::ev/handle ::m/select-target!}
  [tx {:keys [target-id] :as ev}]
  (assoc-in tx [:db ::m/selected-target] target-id))

(defn set-selection!
  {::ev/handle ::m/set-selection!}
  [tx {:keys [target-id v] :as ev}]
  (assoc-in tx [::m/target target-id :selected] v))

(defn open-result!
  {::ev/handle ::m/open-result!}
  [tx msg]
  (js/console.log "open result" msg)
  tx)

(defn open-in-editor!
  {::ev/handle ::m/open-in-editor!}
  [tx {:keys [file line column]}]
  (sg/queue-fx tx :shadow-api
    {:request {:uri "/open-file"
               :method :POST
               :body {:file file
                      :line line
                      :column column}}
     :on-success {:e ::m/open-result!}}))

(defn load-snapshot [tx {:keys [target-id] :as target}]
  (relay-ws/call! (::rt/runtime-ref tx)
    {:op ::m/take-snapshot
     :to target-id}
    {:e ::m/set-snapshot!
     :target-id target-id}))

(defc ui-slot [target-id item {:keys [type name value] :as slot} idx type]
  (bind expanded-ref (atom false))
  (bind expanded (sg/watch expanded-ref))

  (render
    (let [{:keys [preview edn]} value]
      (<< [:div {:class (css :py-0.5 :pr-2 :font-semibold :whitespace-nowrap :border-t :cursor-help)
                 :title (str "click to log " name " in runtime console")
                 :on-click {:e ::m/request-log! :target-id target-id :name name :instance-id (:instance-id item) :idx idx :type type}} name]
          [:div {:class (css :py-0.5 :font-mono :overflow-auto :border-t)
                 :style/max-height (when-not expanded "142px")
                 :on-click #(swap! expanded-ref not)}
           (if preview
             (<< [:div
                  [:div {:class (css :font-bold :p-2)} "FIXME: value too large to print"]
                  [:div {:class (css :p-2 :truncate :whitespace-nowrap)} preview]])
             (edn/render-edn-str edn))]
          ))))

(defc ui-component-info [target-id {:keys [args slots] :as item}]
  (render
    (let [$section-label (css :font-semibold :py-2 {:grid-column "span 2"})]
      (<< [:div
           {:class (css :pl-2 {:border-left "6px solid #eee"})}
           [:div
            {:class (css :cursor-pointer :underline :pt-2)
             :title "click to open file in editor"
             :on-click {:e ::m/open-in-editor!
                        :target-id target-id
                        :file (:file item)
                        :line (:line item)
                        :column (:column item)}}

            (str "File: " (:file item) "@" (:line item) ":" (:column item))]
           [:div {:class (css
                           :overflow-hidden
                           {:display "grid"
                            :grid-template-columns "min-content minmax(25%, auto)"
                            :grid-row-gap "0"})}
            (when (seq args)
              (<< [:div {:class $section-label} "Arguments"]
                  (sg/simple-seq args #(ui-slot target-id item %1 %2 :arg))))

            (when (seq slots)
              (<< [:div {:class $section-label} "Slots"]
                  (sg/simple-seq slots #(ui-slot target-id item %1 %2 :slot))))]]))))

(declare ui-node)

(defn ui-node-children [ctx item]
  (sg/simple-seq (:children item)
    (fn [item idx]
      (ui-node (update ctx :path conj idx) item))))

(defc ui-node [ctx item]
  (bind {:keys [selected]}
    (sg/kv-lookup ::m/target (:target-id ctx)))

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
                     :on-mouseout {:e ::m/remove-highlight! :target-id (:target-id ctx) :item (:instance-id item)}
                     :on-mouseenter {:e ::m/highlight! :target-id (:target-id ctx) :item (:instance-id item)}}
               (:name item)]
              ;; can't use :instance-id for tracking which component should show details
              ;; as every hot-reload re-creates the component, thus changing the instance id
              ;; instead we track which one we were looking at via the path in the tree
              ;; which might still change and close the one we were looking at
              ;; but can't think of another way to do this currently
              (when (contains? selected (:path comp-ctx))
                (ui-component-info (:target-id ctx) item))
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


(defc ui-panel [^:stable target-id]
  (bind {:keys [snapshot view selected] :as target}
    (sg/kv-lookup ::m/target target-id))

  (effect :mount [env]
    (when-not snapshot
      (load-snapshot env target)))

  (event ::load-snapshot! [env ev e]
    (load-snapshot env target))

  (event ::select! [env {:keys [item] :as ev} e]
    (.preventDefault e)
    (sg/run-tx env
      {:e ::m/set-selection!
       :target-id target-id
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
      {:e ::m/set-selection!
       :target-id target-id
       :v nil}))

  (event ::request-log! [env ev e]
    (sg/dispatch-up! env (assoc ev :target-id target-id)))

  (bind ctx
    {:target-id target-id
     :path []
     :level 0})

  (render
    (<< [:div {:class (css :flex-1 :overflow-auto :pl-2 :py-2)}
         (sg/simple-seq snapshot #(ui-node ctx %1))])))
