(ns test.samples
  (:require
    ["react-dom" :as rdom]
    ["react" :as react :rename {createElement el}]
    [reagent.core :as reagent]
    [re-com.core :as rc]
    [shadow.arborist :as sa]
    [shadow.grove :as sg :refer (<<)]
    [shadow.arborist.interpreted]))

(def dom-root
  (js/document.getElementById "root"))

(defn grey-box []
  [:div.border.border-grey-500.bg-gray-100.p-8 "box"])

(defn example-recom []
  [rc/h-box
   :gap "10px"
   :children [[grey-box]
              [grey-box]
              [rc/gap :size "5px"]
              [grey-box]]])

(defn start-recom []
  (dotimes [x 10]
    (time
      (let [node (js/document.createElement "div")]
        (.append dom-root node)

        (rdom/render
          (reagent/as-element [example-recom])
          node)))))

(defn example-reagent []
  [:div.flex
   [:div.border.border-grey-500.bg-gray-100.p-8.mr-4 "box"]
   [:div.border.border-grey-500.bg-gray-100.p-8.mr-8 "box"]
   [:div.border.border-grey-500.bg-gray-100.p-8.mr-4 "box"]])

(defn start-reagent []
  (dotimes [x 10]
    (time
      (let [node (js/document.createElement "div")]
        (.append dom-root node)

        (rdom/render
          (reagent/as-element [example-recom])
          node)))))

(defn example-react []
  (el "div" #js {:className "flex"}
    (el "div" #js {:className "border border-grey-500 bg-gray-100 p-8 mr-4"} "box")
    (el "div" #js {:className "border border-grey-500 bg-gray-100 p-8 mr-4"} "box")
    (el "div" #js {:className "border border-grey-500 bg-gray-100 p-8 mr-4"} "box")
    ))


(defn start-react []
  (dotimes [x 10]
    (time
      (let [node (js/document.createElement "div")]
        (.append dom-root node)

        (rdom/render
          (el example-react nil)
          node)))))

(defn example-grove []
  (<< [:div.flex
       [:div.border.border-grey-500.bg-gray-100.p-8.mr-4 "box"]
       [:div.border.border-grey-500.bg-gray-100.p-8.mr-8 "box"]
       [:div.border.border-grey-500.bg-gray-100.p-8.mr-4 "box"]]))

(defn box [title body]
  (<< [:div.border.shadow-lg
       [:div title]
       body]))

(defn example-grove2 []
  (<< [:div.flex
       [:div.mr-4 (box "title a" "box")]
       [:div.mr-8 (box "title b" "box")]
       [:div.mr-4 (box "title c" "box")]]))

(defn example-grove3 []
  (<< [:div.flex
       [:div.mr-4
        (box "title a"
          (<< [:div.font-bold "box"]))]
       [:div.mr-8
        (box "title b"
          (<< [:div.p-8 "box"]))]
       [:div.mr-4
        (box "title c"
          (<< [:div.text-2xl "box"]))]]))

(defn example-grove4 []
  (<< [:div.flex
       [:div.mr-4
        (box
          (<< "title a" [:sup "1"])
          (<< [:div.font-bold "box"]))]
       [:div.mr-8
        (box
          (<< "title " [:span.font-bold "b"])
          (<< [:div.p-8 "box"]))]
       [:div.mr-4
        (box
          "title c"
          (<< [:div.text-2xl "box"]))]]))

(defn ^:dev/after-load start-grove []
  (dotimes [x 10]
    (time
      (let [node (js/document.createElement "div")]
        (.append dom-root node)

        (let [root (sa/dom-root node {})]
          (sa/update! root (example-recom))
          )))))

(def example-html
  "<div class=\"rc-h-box display-flex \" style=\"flex-flow: row nowrap; flex: 0 0 auto; justify-content: flex-start; align-items: stretch;\">\n  <div class=\"border border-grey-500 bg-gray-100 p-8\">box</div>\n  <div class=\"rc-gap \" style=\"flex: 0 0 10px; width: 10px;\"></div>\n  <div class=\"border border-grey-500 bg-gray-100 p-8\">box</div>\n  <div class=\"rc-gap \" style=\"flex: 0 0 10px; width: 10px;\"></div>\n  <div class=\"rc-gap \" style=\"flex: 0 0 5px;\"></div>\n  <div class=\"rc-gap \" style=\"flex: 0 0 10px; width: 10px;\"></div>\n  <div class=\"border border-grey-500 bg-gray-100 p-8\">box</div>\n</div>")

(defn start-innerhtml []
  (dotimes [x 10]
    (time
      (let [node (js/document.createElement "div")]
        (.append dom-root node)

        (set! node -innerHTML example-html)))))

(defn init []
  ;; give stuff time to settle before rendering anything
  (js/setTimeout start-grove 2000))
