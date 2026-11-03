(ns shadow.arborist.bench-seq
  "simple-seq vs keyed-seq vs diff-seq. run via examples/bench-seq/run.mjs"
  (:require
    [shadow.grove :refer (<<)]
    [shadow.arborist :as sa]
    [shadow.arborist.collections :as coll]))

(def stats #js {:renders 0 :sink 0})

(defn row [item idx]
  (set! (.-renders stats) (inc (.-renders stats)))
  (<< [:tr {:class (when (:done item) "done")}
       [:td.id (:id item)]
       [:td [:a {:href (:href item)} (:label item)]]
       [:td.count (:count item)]]))

(defn str-row [s idx]
  (set! (.-renders stats) (inc (.-renders stats)))
  (<< [:tr [:td s]]))

(defn make-item [i]
  {:id i
   :label (str "item " i)
   :href (str "#item-" i)
   :count (* i 7)
   :done (zero? (mod i 3))})

(defn items [from n]
  (mapv make-item (range from (+ from n))))

(defn tags [n]
  (mapv #(str "tag-" (mod % 7)) (range n)))

;; equal content, new instances, no cached hash. like a query doing (map #(assoc % ...))
(defn fresh [xs]
  (mapv #(if (map? %) (into {} %) %) xs))

(defn remove-at [xs i]
  (into (subvec xs 0 i) (subvec xs (inc i))))

(defn shuffle-seeded [xs seed]
  (let [arr (into-array xs)]
    (loop [i (dec (alength arr)) x seed]
      (if (pos? i)
        (let [x (bit-xor x (bit-shift-left x 13))
              x (bit-xor x (unsigned-bit-shift-right x 17))
              x (bit-xor x (bit-shift-left x 5))
              j (js-mod (unsigned-bit-shift-right x 0) (inc i))
              t (aget arr i)]
          (aset arr i (aget arr j))
          (aset arr j t)
          (recur (dec i) x))
        (vec arr)))))

(defn shuffle-changed [xs seed]
  (let [ys (shuffle-seeded xs seed)]
    (if (= ys xs)
      (conj (subvec xs 1) (first xs))
      ys)))

(defn swap-idx [xs a b]
  (assoc xs a (nth xs b) b (nth xs a)))

;; states-fn returns k states to apply in order, k always even, ends on init state
(defn alternate [a b]
  (fn [k]
    (let [arr (js/Array. k)]
      (dotimes [i k]
        (aset arr i (if (even? i) b a)))
      arr)))

(defn alternate-fresh [a b]
  (fn [k]
    (let [arr (js/Array. k)]
      (dotimes [i k]
        (aset arr i (fresh (if (even? i) b a))))
      arr)))

(def scenarios
  [{:id "remove-first" :min-n 1 :label "remove first ⇄ insert first"
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate a (remove-at a 0))}))}

   {:id "remove-middle" :min-n 3 :label "remove middle ⇄ insert middle"
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate a (remove-at a (quot n 2)))}))}

   {:id "remove-last" :min-n 2 :label "remove last ⇄ append"
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate a (pop a))}))}

   {:id "swap" :min-n 4 :label "swap rows 1 and n-2"
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate a (swap-idx a 1 (- n 2)))}))}

   {:id "update-1" :min-n 1 :label "update 1 row"
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate a (update-in a [(quot n 2) :count] inc))}))}

   {:id "update-10th" :min-n 20 :label "update every 10th row"
    :setup (fn [n] (let [a (items 0 n)]
                     {:init a
                      :states (alternate a (reduce #(update-in %1 [%2 :count] inc) a (range 0 n 10)))}))}

   {:id "reverse" :min-n 2 :label "reverse"
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate a (vec (rseq a)))}))}

   {:id "shuffle" :min-n 3 :label "shuffle"
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate a (shuffle-changed a 12345))}))}

   {:id "replace-all" :min-n 1 :label "replace all (no overlap)"
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate a (items n n))}))}

   {:id "fresh-nochange" :min-n 1 :label "fresh equal maps, no change"
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate-fresh a a)}))}

   {:id "fresh-remove-first" :min-n 2 :label "fresh equal maps, remove first ⇄ insert first"
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate-fresh a (remove-at a 0))}))}

   {:id "dup-strings-remove-first" :min-n 2 :label "strings w/ duplicates, remove first ⇄ insert first"
    :rfn str-row
    :no-keyed true
    :setup (fn [n] (let [a (tags n)] {:init a :states (alternate a (remove-at a 0))}))}

   {:id "inline-remove-first" :min-n 2 :label "inline render fn, remove first ⇄ insert first"
    :inline true
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate a (remove-at a 0))}))}

   {:id "inline-swap" :min-n 4 :label "inline render fn, swap rows 1 and n-2"
    :inline true
    :setup (fn [n] (let [a (items 0 n)] {:init a :states (alternate a (swap-idx a 1 (- n 2)))}))}])

(def impls
  [{:id "simple-seq" :make (fn [xs rfn] (coll/simple-seq xs rfn))}
   {:id "keyed-seq :id" :keyed true :make (fn [xs rfn] (coll/keyed-seq xs :id rfn))}
   {:id "keyed-seq new" :keyed true :make (fn [xs rfn] (coll/diff-keyed-seq xs :id rfn))}
   {:id "diff-seq" :make (fn [xs rfn] (coll/diff-seq xs rfn))}
   {:id "diff-seq =" :make (fn [xs rfn] (coll/diff-seq-by-value xs rfn))}])

(defn gc! []
  (when (exists? js/window.gc)
    (js/window.gc)))

(defn run-ops! [root make rfn inline? states layout-node]
  (dotimes [i (alength states)]
    (let [rfn (if inline? (fn [x idx] (rfn x idx)) rfn)]
      (sa/update! root (make (aget states i) rfn))
      (when layout-node
        ;; method call, closure would drop a plain .offsetHeight read
        (set! (.-sink stats) (+ (.-sink stats) (.-height (.getBoundingClientRect layout-node))))))))

(defn median [arr]
  (let [arr (.sort (.slice arr) (fn [a b] (- a b)))]
    (aget arr (quot (alength arr) 2))))

(defn timed-batches [run-batch! states-fn k batches]
  (let [out #js []]
    (dotimes [_ batches]
      (let [states (states-fn k)]
        (gc!)
        (let [t0 (js/performance.now)]
          (run-batch! states)
          (.push out (/ (* 1000 (- (js/performance.now) t0)) k)))))
    (median out)))

(defn calibrate [run-batch! states-fn target-ms]
  (let [t0 (js/performance.now)]
    (loop [ops 0]
      (let [elapsed (- (js/performance.now) t0)]
        (if (and (< elapsed target-ms) (< ops 20000))
          (do (run-batch! (states-fn 2))
              (recur (+ ops 2)))
          (let [k (js/Math.ceil (* ops (/ 25 (max elapsed 1))))]
            (max 4 (* 2 (js/Math.ceil (/ k 2))))))))))

(defn count-mutations [records]
  (reduce
    (fn [sum ^js r]
      (if (= "childList" (.-type r))
        (+ sum (.. r -addedNodes -length) (.. r -removedNodes -length))
        (inc sum)))
    0
    (array-seq records)))

(defn measure-cell [{:keys [make]} {:keys [setup rfn inline]} n]
  (let [{:keys [init states]} (setup n)
        rfn (or rfn row)
        table (js/document.createElement "table")
        tbody (js/document.createElement "tbody")
        _ (.appendChild table tbody)
        _ (js/document.body.appendChild table)
        root (sa/dom-root tbody {})
        _ (sa/update! root (make init rfn))

        run-script! #(run-ops! root make rfn inline % nil)
        run-layout! #(run-ops! root make rfn inline % table)

        k (calibrate run-script! states 25)
        _ (run-script! (states k))
        us (timed-batches run-script! states k 7)

        k-layout (calibrate run-layout! states 25)
        _ (run-layout! (states k-layout))
        us-layout (timed-batches run-layout! states k-layout 7)

        obs (js/MutationObserver. (fn []))
        _ (.observe obs tbody #js {:childList true :subtree true :attributes true :characterData true})
        _ (set! (.-renders stats) 0)
        _ (run-script! (states 2))
        mutations (/ (count-mutations (.takeRecords obs)) 2)
        renders (/ (.-renders stats) 2)]

    (.disconnect obs)
    (.destroy! ^js root true)
    (.remove table)

    {:us us :us-layout us-layout :renders renders :mutations mutations}))

;; matching strategies, old-arr/new-arr -> number of matched items

;; proposal: cljs map value -> js/Set of indexes
(defn match-cljs-map-set [old-arr new-arr]
  (let [m (loop [i 0 m (transient {})]
            (if (< i (alength old-arr))
              (let [v (aget old-arr i)
                    s (get m v)]
                (if s
                  (do (.add s i) (recur (inc i) m))
                  (recur (inc i) (assoc! m v (doto (js/Set.) (.add i))))))
              m))]
    (loop [j 0 matched 0]
      (if (< j (alength new-arr))
        (let [s (get m (aget new-arr j))]
          (if (and s (pos? (.-size s)))
            (let [k (.. s (values) (next) -value)]
              (.delete s k)
              (recur (inc j) (inc matched)))
            (recur (inc j) matched)))
        matched))))

;; js Map by identity -> js/Set of indexes
(defn match-js-map-set [old-arr new-arr]
  (let [m (js/Map.)]
    (dotimes [i (alength old-arr)]
      (let [v (aget old-arr i)
            s (.get m v)]
        (if s (.add s i) (.set m v (doto (js/Set.) (.add i))))))
    (loop [j 0 matched 0]
      (if (< j (alength new-arr))
        (let [s (.get m (aget new-arr j))]
          (if (and s (pos? (.-size s)))
            (let [k (.. s (values) (next) -value)]
              (.delete s k)
              (recur (inc j) (inc matched)))
            (recur (inc j) matched)))
        matched))))

;; what diff-seq does: js Map by identity -> head idx, Int32Array chain
(defn match-js-map-chain [old-arr new-arr]
  (let [n (alength old-arr)
        m (js/Map.)
        next-same (js/Int32Array. n)]
    (loop [k (dec n)]
      (when (>= k 0)
        (let [v (aget old-arr k)
              head (.get m v)]
          (aset next-same k (if (undefined? head) -1 head))
          (.set m v k)
          (recur (dec k)))))
    (loop [j 0 matched 0]
      (if (< j (alength new-arr))
        (let [v (aget new-arr j)
              head (.get m v)]
          (if (undefined? head)
            (recur (inc j) matched)
            (let [nxt (aget next-same head)]
              (if (neg? nxt) (.delete m v) (.set m v nxt))
              (recur (inc j) (inc matched)))))
        matched))))

;; what diff-seq-by-value does: js Map by hash -> head idx, chain, = check
(defn match-hash-chain [old-arr new-arr]
  (let [n (alength old-arr)
        m (js/Map.)
        next-same (js/Int32Array. n)]
    (loop [k (dec n)]
      (when (>= k 0)
        (let [h (hash (aget old-arr k))
              head (.get m h)]
          (aset next-same k (if (undefined? head) -1 head))
          (.set m h k)
          (recur (dec k)))))
    (loop [j 0 matched 0]
      (if (< j (alength new-arr))
        (let [v (aget new-arr j)
              h (hash v)
              k (loop [prev -1 k (.get m h)]
                  (cond
                    (or (undefined? k) (neg? k)) -1
                    (= v (aget old-arr k))
                    (let [nxt (aget next-same k)]
                      (cond
                        (not (neg? prev)) (aset next-same prev nxt)
                        (neg? nxt) (.delete m h)
                        :else (.set m h nxt))
                      k)
                    :else (recur k (aget next-same k))))]
          (recur (inc j) (if (neg? k) matched (inc matched))))
        matched))))

(def strategies
  [{:id "cljs map → js/Set (proposal)" :f match-cljs-map-set}
   {:id "js Map identity → js/Set" :f match-js-map-set}
   {:id "js Map identity → Int32 chain (diff-seq)" :f match-js-map-chain}
   {:id "js Map hash → chain + = (diff-seq =)" :f match-hash-chain}])

(def match-data
  [{:id "maps-shuffled" :label "maps, shuffled (same instances)"
    :setup (fn [n] (let [a (items 0 n) b (shuffle-seeded a 777)]
                     {:old a :new-fn (fn [k] (let [arr (js/Array. k)] (dotimes [i k] (aset arr i b)) arr))}))}
   {:id "maps-fresh-shuffled" :label "maps, shuffled, fresh equal copies"
    :setup (fn [n] (let [a (items 0 n) b (shuffle-seeded a 777)]
                     {:old a :new-fn (fn [k] (let [arr (js/Array. k)] (dotimes [i k] (aset arr i (fresh b))) arr))}))}
   {:id "strings-dups-shuffled" :label "strings w/ duplicates, shuffled"
    :setup (fn [n] (let [a (tags n) b (shuffle-seeded a 777)]
                     {:old a :new-fn (fn [k] (let [arr (js/Array. k)] (dotimes [i k] (aset arr i b)) arr))}))}])

(defn run-hash! [arr]
  (dotimes [i (alength arr)]
    (hash (aget arr i))))

(defn measure-match [{:keys [f]} {:keys [setup]} n]
  (let [{:keys [old new-fn]} (setup n)
        old-arr (into-array old)
        ;; new colls arrive as vectors, convert per op like an impl would
        run-batch! (fn [news]
               (dotimes [i (alength news)]
                 (set! (.-sink stats) (+ (.-sink stats) (f old-arr (into-array (aget news i)))))))
        ;; prime cached hashes on old items, as a previous sync would have
        _ (run-hash! old-arr)
        k (calibrate run-batch! new-fn 25)
        _ (run-batch! (new-fn k))
        us (timed-batches run-batch! new-fn k 7)
        matched (f old-arr (into-array (aget (new-fn 1) 0)))]
    {:us us :matched matched}))

(defn log [& args]
  (apply js/console.log args))

(defn run-bench [^js opts]
  (let [sizes (js->clj (or (.-sizes opts) #js [1 2 3 5 10 20 50 100 1000]))
        only (some-> (.-scenarios opts) (js->clj) (set))
        matching
        (vec (for [n [10 100 1000]
                   data match-data
                   strat strategies]
               (let [r (measure-match strat data n)]
                 (log "match" n (:id data) (:id strat) (.toFixed (:us r) 2))
                 (assoc r :n n :data (:label data) :strategy (:id strat)))))

        dom
        (vec (for [n sizes
                   scenario scenarios
                   :when (or (nil? only) (contains? only (:id scenario)))
                   :when (>= n (:min-n scenario 1))
                   impl impls
                   :when (not (and (:keyed impl) (:no-keyed scenario)))]
               (let [r (measure-cell impl scenario n)]
                 (log "dom" n (:id scenario) (:id impl) (.toFixed (:us r) 2) (:renders r) (:mutations r))
                 (assoc r :n n :scenario (:label scenario) :scenario-id (:id scenario) :impl (:id impl)))))]

    (clj->js {:matching matching :dom dom})))

(defn init []
  (set! js/window -runBench
    (fn [opts]
      (js/Promise.
        (fn [resolve reject]
          ;; let the page settle first
          (js/setTimeout
            (fn []
              (try
                (resolve (run-bench (or opts #js {})))
                (catch :default e
                  (reject e))))
            100))))))
