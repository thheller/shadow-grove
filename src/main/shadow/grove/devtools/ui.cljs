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
    [shadow.grove.ui.vlist2 :as vlist]
    [shadow.grove :as sg :refer (defc << css)]))

(defn form-set-attr!
  {::ev/handle :form/set-attr}
  [env {:keys [a v]}]
  (update env :db assoc-in (if (keyword? a) [a] a) v))

(defn set-snapshot!
  {::ev/handle ::m/set-snapshot!}
  [env {:keys [ident call-result] :as msg}]
  (let [snapshot (:snapshot call-result)]
    (assoc-in env [:db ident :snapshot] snapshot)
    ))

(defn load-snapshot [env {:keys [client-id] :as runtime}]
  (relay-ws/call! @(::rt/runtime-ref env)
    {:op :shadow.grove.preload/take-snapshot
     :to client-id}
    {:e ::m/set-snapshot!
     :ident (:db/ident runtime)}))

(defn switch-view!
  {::ev/handle ::m/switch-view!}
  [env {:keys [view runtime] :as ev} e]
  (assoc-in env [:db runtime :view] view))

(defn highlight!
  {::ev/handle ::m/highlight!}
  [env {:keys [item runtime] :as ev} e]
  (when (contains? (get-in env [:db runtime :supported-ops]) ::m/highlight-component)
    (sg/queue-fx env
      :relay-send
      [{:op ::m/highlight-component
        :component item
        :to (get-in env [:db runtime :client-id])}])))

(defn request-log!
  {::ev/handle ::m/request-log!}
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
  {::ev/handle ::m/remove-highlight!}
  [env {:keys [item runtime] :as ev} e]
  (when (contains? (get-in env [:db runtime :supported-ops]) ::m/highlight-component)
    (sg/queue-fx env
      :relay-send
      [{:op ::m/remove-highlight
        :to (get-in env [:db runtime :client-id])}])))

(defn events-vlist [env db runtime-ident {:keys [offset num] :or {offset 0 num 0} :as params}]
  (let [events
        (get-in db [runtime-ident :events])

        entries
        (count events)

        slice
        (->> events
             (drop offset)
             (take num)
             (vec))]

    {:item-count entries
     :offset offset
     :slice slice}))

(defc ui-slot [item {:keys [type name value] :as slot} idx type]
  (bind expanded-ref (atom false))
  (bind expanded (sg/watch expanded-ref))

  (render
    (let [{:keys [preview edn]} value]
      (<< [:div {:class (css :py-0.5 :pr-2 :font-semibold :whitespace-nowrap :border-t :cursor-help)
                 :title (str "click to log " name " in runtime console")
                 :on-click {:e ::m/request-log! :name name :instance-id (:instance-id item) :idx idx :type type}} name]
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
                     :on-mouseout {:e ::m/remove-highlight! :runtime (:runtime-ident ctx) :item (:instance-id item)}
                     :on-mouseenter {:e ::m/highlight! :runtime (:runtime-ident ctx) :item (:instance-id item)}}
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

(defc ui-event [event-ident]
  (bind entry
    (sg/db-read event-ident))

  (render
    (let [{:keys [ts event keys-new keys-updated keys-removed fx]} entry
          $numeric (css :text-center {:width "30px"})]

      (<< [:div {:class (css :flex :border-b [:hover :bg-gray-100])
                 :on-click {:e :form/set-attr
                            :a [(:runtime entry) :selected-event]
                            :v event-ident}}
           [:div {:class (css :px-2)} ts]
           [:div {:class (css :flex-1 {:color "#660e7a"})} (str (:e event))]
           [:div {:class $numeric} (count keys-new)]
           [:div "/"]
           [:div {:class $numeric} (count keys-updated)]
           [:div "/"]
           [:div {:class $numeric} (count keys-removed)]
           [:div "/"]
           [:div {:class $numeric} (count fx)]
           ])
      )))

(defc expandable [content]
  (bind content-ref (sg/ref))

  (bind state-ref
    (atom {:expanded false
           :needs-expand false}))

  (bind {:keys [expanded needs-expand]}
    (sg/watch state-ref))

  (effect content [env]
    (let [el @content-ref]
      (when (not= (.-scrollHeight el) (.-clientHeight el))
        (swap! state-ref assoc :needs-expand true)
        )))

  (render
    (<< [:div {:class (css :relative
                        ["& > .controls" :hidden]
                        ["&:hover > .controls" :block])}

         (when needs-expand
           (<< [:div {:class (css "controls"
                               :absolute :p-2 :bg-white
                               :border :shadow-lg
                               {:top "0px"
                                :z-index "1"})
                      :style/cursor (if expanded "zoom-out" "zoom-in")
                      :on-click #(swap! state-ref update :expanded not)}
                (if expanded "-" "+")]))

         [:div {:dom/ref content-ref
                :style/max-height (when-not expanded "129px")
                :class (css :flex-1 :overflow-auto)}
          content]])))

(def icon-close
  ;; https://github.com/sschoger/heroicons-ui/blob/master/svg/icon-x-square.svg
  (<< [:svg
       {:xmlns "http://www.w3.org/2000/svg"
        :viewBox "0 0 24 24"
        :width "24"
        :height "24"}
       [:path
        {:d "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5c0-1.1.9-2 2-2zm0 2v14h14V5H5zm8.41 7l1.42 1.41a1 1 0 1 1-1.42 1.42L12 13.4l-1.41 1.42a1 1 0 1 1-1.42-1.42L10.6 12l-1.42-1.41a1 1 0 1 1 1.42-1.42L12 10.6l1.41-1.42a1 1 0 1 1 1.42 1.42L13.4 12z"}]]))

(defc ui-event-details [event-ident]
  (bind entry
    (sg/db-read event-ident))

  (bind tab-ref
    (atom :event))

  (bind tab
    (sg/watch tab-ref))

  (render
    (let [{:keys [ts event keys-new keys-updated keys-removed fx]} entry
          $label (css :px-4 :py-2 :border-l :text-center :cursor-pointer)]
      (<< [:div {:class (css :flex)}
           [:div {:class (css :flex-1 :px-4 :pt-2 :text-lg :font-bold)} (str (:e event))]
           [:div {:class (css :cursor-pointer :pt-2 :pr-2)
                  :on-click ::close!} icon-close]]
          [:div {:class (css :px-4 :pb-2 :text-gray-600 :text-sm)} ts]
          [:div {:class (css :flex :border-y-2 :overflow-hidden)}
           [:div {:class $label :on-click #(reset! tab-ref :event)} "Event"]
           [:div {:class $label :on-click #(reset! tab-ref :keys-new)} (count keys-new) " new keys added"]
           [:div {:class $label :on-click #(reset! tab-ref :keys-updated)} (count keys-updated) " keys updated"]
           [:div {:class $label :on-click #(reset! tab-ref :keys-removed)} (count keys-removed) " keys removed"]
           [:div {:class $label :on-click #(reset! tab-ref :fx)} (count fx) " effects"]]
          [:div {:class (css :flex-1 :overflow-auto)}
           (edn/render-edn
             (case tab
               :keys-new keys-new
               :keys-updated keys-updated
               :keys-removed keys-removed
               :fx fx
               event))]

          ))))

(defc ui-events [runtime-ident]
  (bind events
    (sg/db-read [runtime-ident :events]))

  (bind selected
    (sg/db-read [runtime-ident :selected-event]))

  (event ::close! [env ev e]
    (sg/run-tx env
      {:e :form/set-attr
       :a [runtime-ident :selected-event]
       :v nil}))

  (render
    (<< [:div {:class (css :flex-1 :overflow-auto)
               :style/min-height (when selected "80px")
               :style/max-height (when selected "80px")}
         #_[:div {:class (css :flex)}
            [:div {:class (css :text-center {:width "140px"})} "Changes"]
            [:div {:class (css :flex-1 :pl-2)} "Event"]]
         (sg/keyed-seq events identity ui-event)]
        (when selected
          (<< [:div {:class (css :flex-1 :flex :flex-col :overflow-auto {:border-top "4px solid #eee"})}
               (ui-event-details selected)]))
        )))

(defc ui-tree [^:stable runtime-ident]
  (bind {:keys [snapshot view] :as runtime}
    (sg/db-read runtime-ident))

  (effect :mount [env]
    (when-not snapshot
      (load-snapshot env runtime)))

  (event ::load-snapshot! [env ev e]
    (load-snapshot env runtime))

  ;; FIXME: move this to proper db handlers
  ;; component technically doesn't care about selected
  (bind selected
    (sg/db-read ::m/selected))

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
    (<< [:div {:class (css :flex-1 :overflow-auto :pl-2 :py-2)}
         (sg/simple-seq snapshot #(ui-node ctx %1))])))

(defc ui-runtime [^:stable runtime-ident]
  (bind view
    (sg/db-read [runtime-ident :view]))

  (render
    (let [$button (css :inline-block :cursor-pointer :text-lg :border :px-4 :rounded :whitespace-nowrap :bg-blue-200 [:hover :bg-blue-400])]
      (<< [:div {:class (css :flex-1 :flex :flex-col :text-sm :overflow-hidden)}
           [:div {:class (css :bg-white :p-2)}
            [:div
             [:button {:class $button
                       :on-click {:e ::m/switch-view! :runtime runtime-ident :view :tree}} "Tree"]
             [:button {:class $button
                       :on-click {:e ::m/switch-view! :runtime runtime-ident :view :events}} "Events"]]]
           (case view
             :events
             (ui-events runtime-ident)

             ;; ui tree
             (ui-tree runtime-ident)
             )]))))

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