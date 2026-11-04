(ns shadow.arborist.diff-seq-test
  (:require
    ["jsdom" :refer (JSDOM)]
    [cljs.test :as ct :refer (deftest is testing)]
    [clojure.string :as str]
    [shadow.grove :refer (<<)]
    [shadow.arborist :as sa]
    [shadow.arborist.collections :as coll]
    [shadow.arborist.interpreted]))

(defonce dom
  (let [dom (JSDOM. "<!DOCTYPE html>")]
    (set! js/global -document (-> dom .-window .-document))
    dom))

(defn with-test-node [callback]
  (let [node (js/document.createElement "div")]
    (js/document.body.append node)
    (callback node (sa/dom-root node {}))
    (.remove node)))

(def renders (atom 0))

(defn item [x]
  (swap! renders inc)
  (<< [:b (if (map? x) (:v x) (str x))]))

(defn init
  "bypasses the empty check in diff-seq so syncing to/from empty is covered"
  ([xs] (init xs item false))
  ([xs rfn by-value?] (coll/DiffCollectionInit. (vec xs) rfn by-value? nil)))

(defn text [node]
  (->> (.querySelectorAll node "b")
       (array-seq)
       (map #(.-textContent %))
       (str/join ",")))

(defn expected [xs]
  (->> xs (map #(if (map? %) (:v %) %)) (str/join ",")))

(def transitions
  [[[1 2 3] [3 2 1]]
   [[1 2 3] [3 4 1]]
   [[1 2 3] [4 5 6]]
   [[1 2 3] []]
   [[] [1 2 3]]
   [[1 2 3] [0 1 2 3]]
   [[1 2 3] [1 2 3 4]]
   [[1 2 3] [1 3]]
   [[1 2 3] [4]]
   [[1 2 3] [4 5 6 7]]
   [[1 2 3 4 5] [5 2 3 4 1]]
   [[1 2 3 4 5] [2 3 4 5 1]]
   [[1 2 3 4 5] [5 1 2 3 4]]
   [[1 1 1] [1 1]]
   [[1 1 1] [1 2 1 1]]
   [[1 2 1 2] [2 1 2 1]]
   [[1 2 1 2] [2 2 1 1]]
   [[1 1 2 2 3 3] [3 2 1 3 2 1]]
   [["a" "b" "a"] ["b" "a" "a" "b"]]])

(deftest fixed-transitions
  (doseq [by-value? [false true]
          [from to] transitions]
    (with-test-node
      (fn [node root]
        (sa/update! root (init from item by-value?))
        (is (= (expected from) (text node)))
        (sa/update! root (init to item by-value?))
        (is (= (expected to) (text node)) (pr-str [:by-value by-value? from :-> to]))))))

(deftest remove-first-keeps-dom-and-skips-render
  (with-test-node
    (fn [node root]
      (sa/update! root (init ["a" "b" "c" "d"]))
      (let [[_ b c d] (array-seq (.querySelectorAll node "b"))]
        (reset! renders 0)
        (sa/update! root (init ["b" "c" "d"]))
        (is (= 0 @renders))
        (is (= [b c d] (array-seq (.querySelectorAll node "b"))))))))

(deftest swap-moves-without-render
  (with-test-node
    (fn [node root]
      (sa/update! root (init [1 2 3 4 5 6]))
      (let [[n1 n2 n3 n4 n5 n6] (array-seq (.querySelectorAll node "b"))]
        (reset! renders 0)
        (sa/update! root (init [1 5 3 4 2 6]))
        (is (= 0 @renders))
        (is (= [n1 n5 n3 n4 n2 n6] (array-seq (.querySelectorAll node "b"))))))))

(deftest update-in-place-renders-once
  (with-test-node
    (fn [node root]
      (let [a {:v "a"} b {:v "b"} c {:v "c"}]
        (sa/update! root (init [a b c]))
        (let [nodes (array-seq (.querySelectorAll node "b"))]
          (reset! renders 0)
          (sa/update! root (init [a {:v "B"} c]))
          (is (= 1 @renders))
          (is (= "a,B,c" (text node)))
          (is (= nodes (array-seq (.querySelectorAll node "b")))))))))

(deftest by-value-matches-fresh-equal-maps
  (with-test-node
    (fn [node root]
      (let [xs (mapv (fn [i] {:v (str "i" i)}) (range 5))
            fresh (mapv #(into {} %) xs)]
        (sa/update! root (init xs item true))
        (let [nodes (vec (array-seq (.querySelectorAll node "b")))]
          (reset! renders 0)
          ;; fresh but equal maps, first removed
          (sa/update! root (init (subvec fresh 1) item true))
          (is (= 0 @renders))
          (is (= (subvec nodes 1) (vec (array-seq (.querySelectorAll node "b")))))

          ;; identity mode must re-render them
          (reset! renders 0)
          (sa/update! root (init (mapv #(into {} %) (subvec xs 1)) item false))
          (is (= 4 @renders))
          (is (= "i1,i2,i3,i4" (text node))))))))

(deftest rotate-moves-one-without-render
  (with-test-node
    (fn [node root]
      (sa/update! root (init [1 2 3 4 5]))
      (let [[n1 n2 n3 n4 n5] (array-seq (.querySelectorAll node "b"))]
        (reset! renders 0)
        (sa/update! root (init [2 3 4 5 1]))
        (is (= 0 @renders))
        (is (= [n2 n3 n4 n5 n1] (array-seq (.querySelectorAll node "b"))))
        (sa/update! root (init [1 2 3 4 5]))
        (is (= 0 @renders))
        (is (= [n1 n2 n3 n4 n5] (array-seq (.querySelectorAll node "b"))))))))

(deftest scattered-updates-render-in-place
  (with-test-node
    (fn [node root]
      (let [xs (mapv (fn [i] {:v (str "i" i)}) (range 20))]
        (sa/update! root (init xs))
        (let [nodes (array-seq (.querySelectorAll node "b"))
              ys (reduce #(assoc %1 %2 {:v (str "u" %2)}) xs (range 0 20 5))]
          (reset! renders 0)
          (sa/update! root (init ys))
          (is (= 4 @renders))
          (is (= (expected ys) (text node)))
          (is (= nodes (array-seq (.querySelectorAll node "b")))))))))

(defn rand-coll [alphabet max-len]
  (vec (repeatedly (rand-int (inc max-len)) #(rand-nth alphabet))))

(defn remove-at [xs i]
  (into (subvec xs 0 i) (subvec xs (inc i))))

(defn insert-at [xs i x]
  (-> (subvec xs 0 i) (conj x) (into (subvec xs i))))

(defn mutate [xs alphabet max-len]
  (let [n (count xs)]
    (case (if (zero? n) 0 (rand-int 9))
      0 (rand-coll alphabet max-len)
      1 (vec (shuffle xs))
      2 (remove-at xs (rand-int n))
      3 (insert-at xs (rand-int (inc n)) (rand-nth alphabet))
      4 (assoc xs (rand-int n) (rand-nth alphabet))
      5 (vec (reverse xs))
      ;; swap two
      6 (let [a (rand-int n) b (rand-int n)] (assoc xs a (nth xs b) b (nth xs a)))
      ;; move one
      7 (let [i (rand-int n) x (nth xs i) ys (remove-at xs i)] (insert-at ys (rand-int n) x))
      ;; replace a few in place
      8 (reduce (fn [xs _] (assoc xs (rand-int n) (rand-nth alphabet))) xs (range (inc (rand-int 3)))))))

(defn fuzz [alphabet max-len by-value? fresh-copies?]
  (with-test-node
    (fn [node root]
      (loop [i 0 xs []]
        (when (< i 3000)
          (let [next (mutate xs alphabet max-len)
                next (if fresh-copies? (mapv #(if (and (map? %) (zero? (rand-int 2))) (into {} %) %) next) next)
                ;; inline fn every other round, forces render of everything
                rfn (if (even? i) item (fn [x idx] (item x)))]
            (sa/update! root (init next rfn by-value?))
            (if (= (expected next) (text node))
              (recur (inc i) next)
              (is false (pr-str {:by-value by-value? :from xs :to next :got (text node)})))))))))

(deftest fuzz-identity
  (fuzz [1 2 3 4 5 "a" "b" :k] 12 false false))

(deftest fuzz-by-value
  (fuzz [1 2 3 "a" "b" {:v "m1"} {:v "m2"} {:v "m3"}] 12 true true))

(deftest fuzz-identity-with-fresh-maps
  (fuzz [{:v "m1"} {:v "m2"} {:v "m3"} {:v "m4"}] 12 false true))

;; mostly distinct items, longer lists, more moves
(deftest fuzz-identity-long
  (fuzz (vec (range 60)) 40 false false))

(deftest fuzz-by-value-long
  (fuzz (mapv (fn [i] {:v (str "m" i)}) (range 60)) 40 true true))

;; keyed

(defn keyed-item [x idx key]
  (swap! renders inc)
  (<< [:b {:data-k (str key)} (:v x)]))

(defn keyed-init
  ([xs] (keyed-init xs keyed-item))
  ([xs rfn] (coll/DiffCollectionInit. (vec xs) rfn false :id)))

(defn kv [id v] {:id id :v v})

(defn kexpected [xs]
  (->> xs (map :v) (str/join ",")))

(def keyed-transitions
  [[[1 2 3] [3 2 1]]
   [[1 2 3] [3 4 1]]
   [[1 2 3] [4 5 6]]
   [[1 2 3] []]
   [[] [1 2 3]]
   [[1 2 3] [0 1 2 3]]
   [[1 2 3] [1 2 3 4]]
   [[1 2 3] [1 3]]
   [[1 2 3] [4]]
   [[1 2 3 4 5] [5 2 3 4 1]]
   [[1 2 3 4 5] [2 3 4 5 1]]
   [[1 2 3 4 5] [5 1 2 3 4]]
   [[1 2 3 4 5] [1 9 3 8 5]]])

(deftest keyed-fixed-transitions
  (doseq [[from to] keyed-transitions]
    (with-test-node
      (fn [node root]
        (let [from (mapv #(kv % (str "v" %)) from)
              to (mapv #(kv % (str "v" %)) to)]
          (sa/update! root (keyed-init from))
          (is (= (kexpected from) (text node)))
          (sa/update! root (keyed-init to))
          (is (= (kexpected to) (text node)) (pr-str [from :-> to])))))))

(deftest keyed-update-renders-in-place
  (with-test-node
    (fn [node root]
      (let [xs (mapv #(kv % (str "v" %)) (range 5))]
        (sa/update! root (keyed-init xs))
        (let [nodes (array-seq (.querySelectorAll node "b"))]
          (reset! renders 0)
          ;; same keys, new content for 2 items, everything else identical
          (sa/update! root (keyed-init (-> xs (assoc 1 (kv 1 "x")) (assoc 3 (kv 3 "y")))))
          (is (= 2 @renders))
          (is (= "v0,x,v2,y,v4" (text node)))
          (is (= nodes (array-seq (.querySelectorAll node "b"))))

          ;; fresh equal maps with same keys re-render (data not identical), keep DOM
          (reset! renders 0)
          (sa/update! root (keyed-init (mapv #(into {} %) xs)))
          (is (= 5 @renders))
          (is (= nodes (array-seq (.querySelectorAll node "b")))))))))

(deftest keyed-never-renders-over-other-key
  (with-test-node
    (fn [node root]
      (let [a (kv 1 "a") c (kv 3 "c")]
       (sa/update! root (keyed-init [a (kv 2 "b") c]))
       (let [[n1 _ n3] (array-seq (.querySelectorAll node "b"))]
        (reset! renders 0)
        ;; 2 replaced by 9 in the middle. unkeyed would render 9 over 2
        (sa/update! root (keyed-init [a (kv 9 "z") c]))
        (is (= 1 @renders))
        (let [[m1 m9 m3] (array-seq (.querySelectorAll node "b"))]
          (is (identical? n1 m1))
          (is (identical? n3 m3))
          (is (= "9" (.getAttribute m9 "data-k")))))))))

(deftest keyed-duplicate-keys-throw-in-dev
  (is (thrown? js/Error (coll/keyed-seq [(kv 1 "a") (kv 1 "b")] :id keyed-item))))

(deftest interpreted-hiccup-seq-with-dom-key
  ;; lazy seqs of hiccup with :dom/key construct a keyed-seq
  (with-test-node
    (fn [node root]
      (sa/update! root (for [x [1 2 3]] [:b {:dom/key x} (str x)]))
      (is (= "1,2,3" (text node)))
      (is (instance? coll/DiffCollection (.-node (.-root root))))
      (sa/update! root (for [x [3 1 4]] [:b {:dom/key x} (str x)]))
      (is (= "3,1,4" (text node))))))

(defn keyed-mutate [xs pool]
  (let [n (count xs)
        present (set (map :id xs))
        absent (remove present pool)
        fresh-id #(rand-nth absent)]
    (case (if (zero? n) 0 (rand-int 9))
      0 (mapv #(kv % (str "v" %)) (take (rand-int 30) (shuffle pool)))
      1 (vec (shuffle xs))
      2 (remove-at xs (rand-int n))
      3 (if (seq absent) (let [id (fresh-id)] (insert-at xs (rand-int (inc n)) (kv id (str "v" id)))) xs)
      ;; new content, same key
      4 (let [i (rand-int n)] (update xs i assoc :v (str "u" (rand-int 1000))))
      5 (vec (reverse xs))
      6 (let [a (rand-int n) b (rand-int n)] (assoc xs a (nth xs b) b (nth xs a)))
      7 (let [i (rand-int n) x (nth xs i) ys (remove-at xs i)] (insert-at ys (rand-int n) x))
      ;; replace an item with a new key in place
      8 (if (seq absent) (let [id (fresh-id)] (assoc xs (rand-int n) (kv id (str "v" id)))) xs))))

(deftest fuzz-keyed
  (with-test-node
    (fn [node root]
      (let [pool (vec (range 50))
            ;; DOM node -> key it was created for, must never change
            node-keys (js/Map.)]
        (loop [i 0 xs []]
          (when (< i 3000)
            (let [next (keyed-mutate xs pool)
                  next (mapv #(if (zero? (rand-int 3)) (into {} %) %) next)
                  rfn (if (even? i) keyed-item (fn [x idx key] (keyed-item x idx key)))]
              (sa/update! root (keyed-init next rfn))
              (let [bad-node
                    (some
                      (fn [el]
                        (let [k (.getAttribute el "data-k")
                              prev (.get node-keys el)]
                          (if (and prev (not= prev k))
                            [prev k]
                            (do (.set node-keys el k) nil))))
                      (array-seq (.querySelectorAll node "b")))]
                (cond
                  (not= (kexpected next) (text node))
                  (is false (pr-str {:from xs :to next :got (text node)}))

                  bad-node
                  (is false (pr-str {:rendered-over bad-node :from xs :to next}))

                  :else
                  (recur (inc i) next))))))))))
