(ns shadow.experiments.arborist.collections
  (:require
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.arborist.fragments :as frag]
    [shadow.experiments.arborist.common :as common]))

(declare KeyedCollectionInit)

(defn index-map ^not-native [key-vec]
  (persistent! (reduce-kv #(assoc! %1 %3 %2) (transient {}) key-vec)))

(deftype KeyedItem [key value moved?])

(deftype KeyedCollection
  [env
   ^:mutable coll
   ^:mutable ^function key-fn
   ^:mutable ^function render-fn
   ^:mutable items ;; array of KeyedItem instances
   ^:mutable item-keys ;; map of key -> items index
   marker-before
   marker-after
   ^boolean ^:mutable dom-entered?]

  p/IManaged
  (dom-first [this] marker-before)

  (dom-insert [this parent anchor]
    (.insertBefore parent marker-before anchor)
    (.forEach items
      (fn [item]
        (p/dom-insert ^not-native (.-value item) parent anchor)))
    (.insertBefore parent marker-after anchor))

  (dom-entered! [this]
    (set! dom-entered? true)
    (.forEach items
      (fn [item]
        (p/dom-entered! ^not-native (.-value item)))))

  (supports? [this next]
    (instance? KeyedCollectionInit next))

  (dom-sync! [this ^KeyedCollectionInit next]
    (let [^not-native old-coll coll
          ^not-native new-coll (.-coll next)
          dom-parent (.-parentNode marker-after)]

      (when-not dom-parent
        (throw (ex-info "sync while not in dom?" {})))

      (set! coll new-coll)
      (set! key-fn (.-key-fn next))
      (set! render-fn (.-render-fn next))

      (let [old-len (-count old-coll)
            new-len (-count new-coll)

            ;; array of KeyedItem but value is just the render result for now
            new-items (js/Array. new-len)

            ;; traverse new coll once to build key map and render items
            ^not-native new-keys
            (-persistent!
              (reduce-kv
                (fn [^not-native keys idx val]
                  (let [key (key-fn val)
                        rendered (render-fn val idx key)
                        item (KeyedItem. key rendered false)]

                    (aset new-items idx item)
                    (-assoc! keys key item)))
                (-as-transient {})
                coll))]

        (when (not= (-count new-keys) new-len)
          (throw (ex-info "collection contains duplicated keys" {})))

        ;; now remove item when key no longer exists
        (loop [idx (dec old-len)]
          (when-not (neg? idx)
            (let [^KeyedItem item (aget items idx)]
              (if (contains? new-keys (.-key item))
                (recur (dec idx))
                (do (p/destroy! (.-value item))
                    ;; FIXME: what is most costly?
                    ;; splicing the existing array
                    ;; or pushing stuff into a new array (without the new items)
                    ;; probably dependent on size and how many we remove?
                    ;; likely won't matter much but might be worth testing, code is more or less the same
                    (.splice items idx 1)
                    (recur (dec idx)))))))

        ;; items array now contains all the old items without the deleted ones
        ;; if this contains less items than new-coll then something new was added as well
        ;; if the length is the same then only one or more items were removed

        ;; don't really need this anywhere though?
        ;; new-items?
        ;; (not= new-len (alength items))

        ;; now go backwards over the new collection and apply render results to items
        ;; reverse order because of only being able to insert before anchor

        ;; will create new items while traversing
        ;; will move items when required

        (loop [anchor marker-after
               idx (dec new-len)]

          (when-not (neg? idx)
            (let [new-item (aget new-items idx)
                  old-item (get item-keys (.-key new-item))]

              (cond
                ;; item does not exist in old coll, just create and insert
                (not old-item)
                (let [managed (p/as-managed (.-value new-item) env)]
                  (p/dom-insert managed dom-parent anchor)
                  (when dom-entered?
                    (p/dom-entered! managed))

                  (set! new-item -value managed)

                  ;; FIXME: can there be a case where there is an actual item in items at this idx?

                  (recur (p/dom-first managed) (dec idx)))

                ;; item in same position, render update, move only when item was previously moved
                (identical? old-item (aget items idx))
                (let [^not-native managed (.-value old-item)
                      rendered (.-value new-item)]
                  (if (p/supports? managed rendered)
                    ;; update in place if supported
                    (do (p/dom-sync! managed rendered)
                        (set! new-item -value managed)

                        ;; item was previously moved, move in DOM now
                        (when (.-moved? old-item)
                          ;; don't need to do this I think, never using old-item again
                          ;; (set! old-item -moved? false)
                          (p/dom-insert managed dom-parent anchor))

                        (let [next-anchor (p/dom-first managed)]
                          (recur next-anchor (dec idx))))

                    ;; not updatable, swap.
                    ;; unlikely given that key was the same, result should be the same.
                    ;; still possible though
                    (let [new-managed (p/as-managed rendered env)]
                      (p/dom-insert new-managed dom-parent anchor)
                      (when dom-entered?
                        (p/dom-entered! new-managed))
                      (p/destroy! managed)
                      (set! new-item -value new-managed)
                      (recur (p/dom-first new-managed) (dec idx))
                      )))

                ;; item not in proper position, find it and move it here
                ;; FIXME: this starts looking at the front of the collection
                ;; this might be a performance drain when collection is shuffled too much
                :else
                (let [old-idx (.indexOf items old-item)
                      old-item (aget items old-idx)
                      ^not-native managed (.-value old-item)
                      rendered (.-value new-item)]

                  (set! new-item -value managed)

                  ;; just swap positions for now
                  (let [^KeyedItem item-at-idx (aget items idx)]
                    (set! item-at-idx -moved? true)
                    (aset items old-idx item-at-idx)
                    (aset items idx old-item))

                  (if (p/supports? managed rendered)
                    ;; update in place if supported
                    (do (p/dom-sync! managed rendered)
                        (p/dom-insert managed dom-parent anchor)
                        (recur (p/dom-first managed) (dec idx)))

                    ;; not updatable, swap.
                    ;; unlikely given that key was the same, result should be the same.
                    ;; still possible though
                    (let [new-managed (p/as-managed rendered env)]
                      (p/dom-insert new-managed dom-parent anchor)
                      (when dom-entered?
                        (p/dom-entered! new-managed))
                      (p/destroy! managed)
                      (recur (p/dom-first new-managed) (dec idx))
                      )))))))

        (set! item-keys new-keys)
        (set! items new-items)))
    :synced)

  (destroy! [this]
    (.remove marker-before)
    (.forEach items
      (fn [item]
        (p/destroy! ^not-native (.-value item))))
    (.remove marker-after)))

(deftype KeyedCollectionInit [coll key-fn render-fn]
  p/IConstruct
  (as-managed [this env]
    (let [len (count coll)
          marker-before (js/document.createComment "coll-start")
          marker-after (js/document.createComment "coll-end")

          items (js/Array. len)

          ;; {<key> <item>}, same instance as in array
          keys
          (persistent!
            (reduce-kv
              (fn [keys idx val]
                (let [key (key-fn val)
                      rendered (render-fn val idx key)
                      managed (p/as-managed rendered env)
                      item (KeyedItem. key managed false)]

                  (aset items idx item)
                  (assoc! keys key item)))
              (transient {})
              coll))]

      (when (not= (count keys) len)
        (throw (ex-info "collection contains duplicated keys" {})))

      (KeyedCollection.
        env
        coll
        key-fn
        render-fn
        items
        keys
        marker-before
        marker-after
        false)))

  IEquiv
  (-equiv [this ^KeyedCollectionInit other]
    (and (instance? KeyedCollectionInit other)
         ;; could be a keyword, can't use identical?
         (keyword-identical? key-fn (.-key-fn other))
         ;; FIXME: this makes it never equal if fn is created in :render fn
         (identical? render-fn (.-render-fn other))
         ;; compare coll last since its pointless if the others changed and typically more expensive to compare
         (= coll (.-coll other)))))

(declare SimpleCollectionInit)

;; FIXME: verify this is actually faster in a meaningful way to have 2 separate impls
;; seems like it should be faster but might not be
(deftype SimpleCollection
  [env
   ^:mutable coll
   ^:mutable ^function render-fn
   ^:mutable ^array items
   marker-before
   marker-after
   ^boolean ^:mutable dom-entered?
   ]

  p/IManaged
  (dom-first [this] marker-before)

  (dom-insert [this parent anchor]
    (.insertBefore parent marker-before anchor)
    (run! #(p/dom-insert % parent anchor) items)
    (.insertBefore parent marker-after anchor))

  (dom-entered! [this]
    (set! dom-entered? true)
    (run! #(p/dom-entered! ^not-native %) items))

  (supports? [this next]
    (instance? SimpleCollectionInit next))

  (dom-sync! [this ^SimpleCollectionInit next]
    (let [old-coll coll
          new-coll (.-coll next)
          dom-parent (.-parentNode marker-after)

          oc (count old-coll)
          nc (count new-coll)

          max-idx (js/Math.min oc nc)]

      (when-not dom-parent
        (throw (ex-info "sync while not in dom?" {})))

      (set! coll new-coll)
      (set! render-fn (.-render-fn next))

      (dotimes [idx max-idx]
        (let [item (aget items idx)
              new-val (nth new-coll idx)
              new-rendered (render-fn new-val idx)]

          (if (p/supports? item new-rendered)
            (p/dom-sync! item new-rendered)
            (let [new-managed (common/replace-managed env item new-rendered)]
              (when dom-entered?
                (p/dom-entered! new-managed))
              (aset items idx new-managed)))))

      (cond
        (= oc nc)
        :done

        ;; old had more items, remove tail
        ;; FIXME: might be faster to remove in last one first? less node re-ordering
        (> oc nc)
        (do (dotimes [idx (- oc nc)]
              (let [idx (+ max-idx idx)
                    item (aget items idx)]
                (p/destroy! item)))
            (set! (.-length items) max-idx))

        ;; old had fewer items, append at end
        (< oc nc)
        (dotimes [idx (- nc oc)]
          (let [idx (+ max-idx idx)
                val (nth new-coll idx)
                rendered (render-fn val idx)
                managed (p/as-managed rendered env)]
            (.push items managed)
            (p/dom-insert managed dom-parent marker-after)
            (when dom-entered?
              (p/dom-entered! managed))))))

    :synced)

  (destroy! [this]
    (.remove marker-before)
    (run! #(p/destroy! ^not-native %) items)
    (.remove marker-after)))

(deftype SimpleCollectionInit [coll render-fn]
  p/IConstruct
  (as-managed [this env]
    (let [marker-before (js/document.createComment "coll-start")
          marker-after (js/document.createComment "coll-end")
          arr (js/Array. (count coll))]

      (reduce-kv
        (fn [_ idx val]
          (aset arr idx (p/as-managed (render-fn val idx) env)))
        nil
        coll)

      (SimpleCollection. env coll render-fn arr marker-before marker-after false)))

  IEquiv
  (-equiv [this ^SimpleCollectionInit other]
    (and (instance? SimpleCollectionInit other)
         ;; could be a keyword, can't use identical?
         ;; FIXME: this makes it never equal if fn is created in :render fn
         (identical? render-fn (.-render-fn other))
         ;; compare coll last since its pointless if the others changed and typically more expensive to compare
         (= coll (.-coll other)))))

(defn node [coll key-fn render-fn]
  {:pre [(sequential? coll)
         (ifn? render-fn)]}
  ;; we always need compatible collections
  ;; but code looks inconvenient it it doesn't take lazy seqs
  (let [coll (vec coll)]
    (cond
      (zero? (count coll))
      nil ;; can skip much unneeded work for empty colls

      ;; FIXME: should likely use simple path for really small colls
      ;; or maybe some other metrics we can infer here?
      (some? key-fn)
      (KeyedCollectionInit. coll (common/ifn1-wrap key-fn) (common/ifn3-wrap render-fn))

      :else
      (SimpleCollectionInit. coll (common/ifn2-wrap render-fn)))))