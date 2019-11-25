(ns shadow.experiments.grove.bench-fragment
  (:require
    ["benchmark" :as b]
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.arborist.protocols :as sap]))

;; browser version seems to rely on Benchmark global for some reason
(set! js/Benchmark b)

(defn log-cycle [event]
  (println (.toString (.-target event))))

(defn log-complete [event]
  (this-as this
    (js/console.log this)))

(defn fragment-optimized []
  (sa/fragment
    [:div.card
     [:div.card-title "title"]
     [:div.card-body "body"]
     [:div.card-footer
      [:div.card-actions
       [:button "ok"]
       [:button "cancel"]]]]))

(defn fragment-fallback []
  (sa/fragment-fallback
    [:div.card
     [:div.card-title "title"]
     [:div.card-body "body"]
     [:div.card-footer
      [:div.card-actions
       [:button "ok"]
       [:button "cancel"]]]]))

(defn hiccup []
  [:div.card
   [:div.card-title "title"]
   [:div.card-body "body"]
   [:div.card-footer
    [:div.card-actions
     [:button "ok"]
     [:button "cancel"]]]])

(defn start []
  (-> (b/Suite.)
      (.add "fragment-optimized" fragment-optimized)
      (.add "fragment-fallback" fragment-fallback)
      (.add "hiccup" hiccup) ;; not actually fair to compare this
      (.on "cycle" log-cycle)
      (.run)))

;; node
(defn main []
  (start))

;; browser
(defn init []
  (js/setTimeout start 1000))
