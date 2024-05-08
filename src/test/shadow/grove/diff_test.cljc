(ns shadow.grove.diff-test
  (:require
    [clojure.test :refer (deftest is)]
    [clojure.pprint :refer (pprint)]))

(def NOT-FOUND (Object.))

(declare diff-object)

(defn diff-map [left right]
  (let [all-keys
        (-> #{}
            (into (keys left))
            (into (keys right)))]

    (reduce
      (fn [m k]
        (let [lv (get left k NOT-FOUND)
              rv (get right k NOT-FOUND)]

          (cond
            (identical? lv NOT-FOUND)
            (assoc-in m [:added k] rv)

            (identical? rv NOT-FOUND)
            (assoc-in m [:removed k] lv)

            (= lv rv)
            (assoc-in m [:equal k] rv)

            :else
            (assoc-in m [:updated k] (diff-object lv rv)))))

      {:op :map
       :added {}
       :removed {}
       :equal {}
       :updated {}}

      all-keys)))

(defn diff-set [left right]
  (let [all
        (-> #{}
            (into left)
            (into right))]

    (reduce
      (fn [m entry]
        (let [l? (contains? left entry)
              r? (contains? right entry)]

          (cond
            (and l? r?)
            (update m :equal conj entry)

            l?
            (update m :removed conj entry)

            r?
            (update m :added conj entry)

            :else
            m)))

      {:op :set
       :added []
       :removed []
       :equal []}

      all)))

(defn diff-vec [left right]
  (let [entries
        (reduce-kv
          (fn [m idx rv]
            (let [lv (get left idx NOT-FOUND)]
              (cond
                (identical? lv NOT-FOUND)
                (conj m {:idx idx :op :add :val rv})

                (= lv rv)
                (conj m {:idx idx :op :eq :val rv})

                :else
                (conj m {:idx idx :op :update :diff (diff-object lv rv)})
                )))
          []
          right)]

    {:op :vec
     :entries entries
     :removed
     ;; above uses right as reference, need to account for left having had more entries
     (loop [removed []
            size-diff (- (count left) (count right))]
       (if-not (pos? size-diff)
         removed
         (let [idx (- (count left) 1 size-diff)]
           (recur
             (conj removed {:idx idx :val (nth left idx)})
             (dec size-diff)))))}))

(defn diff-val [left right]
  {:op :val
   :left left
   :right right})

(defn diff-object [left right]
  (cond
    (= left right)
    {:op :eq
     :val right}

    (nil? left)
    {:op :add
     :val right}

    (nil? right)
    {:op :remove
     :val left}

    (and (map? left) (map? right))
    (diff-map left right)

    (and (vector? left) (vector? right))
    (diff-vec left right)

    (and (set? left) (set? right))
    (diff-set left right)

    (and (string? left) (string? right))
    (diff-val left right)

    (and (number? left) (number? right))
    (diff-val left right)

    (and (boolean? left) (boolean? right))
    (diff-val left right)

    (and (symbol? left) (symbol? right))
    (diff-val left right)

    (and (keyword? left) (keyword? right))
    (diff-val left right)

    :else
    {:op :unknown
     :left left
     :right right}))

(deftest test-diff-summary

  (pprint
    (diff-object
      {:x 1 :y [1 2] :z #{1 2 3} :foo "foo"}
      {:x 1 :y [1 2 3 4] :z #{2 4} :foo "bar"})))
