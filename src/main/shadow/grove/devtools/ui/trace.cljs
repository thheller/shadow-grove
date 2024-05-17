(ns shadow.grove.devtools.ui.trace
  (:require
    [shadow.grove.devtools :as-alias m]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools.ui.common :as ui-common]
    [shadow.grove.ui.edn :as edn]))

(defn load-traces [tx {:keys [target-id] :as target}]
  (relay-ws/cast! (::sg/runtime-ref tx)
    {:op ::m/request-traces
     :to target-id}))

(defc ui-panel [target-id]
  (bind {:keys [traces] :as target}
    (sg/kv-lookup ::m/target target-id))

  (effect :mount [env]
    (load-traces env target))

  (event ::refresh! [env ev e]
    (load-traces env target))

  (render
    (<< [:div {:class (css :flex-1 :overflow-auto :font-mono)}
         [:div {:class (css :flex)}
          [:button {:class (css :inline-block :cursor-pointer :text-lg :border :px-4 :rounded :whitespace-nowrap :bg-blue-200 [:hover :bg-blue-400])
                    :on-click {:e ::refresh! :e/prevent-default true}}
           "refresh"]

          [:div {:class (css :font-bold :text-lg :p-2)}
           "TBD: No clue how to display this yet!"]]
         (sg/simple-seq traces
           (fn [trace]
             (sg/simple-seq trace
               (fn [trace-item idx]
                 (let [[id ts & more] trace-item]
                   (<< [:div {:class
                              (if (zero? idx)
                                (css :flex :font-bold)
                                (css :flex))}
                        [:div {:class (css :p-1)} ts]
                        [:div {:class (css :p-1 :whitespace-nowrap)} (str id)]
                        [:div {:class (css :p-1 :truncate)}
                         (pr-str more)]]))))))])))
