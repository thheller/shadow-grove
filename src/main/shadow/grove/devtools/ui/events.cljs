(ns shadow.grove.devtools.ui.events
  (:require
    [shadow.grove.devtools :as-alias m]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools.ui.common :as ui-common]
    [shadow.grove.ui.edn :as edn]))

(defn select-event!
  {::ev/handle ::select-event!}
  [env {:keys [runtime event] :as ev}]
  (assoc-in env [:db runtime :selected-event] event))

(defc ui-tx-report [entry]
  (bind tab-ref
    (atom :event))

  (bind tab
    (sg/watch tab-ref))

  (render
    (let [{:keys [ts event keys-new keys-updated keys-removed fx]} entry
          $label (css :px-4 :py-2 :border-l :text-center :cursor-pointer)]
      (<< [:div {:class (css :flex)
                 :on-click ::close!}
           [:div {:class (css :flex-1 :px-4 :py-2 :text-lg :font-bold)} (str (:e event))]
           [:div {:class (css :cursor-pointer :py-2 :pr-2)} ui-common/icon-close]]
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

(defc ui-dev-log [entry]
  (render
    (let [{:keys [ns line column file]} (:src-info entry)]

      (<< [:div
           [:div {:on-click ::close! :class (css :cursor-pointer :py-2 :pr-2 {:float "right"})} ui-common/icon-close]
           [:div {:on-click ::close!
                  :class (css :flex-1 :px-4 :py-2)}
            [:div {:class (css :text-lg :font-semibold)} (:header entry)]
            [:div (str (or file ns) "@" line ":" column)]]]

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
      :tx-report (ui-tx-report entry)
      :dev-log (ui-dev-log entry)
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
        (let [{:keys [event keys-new keys-updated keys-removed fx]} entry]
          (<< [:div {:class (css :flex :border-b :cursor-pointer [:hover :bg-gray-100])
                     :on-click select-ev}
               [:div {:class (css :px-2 {:width "95px"})} (time-ts ts)]
               [:div {:class (css :flex-1 :truncate {:color "#660e7a"})} (str (:e event))]
               [:div {:class $numeric} (count keys-new)]
               [:div "/"]
               [:div {:class $numeric} (count keys-updated)]
               [:div "/"]
               [:div {:class $numeric} (count keys-removed)]
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


