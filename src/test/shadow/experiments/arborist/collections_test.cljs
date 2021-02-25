(ns shadow.experiments.arborist.collections-test
  (:require
    [cljs.test :as ct :refer (deftest is)]
    [shadow.experiments.grove :as sg :refer (<<)]
    [shadow.experiments.arborist :as sa]))

(defn ^:dev/before-load clear-console []
  (js/console.clear))

(defn with-test-node [callback]
  (let [node (js/document.createElement "div")]
    (js/document.body.append node)
    (callback node (sa/dom-root node {}))
    (.remove node)))

(defn item [x]
  (<< [:span x]))

(deftest can-swap-item-positions
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/render-seq [1 2 3] identity item))
      (is (= "123" (.-innerText node)))

      (sa/update! root (sg/render-seq [3 2 1] identity item))
      (is (= "321" (.-innerText node)))
      )))

(deftest can-mix-new-and-old
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/render-seq [1 2 3] identity item))
      (is (= "123" (.-innerText node)))

      (sa/update! root (sg/render-seq [3 4 1] identity item))
      (is (= "341" (.-innerText node))))))

(deftest can-replace-all
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/render-seq [1 2 3] identity item))
      (is (= "123" (.-innerText node)))

      (sa/update! root (sg/render-seq [4 5 6] identity item))
      (is (= "456" (.-innerText node)))
      )))

(deftest can-remove-all
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/render-seq [1 2 3] identity item))
      (is (= "123" (.-innerText node)))

      (sa/update! root (sg/render-seq [] identity item))
      (is (= "" (.-innerText node)))
      )))

(deftest can-add-item-at-start
  (with-test-node
    (fn [node root]
      (let [root (sa/dom-root node {})]
        (sa/update! root (sg/render-seq [1 2 3] identity item))
        (is (= "123" (.-innerText node)))

        (sa/update! root (sg/render-seq [0 1 2 3] identity item))
        (is (= "0123" (.-innerText node)))
        ))))

(deftest can-replace-with-fewer-items
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/render-seq [1 2 3] identity item))
      (is (= "123" (.-innerText node)))

      (sa/update! root (sg/render-seq [4] identity item))
      (is (= "4" (.-innerText node)))
      )))

(deftest can-replace-with-more-items
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/render-seq [1 2 3] identity item))
      (is (= "123" (.-innerText node)))

      (sa/update! root (sg/render-seq [4 5 6 7] identity item))
      (is (= "4567" (.-innerText node)))
      )))

(deftest can-add-items-at-end
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/render-seq [1 2 3] identity item))
      (is (= "123" (.-innerText node)))

      (sa/update! root (sg/render-seq [1 2 3 4] identity item))
      (is (= "1234" (.-innerText node))))))

