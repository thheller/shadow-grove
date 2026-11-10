(ns shadow.arborist.keyed-seq-test
  (:require
    [shadow.arborist.test-dom :as test-dom]
    [cljs.test :as ct :refer (deftest is)]
    [shadow.grove :as sg :refer (<<)]
    [shadow.arborist :as sa]))

(defn ^:dev/before-load clear-console []
  (js/console.clear))

(defn with-test-node [callback]
  (test-dom/use-document!)
  (let [node (js/document.createElement "div")]
    (js/document.body.append node)
    (callback node (sa/dom-root node {}))
    (.remove node)))

(defn item [x]
  (<< [:span x]))

(deftest can-swap-item-positions
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/keyed-seq [1 2 3] identity item))
      (is (= "123" (.-textContent node)))

      (sa/update! root (sg/keyed-seq [3 2 1] identity item))
      (is (= "321" (.-textContent node)))
      )))

(deftest can-mix-new-and-old
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/keyed-seq [1 2 3] identity item))
      (is (= "123" (.-textContent node)))

      (sa/update! root (sg/keyed-seq [3 4 1] identity item))
      (is (= "341" (.-textContent node))))))

(deftest can-replace-all
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/keyed-seq [1 2 3] identity item))
      (is (= "123" (.-textContent node)))

      (sa/update! root (sg/keyed-seq [4 5 6] identity item))
      (is (= "456" (.-textContent node)))
      )))

(deftest can-remove-all
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/keyed-seq [1 2 3] identity item))
      (is (= "123" (.-textContent node)))

      (sa/update! root (sg/keyed-seq [] identity item))
      (is (= "" (.-textContent node)))
      )))

(deftest can-add-item-at-start
  (with-test-node
    (fn [node root]
      (let [root (sa/dom-root node {})]
        (sa/update! root (sg/keyed-seq [1 2 3] identity item))
        (is (= "123" (.-textContent node)))

        (sa/update! root (sg/keyed-seq [0 1 2 3] identity item))
        (is (= "0123" (.-textContent node)))
        ))))

(deftest can-replace-with-fewer-items
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/keyed-seq [1 2 3] identity item))
      (is (= "123" (.-textContent node)))

      (sa/update! root (sg/keyed-seq [4] identity item))
      (is (= "4" (.-textContent node)))
      )))

(deftest can-replace-with-more-items
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/keyed-seq [1 2 3] identity item))
      (is (= "123" (.-textContent node)))

      (sa/update! root (sg/keyed-seq [4 5 6 7] identity item))
      (is (= "4567" (.-textContent node)))
      )))

(deftest can-add-items-at-end
  (with-test-node
    (fn [node root]
      (sa/update! root (sg/keyed-seq [1 2 3] identity item))
      (is (= "123" (.-textContent node)))

      (sa/update! root (sg/keyed-seq [1 2 3 4] identity item))
      (is (= "1234" (.-textContent node))))))

