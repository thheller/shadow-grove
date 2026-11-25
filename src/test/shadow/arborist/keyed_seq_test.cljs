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


;; each

(def render-count (atom 0))

(defn counted [label x]
  (swap! render-count inc)
  (<< [:span label x]))

(defn keyed-each-view [items label]
  (<< [:div (sg/each items identity [x] (counted label x))]))

(deftest each-skips-items-when-deps-equal
  (with-test-node
    (fn [node root]
      (reset! render-count 0)
      (sa/update! root (keyed-each-view [1 2 3] "a"))
      (is (= "a1a2a3" (.-textContent node)))
      (is (= 3 @render-count))

      ;; new fn, = coll and = label, nothing renders
      (sa/update! root (keyed-each-view [1 2 3] (str "a")))
      (is (= 3 @render-count))

      ;; only the new item renders
      (sa/update! root (keyed-each-view [1 2 3 4] "a"))
      (is (= "a1a2a3a4" (.-textContent node)))
      (is (= 4 @render-count))

      ;; dep changed, everything renders again
      (sa/update! root (keyed-each-view [1 2 3 4] "b"))
      (is (= "b1b2b3b4" (.-textContent node)))
      (is (= 8 @render-count)))))

(defn simple-each-view [items label]
  (<< [:div (sg/each items [x idx] (counted label (str x idx)))]))

(deftest simple-each-skips-items-when-deps-equal
  (with-test-node
    (fn [node root]
      (reset! render-count 0)
      (sa/update! root (simple-each-view [1 2] "a"))
      (is (= "a10a21" (.-textContent node)))
      (is (= 2 @render-count))

      (sa/update! root (simple-each-view [1 2] "a"))
      (is (= 2 @render-count))

      (sa/update! root (simple-each-view [1 3] "a"))
      (is (= "a10a31" (.-textContent node)))
      (is (= 3 @render-count))

      (sa/update! root (simple-each-view [1 3] "b"))
      (is (= "b10b31" (.-textContent node)))
      (is (= 5 @render-count)))))

(defn each-without-fragment [items label]
  (sg/each items identity [x] (counted label x)))

(deftest each-skips-items-outside-fragment
  (with-test-node
    (fn [node root]
      (reset! render-count 0)
      ;; not identical coll, so dom-sync! runs instead of -equiv short circuiting
      (sa/update! root (each-without-fragment [1 2 3] "a"))
      (sa/update! root (each-without-fragment (vec (list 1 2 3)) "a"))
      (is (= 3 @render-count))
      (sa/update! root (each-without-fragment [1 2 3] "b"))
      (is (= "b1b2b3" (.-textContent node)))
      (is (= 6 @render-count)))))

(defn each-branches [flag items]
  (<< [:div
       (if flag
         (sg/each items identity [x] (<< [:b x]))
         (sg/each items identity [x] (<< [:i x])))]))

(deftest each-sites-differ-with-equal-deps
  (with-test-node
    (fn [node root]
      (sa/update! root (each-branches true [1 2]))
      (is (= 2 (.-length (.querySelectorAll node "b"))))
      (sa/update! root (each-branches false [1 2]))
      (is (= 0 (.-length (.querySelectorAll node "b"))))
      (is (= 2 (.-length (.querySelectorAll node "i")))))))

(defn idx-free-view [items]
  (<< [:div (sg/each items identity [x] (counted "" x))]))

(defn idx-view [items]
  (<< [:div (sg/each items identity [x idx] (counted x (str "@" idx)))]))

(deftest each-without-idx-only-moves
  (with-test-node
    (fn [node root]
      (reset! render-count 0)
      (sa/update! root (idx-free-view [1 2 3 4]))
      (is (= 4 @render-count))
      (let [first-span (.querySelector node "span")]
        ;; remove first, reverse, insert first: no render at all
        (sa/update! root (idx-free-view [2 3 4]))
        (is (= "234" (.-textContent node)))
        (sa/update! root (idx-free-view [4 3 2]))
        (is (= "432" (.-textContent node)))
        (sa/update! root (idx-free-view [1 4 3 2]))
        (is (= "1432" (.-textContent node)))
        ;; only the reinserted 1 rendered, as a new item
        (is (= 5 @render-count))
        (is (not (identical? first-span (.querySelector node "span"))))))))

(deftest each-with-idx-renders-moved-items
  (with-test-node
    (fn [node root]
      (reset! render-count 0)
      (sa/update! root (idx-view [1 2 3]))
      (is (= "1@02@13@2" (.-textContent node)))
      (is (= 3 @render-count))
      (sa/update! root (idx-view [2 3]))
      (is (= "2@03@1" (.-textContent node)))
      (is (= 5 @render-count)))))

;; keyed-seq idx-free? arity

(defn idx-row [x idx]
  (counted x (str "@" idx)))

(deftest keyed-seq-idx-free-only-moves
  (with-test-node
    (fn [node root]
      (reset! render-count 0)
      (sa/update! root (sg/keyed-seq [1 2 3] identity idx-row true))
      (is (= 3 @render-count))
      ;; moved items keep what they rendered, idx is stale on purpose here
      (sa/update! root (sg/keyed-seq [3 1 2] identity idx-row true))
      (is (= "3@21@02@1" (.-textContent node)))
      (is (= 3 @render-count))
      ;; without the flag the same fn renders the moved items again
      (sa/update! root (sg/keyed-seq [2 3 1] identity idx-row false))
      (is (= "2@03@11@2" (.-textContent node)))
      (is (= 6 @render-count)))))

(deftest keyed-seq-idx-free-flag-change-renders-all
  (with-test-node
    (fn [node root]
      (reset! render-count 0)
      (sa/update! root (sg/keyed-seq [1 2 3] identity idx-row true))
      (sa/update! root (sg/keyed-seq [2 3] identity idx-row true))
      (is (= "2@13@2" (.-textContent node)))
      (is (= 3 @render-count))
      ;; same fn, same order, flag dropped: rows that showed a stale idx render again
      (sa/update! root (sg/keyed-seq [2 3] identity idx-row false))
      (is (= "2@03@1" (.-textContent node)))
      (is (= 5 @render-count)))))
