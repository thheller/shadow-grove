(ns shadow.grove.devtools.ui.events
  (:require
    [shadow.grove.devtools :as-alias m]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools.ui.common :as ui-common]
    [shadow.grove.ui.edn :as edn]))

(defn select-event!
  {::ev/handle ::select-event!}
  [env {:keys [target-id event-id] :as ev}]

  (let [ev (get-in env [::m/event event-id])]
    (-> env
        (assoc-in [::m/target target-id :selected-event] event-id)
        (cond->
          (and (= :tx-report (:type ev))
               (not (:diff ev)))
          (sg/queue-fx
            :relay-send
            {:op ::m/get-tx-diff
             :event-id event-id
             :tx-id (::m/tx-id ev)
             :to target-id})
          ))))


(defmethod relay-ws/handle-msg ::m/tx-diff
  [env {:keys [from event-id diff] :as msg}]
  (assoc-in env [::m/event event-id :diff] diff))

(defc ui-diff-entry [entry op]
  (bind show-ref (atom false))
  (bind show? (sg/watch show-ref))

  (render
    (<< [:div {:class (css :border-b-2)}
         [:div {:class (css :flex)}
          [:div {:class (css :p-1)}
           ;; FIXME: some actual icons would be nice
           (case op
             :add "+"
             :remove "-"
             :update "%"
             "?")]
          [:div {:class (css :flex-1)}
           [:div {:class (css :cursor-pointer :py-1 :font-semibold)
                  :on-click #(swap! show-ref not)}
            (str (:kv-table entry) " - " (pr-str (:key entry)))]

           (when show?
             (case op
               :update (edn/edn-diff (:before entry) (:after entry))
               :add (edn/render-edn (:val entry))
               :remove (edn/render-edn (:val entry))
               (edn/render-edn entry)))]]])))

(defc ui-fx-entry [[fx-key fx-val]]
  (<< [:div
       [:div (edn/render-edn fx-key)]
       [:div (edn/render-edn fx-val)]
       ]))

(defc ui-tx-report [^:stable event-id entry]
  (bind tab-ref
    (atom :event))

  (bind tab
    (sg/watch tab-ref))

  (bind diff
    (:diff (sg/kv-lookup ::m/event event-id)))

  (render
    (let [{::m/keys [ts] ::sg/keys [event fx] :keys [count-new count-updated count-removed]} entry
          $label (css :px-4 :py-2 :border-l :text-center :cursor-pointer
                   ["&.active" :font-bold])]
      (<< [:div {:class (css :flex)
                 :on-click ::close!}
           [:div {:class (css :flex-1 :px-4 :py-2 :text-lg :font-bold)} (str (:e event))]
           [:div {:class (css :cursor-pointer :py-2 :pr-2)} ui-common/icon-close]]
          [:div {:class (css :flex :border-y-2 :overflow-hidden)}
           [:div {:class (str $label (when (= tab :event) " active")) :on-click #(reset! tab-ref :event)} "Event"]
           [:div {:class (str $label (when (= tab :added) " active")) :on-click #(reset! tab-ref :added)}
            (str "Added (" count-new ")")]
           [:div {:class (str $label (when (= tab :updated) " active")) :on-click #(reset! tab-ref :updated)}
            (str "Updated (" count-updated ")")]
           [:div {:class (str $label (when (= tab :removed) " active")) :on-click #(reset! tab-ref :removed)}
            (str "Removed (" count-removed ")")]
           [:div {:class (str $label (when (= tab :fx) " active")) :on-click #(reset! tab-ref :fx)}
            (str "Effects (" (count fx) ")")]]

          [:div {:class (css :flex-1 :overflow-auto)}
           (case tab
             :added (sg/simple-seq (:added diff) #(ui-diff-entry %1 :add))
             :updated (sg/simple-seq (:updated diff) #(ui-diff-entry %1 :update))
             :removed (sg/simple-seq (:removed diff) #(ui-diff-entry %1 :update))
             :fx (sg/simple-seq fx ui-fx-entry)
             (edn/render-edn event))]
          ))))

(defc ui-dev-log [event-id entry]
  (bind event
    (sg/kv-lookup ::m/event event-id))

  (render
    (let [{:keys [ns line column file]} (:src-info entry)]
      (<< [:div
           [:div {:on-click ::close! :class (css :cursor-pointer :py-2 :pr-2 {:float "right"})} ui-common/icon-close]
           [:div {:class (css :flex-1 :px-4 :py-2)}
            [:div {:class (css :text-lg :font-semibold)
                   :on-click ::close!}
             (:header entry)]
            [:div {:class (css :cursor-pointer)
                   :on-click {:e ::m/open-in-editor!
                              :target (:runtime event)
                              :file file
                              :line line
                              :column column}}
             (str (or file ns) "@" line ":" column)]]]

          [:div {:class (css :flex-1 :overflow-auto)}
           (edn/render-edn
             (:log entry))]))))

(defc ui-event-details [event-id]
  (bind entry
    (sg/kv-lookup ::m/event event-id))

  (bind tab-ref
    (atom :event))

  (bind tab
    (sg/watch tab-ref))

  (render
    (case (:type entry)
      :tx-report (ui-tx-report event-id entry)
      :dev-log (ui-dev-log event-id entry)
      (<< [:div {:class (css :flex-1)}
           [:div "Unknown Entry"]
           [:div (edn/render-edn entry)]]))
    ))

(defn pad2 [num]
  (.padStart (js/String num) 2 "0"))

(defn time-ts [ts]
  (let [d (js/Date. ts)]
    (str (pad2 (.getHours d))
         ":"
         (pad2 (.getMinutes d))
         ":"
         (pad2 (.getSeconds d))
         "."
         (.getMilliseconds d))))

(defc ui-log-item [event-id]
  (bind entry
    (sg/kv-lookup ::m/event event-id))

  (render
    (let [ts (::m/ts entry)
          $numeric (css :text-center {:width "30px"})

          select-ev
          {:e ::select-event!
           :target-id (:target-id entry)
           :event-id event-id}]

      (case (:type entry)
        :dev-log
        (<< [:div {:class (css :flex :border-b :cursor-pointer [:hover :bg-gray-100])
                   :on-click select-ev}
             [:div {:class (css :px-2 {:width "95px"})} (time-ts ts)]
             [:div {:class (css :flex-1 :truncate)} (:header entry)]])

        :tx-report
        (let [{::sg/keys [event fx] :keys [count-new count-updated count-removed]} entry]
          (<< [:div {:class (css :flex :border-b :cursor-pointer [:hover :bg-gray-100])
                     :on-click select-ev}
               [:div {:class (css :px-2 {:width "95px"})} (time-ts ts)]
               [:div {:class (css :flex-1 :truncate {:color "#660e7a"})} (str (:e event))]
               [:div {:class $numeric} count-new]
               [:div "/"]
               [:div {:class $numeric} count-updated]
               [:div "/"]
               [:div {:class $numeric} count-removed]
               [:div "/"]
               [:div {:class $numeric} (count fx)]]
              ))

        (<< [:div (pr-str entry)])))))

(defc ui-panel [target-id]
  (bind {:keys [events selected-event] :as target}
    (sg/kv-lookup ::m/target target-id))

  (event ::close! [env ev e]
    (sg/run-tx env
      {:e ::select-event!
       :runtime target-id
       :event nil}))

  (render
    (<< [:div {:class (css :flex-1 :overflow-auto)}
         #_[:div {:class (css :flex)}
            [:div {:class (css :text-center {:width "140px"})} "Changes"]
            [:div {:class (css :flex-1 :pl-2)} "Event"]]
         (sg/keyed-seq events identity ui-log-item)]

        [:div {:class (css :flex :flex-col :overflow-auto
                        {:border-top "4px solid #eee"})
               :style/height (if selected-event "80%" "0%")}
         (when selected-event
           (ui-event-details selected-event))])))


