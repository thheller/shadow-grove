(ns shadow.grove.test-app.dom
  (:require
    [shadow.arborist :as sa :refer (<<)]
    [shadow.grove.components :as sac :refer (defc)]
    [clojure.string :as str]
    [shadow.arborist.protocols :as p]))

(def items
  (into [] (for [i (range 100)]
             {:id i :test (str "foo" i)})))

(defc nested
  [{:keys [id x class] :as props}]
  []
  (<< "foo"
      [:div {:class class}
       [:h2 "nested instance" id "/" x]]
      "bar"))

(defc component
  [{:keys [num class] :as props}]
  [children (sac/slot)]
  ;; (js/console.log "component render" props children)
  (<< [:div {:class ["hello" "world" class]}
       [:div "component instance" num]
       children]
      [:div
       (nested props)]))

(defc root
  {:init-state {:num 0}}
  [props {:keys [num] :as state}]
  [other
   (<< [:h1 "@" num])

   items
   (->> items
        (shuffle)
        (take 75))

   item-row
   (fn [{:keys [id test]}]
     ;; closed over state
     (nested {:x num :id id :class test}))

   ::inc
   (fn [env e]
     ;; (js/console.log ::inc env e)
     (sac/swap-state! env update :num inc))]

  (<< [:div "before"]
      [:div.card {:on-click [::inc]}

       [:div.card-header {:style {:color "red"}} other]
       [:div.card-body {:data-x num}
        [:h2 "props"]
        [:div (pr-str props)]
        [:h2 "state"]
        [:div (pr-str state)]]

       [component {:x num :class (str "class" num)}
        [:div "a" num]
        [:div "b" num]
        [:div "c" num]]

       [:div (->> items (map :id) pr-str)]

       (sa/render-seq items :id item-row)

       [:div.card-footer
        [:button {:type "button" :on-click [::foo 1 2 3]} "OK"]]]
      [:div "after"]))

(defonce root-ref (atom nil))

(defn ^:dev/after-load start []
  (let [container
        (js/document.getElementById "app")

        dom-root
        (sa/dom-root container {})]

    (sa/update! dom-root (root {:props "yo"}))

    (reset! root-ref dom-root)))

(defn ^:dev/before-load reset-root! []
  (when-let [root @root-ref]
    (sa/destroy! root)
    (reset! root-ref nil)))

(defn init []
  (js/setTimeout start 0))

(defn bar [x] (js/console.log (x 1)))

(defn foo [x]
  (when (pos? x)
    (bar (fn [i] (+ i x)))
    (recur (dec x))))