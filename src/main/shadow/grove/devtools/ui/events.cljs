(ns shadow.grove.devtools.ui.events
  (:require
    [shadow.grove.db :as db]
    [shadow.grove.devtools :as-alias m]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools.ui.common :as ui-common]
    [shadow.grove.ui.edn :as edn]))

(defn select-event!
  {::ev/handle ::select-event!}
  [env {:keys [runtime event] :as ev}]

  (let [ev (get-in env [:db event])]
    (-> env
        (assoc-in [:db runtime :selected-event] event)
        (cond->
          (and (= :tx-report (:type ev))
               (not (:diff ev)))
          (sg/queue-fx
            :relay-send
            {:op ::m/get-tx-diff
             :event-id (:event-id ev)
             :tx-id (:tx-id ev)
             :to (get-in env [:db runtime :client-id])})
          ))))


(defmethod relay-ws/handle-msg ::m/tx-diff
  [env {:keys [from event-id diff] :as msg}]
  (let [event-ident (db/make-ident ::m/event event-id)]
    (assoc-in env [:db event-ident :diff] diff)))

(defc ui-diff-entry [{:keys [op] :as entry}]
  (bind show-ref (atom false))
  (bind show? (sg/watch show-ref))

  (render
    (<< [:div {:class (css :border-b-2)}
         [:div {:class (css :cursor-pointer :flex)
                :on-click #(swap! show-ref not)}
          [:div {:class (css :p-1)}
           ;; FIXME: some actual icons would be nice
           (case op
             :db-add "+"
             :db-remove "-"
             :db-update "%"
             "?")]
          [:div {:class (css :flex-1)}
           [:div {:class (css :py-1 :font-bold)} (pr-str (:key entry))]

           (when show?
             (case op
               :db-update (edn/edn-diff (:before entry) (:after entry))
               :db-add (edn/render-edn (:val entry))
               :db-remove (edn/render-edn (:val entry))
               (edn/render-edn entry)))]]])))

(defc ui-fx-entry [[fx-key fx-val]]
  (<< [:div
       [:div (edn/render-edn fx-key)]
       [:div (edn/render-edn fx-val)]
       ])
  )

(defc ui-tx-report [^:stable event-ident entry]
  (bind tab-ref
    (atom :event))

  (bind tab
    (sg/watch tab-ref))

  (bind diff
    (sg/db-read [event-ident :diff]))

  (render
    (let [{:keys [ts event count-new count-updated count-removed fx]} entry
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
             :added (sg/simple-seq (:added diff) ui-diff-entry)
             :updated (sg/simple-seq (:updated diff) ui-diff-entry)
             :removed (sg/simple-seq (:removed diff) ui-diff-entry)
             :fx (sg/simple-seq fx ui-fx-entry)
             (edn/render-edn event))]
          ))))

(defc ui-dev-log [event-ident entry]
  (bind event
    (sg/db-read event-ident))

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

(defc ui-event-details [event-ident]
  (bind entry
    (sg/db-read event-ident))

  (bind tab-ref
    (atom :event))

  (bind tab
    (sg/watch tab-ref))

  (render
    (case (:type entry)
      :tx-report (ui-tx-report event-ident entry)
      :dev-log (ui-dev-log event-ident entry)
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

(defc ui-log-item [event-ident]
  (bind entry
    (sg/db-read event-ident))

  (render
    (let [{:keys [ts]} entry
          $numeric (css :text-center {:width "30px"})

          select-ev
          {:e ::select-event!
           :runtime (:runtime entry)
           :event event-ident}]

      (case (:type entry)
        :dev-log
        (<< [:div {:class (css :flex :border-b :cursor-pointer [:hover :bg-gray-100])
                   :on-click select-ev}
             [:div {:class (css :px-2 {:width "95px"})} (time-ts ts)]
             [:div {:class (css :flex-1 :truncate)} (:header entry)]])

        :tx-report
        (let [{:keys [event count-new count-updated count-removed fx]} entry]
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

(defc ui-panel [runtime-ident]
  (bind events
    (sg/db-read [runtime-ident :events]))

  (bind selected
    (sg/db-read [runtime-ident :selected-event]))

  (event ::close! [env ev e]
    (sg/run-tx env
      {:e ::select-event!
       :runtime runtime-ident
       :event nil}))

  (render
    (<< [:div {:class (css :flex-1 :overflow-auto)}
         #_[:div {:class (css :flex)}
            [:div {:class (css :text-center {:width "140px"})} "Changes"]
            [:div {:class (css :flex-1 :pl-2)} "Event"]]
         (sg/keyed-seq events identity ui-log-item)]

        [:div {:class (css :flex :flex-col :overflow-auto
                        {:border-top "4px solid #eee"})
               :style/height (if selected "80%" "0%")}
         (when selected
           (ui-event-details selected))])))


