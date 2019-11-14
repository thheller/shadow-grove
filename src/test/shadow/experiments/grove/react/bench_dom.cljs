(ns shadow.experiments.grove.react.bench-dom
  (:require
    ["benchmark" :as b]
    ["react" :as react :rename {createElement rce}]
    ["react-dom/server" :as rdom]
    [hx.react :as hx]
    [shadow.experiments.grove.react :as shadow]
    [shadow.experiments.grove.html :as html]
    [reagent.core :as reagent]
    [rum.core :as rum]
    [fulcro.client.dom :as fulcro-dom]
    [clojure.string :as str]))

(defn reagent-render [{:keys [title body items]}]
  (rdom/renderToString
    (reagent/as-element
      [:div.card
       [:div.card-title title]
       [:div.card-body body]
       [:ul.card-list
        (for [item items]
          ^{:key item} [:li item])]
       [:div.card-footer
        [:div.card-actions
         [:button "ok"]
         [:button "cancel"]]]])))

(defn shadow-render [{:keys [title body items]}]
  (rdom/renderToString
    (shadow/<<
      [:div.card
       [:div.card-title title]
       [:div.card-body body]
       [:ul.card-list
        (shadow/render-seq
          items
          identity
          (fn [item]
            (shadow/<< [:li item])))]
       [:div.card-footer
        [:div.card-actions
         [:button "ok"]
         [:button "cancel"]]]])))

(defn html-render [{:keys [title body items]}]
  (str (html/str
         [:div.card
          [:div.card-title title]
          [:div.card-body body]
          [:ul.card-list
           (html/SafeString.
             (reduce
               (fn [s item]
                 (str s (html/str [:li item])))
               ""
               items))]
          [:div.card-footer
           [:div.card-actions
            [:button "ok"]
            [:button "cancel"]]]])))

(defn react-render [{:keys [title body items]}]
  (rdom/renderToString
    (rce "div" #js {:className "card"}
      (rce "div" #js {:className "card-title"} title)
      (rce "div" #js {:className "card-body"} body)
      (rce "ul" #js {:className "card-list"}
        (shadow/render-seq
          items
          identity
          (fn [item]
            (rce "li" #js {} item))))
      (rce "div" #js {:className "card-footer"}
        (rce "div" #js {:className "card-actions"}
          (rce "button" nil "ok")
          (rce "button" nil "cancel"))))))

(defn hx-render [{:keys [title body items]}]
  (rdom/renderToString
    (hx/f
      [:div {:class "card"}
       [:div {:class "card-title"} title]
       [:div {:class "card-body"} body]
       [:ul {:class "card-list"}
        (for [item items]
          [:li {:key item} item])]
       [:div {:class "card-footer"}
        [:div {:class "card-actions"}
         [:button "ok"]
         [:button "cancel"]]]])))

(rum/defc rumc [{:keys [title body items]}]
  [:div.card
   [:div.card-title title]
   [:div.card-body body]
   [:ul.card-list
    (for [item items]
      [:li {:key item} item])]
   [:div.card-footer
    [:div.card-actions
     [:button "ok"]
     [:button "cancel"]]]])

(defn rum-render [props]
  (rdom/renderToString
    (rumc props)))

(defn fulcro-dom-render [{:keys [title body items]}]
  (rdom/renderToString
    (fulcro-dom/div {:className "card"}
      (fulcro-dom/div {:className "card-title"} title)
      (fulcro-dom/div {:className "card-body"} body)
      (fulcro-dom/ul {:className "card-list"}
        (shadow/render-seq
          items
          identity
          (fn [item]
            (fulcro-dom/li {} item)
            )))
      (fulcro-dom/div {:className "card-footer"}
        (fulcro-dom/div {:className "card-actions"}
          (fulcro-dom/button "ok")
          (fulcro-dom/button "cancel"))))))

(defn log-cycle [event]
  (println (.toString (.-target event))))

(defn log-complete [event]
  (this-as this
    (js/console.log this)))

(defn main [& args]
  (let [test-data {:title "hello world"
                   :body "body"
                   :items (shuffle (range 10))}]
    (println "react")
    (println (react-render test-data))
    (println "reagent")
    (println (reagent-render test-data))
    (println "shadow")
    (println (shadow-render test-data))
    (println "html")
    (println (html-render test-data))
    (println "hx")
    (println (hx-render test-data))
    (println "rum")
    (println (rum-render test-data))
    (println "fulcro-dom")
    (println (fulcro-dom-render test-data))

    (when-not (= (react-render test-data)
                 (reagent-render test-data)
                 (shadow-render test-data)
                 ;; not equal due to missing data-reactroot=""
                 ;; (html-render test-data)
                 (hx-render test-data)
                 (rum-render test-data)
                 (fulcro-dom-render test-data))
      (throw (ex-info "not equal!" {})))

    (-> (b/Suite.)
        (.add "react" #(react-render test-data))
        (.add "reagent" #(reagent-render test-data))
        (.add "shadow" #(shadow-render test-data))
        (.add "html" #(html-render test-data))
        ;; (.add "hx" #(hx-render test-data))
        (.add "rum" #(rum-render test-data))
        (.add "fulcro-dom" #(fulcro-dom-render test-data))
        (.on "cycle" log-cycle)
        ;; (.on "complete" log-complete)
        (.run))))