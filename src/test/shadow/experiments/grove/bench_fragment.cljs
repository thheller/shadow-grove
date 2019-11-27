(ns shadow.experiments.grove.bench-fragment
  (:require
    ["benchmark" :as b]
    ["react" :as react :rename {createElement $}]
    ["react-dom" :as rdom]
    [goog.reflect :as gr]
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.arborist.interpreted]
    [shadow.experiments.arborist.protocols :as sap]))

;; browser version seems to rely on Benchmark global for some reason
(set! js/Benchmark b)

(defn log-cycle [event]
  (println (.toString (.-target event))))

(defn log-complete [event]
  (this-as this
    (js/console.log this)))

(defn fragment-optimized [v]
  (sa/fragment
    [:div.card
     [:div.card-title "title"]
     [:div.card-body v]
     (when v
       (sa/fragment
         [:div.card-footer
          [:div.card-actions
           [:button "ok" v]
           [:button "cancel"]]]))]))

(defn fragment-fallback [v]
  (sa/fragment-fallback
    [:div.card
     [:div.card-title "title"]
     [:div.card-body v]
     (when v
       (sa/fragment-fallback
         [:div.card-footer
          [:div.card-actions
           [:button "ok" v]
           [:button "cancel"]]]))
     ]))

(defn hiccup [v]
  [:div.card
   [:div.card-title "title"]
   [:div.card-body v]
   (when v
     [:div.card-footer
      [:div.card-actions
       [:button "ok" v]
       [:button "cancel"]]])])

(defn react-element [v]
  ($ "div" #js {:className "card"}
    ($ "div" #js {:className "card-title"} "title")
    ($ "div" #js {:className "card-body"} v)
    (when v
      ($ "div" #js {:className "card-footer"}
        ($ "div" #js {:className "card-actions"}
          ($ "button" nil "ok" v)
          ($ "button" nil "cancel"))))))

(defn start []
  (let [m-optimized
        (sap/as-managed (fragment-optimized (str "dont-inline-this: " (rand))) {})

        m-fallback
        (sap/as-managed (fragment-fallback (str "dont-inline-this: " (rand))) {})

        m-hiccup
        (sap/as-managed (hiccup (str "dont-inline-this: " (rand))) {})

        r-root
        (js/document.createElement "div")]

    (sap/dom-insert m-optimized js/document.body nil)
    (sap/dom-insert m-fallback js/document.body nil)
    (sap/dom-insert m-hiccup js/document.body nil)

    (js/document.body.appendChild r-root)

    (rdom/render (react-element (rand)) r-root)

    (-> (b/Suite.)
        ;; just fragment
        (.add "fragment-optimized" #(fragment-optimized (rand)))
        ;; construct dom
        (.add "managed-optimized" #(sap/as-managed (fragment-optimized (rand)) {}))
        ;; update dom
        (.add "update-optimized" #(sap/dom-sync! m-optimized (fragment-optimized (rand))))

        (.add "fragment-fallback" #(fragment-fallback (rand)))
        (.add "managed-fallback" #(sap/as-managed (fragment-fallback (rand)) {}))
        (.add "update-fallback" #(sap/dom-sync! m-fallback (fragment-fallback (rand))))

        (.add "hiccup" #(hiccup (rand)))
        (.add "managed-hiccup" #(sap/as-managed (hiccup (rand)) {}))
        (.add "update-hiccup" #(sap/dom-sync! m-hiccup (hiccup (rand))))

        (.add "react-element" #(react-element (rand)))
        ;; can't test create-only since rdom requires the element to be in the dom
        (.add "react-dom" #(rdom/render (react-element (rand)) r-root))

        (.on "cycle" log-cycle)
        (.run #js {:async true}))))

;; node
(defn main []
  (start))

;; browser
(defn init []
  (js/setTimeout start 1000))
