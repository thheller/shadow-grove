(ns shadow.arborist.collections
  (:require
    [shadow.arborist.protocols :as p]
    [shadow.arborist.common :as common]))

(declare KeyedCollectionInit)

(deftype KeyedItem [key data managed moved?])

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
      (fn [^KeyedItem item]
        (p/dom-insert ^not-native (.-managed item) parent anchor)))
    (.insertBefore parent marker-after anchor))

  (dom-entered! [this]
    (set! dom-entered? true)
    (.forEach items
      (fn [^KeyedItem item]
        (p/dom-entered! ^not-native (.-managed item)))))

  (supports? [this next]
    (instance? KeyedCollectionInit next))

  (dom-sync! [this ^KeyedCollectionInit next]
    (let [^not-native old-coll coll
          ^not-native new-coll (.-coll next)
          dom-parent (.-parentNode marker-after)
          rfn-identical? (identical? render-fn (.-render-fn next))]

      (when-not ^boolean dom-parent
        (throw (ex-info "sync while not in dom?" {})))

      (when-not (and rfn-identical? (identical? old-coll new-coll))

        (set! coll new-coll)
        (set! key-fn (.-key-fn next))
        (set! render-fn (.-render-fn next))

        (let [^function kfn (common/ifn1-wrap key-fn)
              ^function rfn (common/ifn3-wrap render-fn)

              new-len (-count new-coll)

              ;; array of KeyedItem with manage instances
              old-items items
              ;; array of KeyedItem but managed will be set later
              new-items (js/Array. new-len)

              ;; traverse new coll once to build key map and render items
              ^not-native new-keys
              (-persistent!
                (reduce-kv
                  (fn [^not-native keys idx val]
                    (let [key (kfn val)
                          item (KeyedItem. key val nil false)]

                      (aset new-items idx item)
                      (-assoc! keys key item)))
                  (-as-transient {})
                  new-coll))]

          (when (not= (-count new-keys) new-len)
            (throw (ex-info "collection contains duplicated keys" {:coll new-coll :keys new-keys})))

          (let [old-items
                (.filter old-items
                  (fn [^KeyedItem item]
                    (if (contains? new-keys (.-key item))
                      true
                      (do (p/destroy! ^not-native (.-managed item) true)
                          false))))]

            ;; old-items now matches what is in the DOM and only contains items still present in new coll

            ;; this can never be more items than the new coll
            ;; but it might be less in cases where items were removed

            ;; now going backwards over the new collection and apply render results to items
            ;; reverse order because of only being able to insert before anchor

            ;; will create new items while traversing
            ;; will move items when required

            (loop [anchor marker-after
                   idx (dec new-len)
                   old-idx (dec (alength old-items))]

              (when-not (neg? idx)
                (let [^KeyedItem new-item (aget new-items idx)
                      ^KeyedItem old-item (get item-keys (.-key new-item))]

                  (cond
                    ;; item does not exist in old coll, just create and insert
                    (not old-item)
                    (let [rendered (rfn (.-data new-item) idx (.-key new-item))
                          ^not-native managed (p/as-managed rendered env)]

                      (p/dom-insert managed dom-parent anchor)

                      ;; FIXME: call dom-entered! after syncing is done, item might not be in final position yet
                      ;; other stuff may be inserted before it
                      (when dom-entered?
                        (p/dom-entered! managed))

                      (set! new-item -managed managed)

                      (recur (p/dom-first managed) (dec idx) old-idx))

                    ;; item in same position, render update, move only when item was previously moved
                    (identical? old-item (aget old-items old-idx))
                    (let [^not-native managed (.-managed old-item)]
                      ;; if the render-fn and data are identical we can skip over rendering them
                      ;; since they rfn is supposed to be pure.
                      ;; only checking identical? references since = may end up doing too much work
                      ;; that the component/fragment will check again later anyways so we want to avoid
                      ;; duplicating the work. skipping the rendering may save a couple allocation and
                      ;; checks so this is worth doing when it can.

                      ;; render-fn is never identical when using (render-seq coll key-fn (fn [data] ...))
                      ;; but it is when using (render-seq coll key-fn component) or other function refs
                      ;; that don't close over other data

                      (if (and rfn-identical? (identical? (.-data old-item) (.-data new-item)))
                        (do (set! new-item -managed managed)

                            (when ^boolean (.-moved? old-item)
                              ;; don't need to do this I think, never using old-item again
                              ;; (set! old-item -moved? false)
                              (p/dom-insert managed dom-parent anchor))

                            (recur (p/dom-first managed) (dec idx) (dec old-idx)))

                        ;; need to render
                        (let [rendered (rfn (.-data new-item) idx (.-key new-item))]
                          (if (p/supports? managed rendered)
                            ;; update in place if supported
                            (do (p/dom-sync! managed rendered)
                                (set! new-item -managed managed)

                                ;; item was previously moved, move in DOM now
                                (when ^boolean (.-moved? old-item)
                                  ;; don't need to do this I think, never using old-item again
                                  ;; (set! old-item -moved? false)
                                  (p/dom-insert managed dom-parent anchor))

                                (recur (p/dom-first managed) (dec idx) (dec old-idx)))

                            ;; not updatable, swap.
                            ;; unlikely given that key was the same, result should be the same.
                            ;; still possible though
                            (let [^not-native new-managed (p/as-managed rendered env)]
                              (p/dom-insert new-managed dom-parent anchor)
                              (when dom-entered?
                                (p/dom-entered! new-managed))
                              (p/destroy! managed true)
                              (set! new-item -managed new-managed)
                              (recur (p/dom-first new-managed) (dec idx) (dec old-idx))
                              )))))

                    ;; item not in proper position, find it and move it here
                    ;; FIXME: this starts looking at the front of the collection
                    ;; this might be a performance drain when collection is shuffled too much
                    :else
                    (let [seek-idx (.indexOf old-items old-item)
                          ^KeyedItem old-item (aget old-items seek-idx)
                          ^not-native managed (.-managed old-item)

                          ;; current tail item
                          ^KeyedItem item-at-idx (aget old-items old-idx)]

                      (set! item-at-idx -moved? true)
                      (aset old-items seek-idx item-at-idx)
                      (aset old-items old-idx old-item)

                      ;; again, may skip rendering if fully identical
                      ;; still need to move it though
                      (if (and rfn-identical? (identical? (.-data new-item) (.-data old-item)))
                        (do (set! new-item -managed managed)
                            (p/dom-insert managed dom-parent anchor)
                            (recur (p/dom-first managed) (dec idx) (dec old-idx)))

                        ;; can't skip rendering
                        (let [rendered (rfn (.-data new-item) idx (.-key new-item))]
                          (if (p/supports? managed rendered)
                            ;; update in place if supported
                            (do (set! new-item -managed managed)
                                (p/dom-sync! managed rendered)
                                (p/dom-insert managed dom-parent anchor)
                                (recur (p/dom-first managed) (dec idx) (dec old-idx)))

                            ;; not updatable, swap.
                            ;; unlikely given that key was the same, result should be the same.
                            ;; still possible though
                            (let [^not-native new-managed (p/as-managed rendered env)]
                              (set! new-item -managed new-managed)
                              (p/dom-insert new-managed dom-parent anchor)
                              (when dom-entered?
                                (p/dom-entered! new-managed))
                              (p/destroy! managed true)
                              (recur (p/dom-first new-managed) (dec idx) (dec old-idx))
                              ))))))))))

          (set! item-keys new-keys)
          (set! items new-items))))
    :synced)

  (destroy! [this ^boolean dom-remove?]
    (when dom-remove?
      (doto (js/document.createRange)
        (.setStartBefore marker-before)
        (.setEndAfter marker-after)
        (.deleteContents)))

    (.forEach items
      (fn [item]
        (p/destroy! ^not-native (.-managed item) false)))))

(defn construct-keyed-seq [env coll key-fn render-fn]
  (let [len (count coll)
        marker-before (common/dom-marker env "keyed-seq-start")
        marker-after (common/dom-marker env "keyed-seq-end")

        kfn (common/ifn1-wrap key-fn)
        rfn (common/ifn3-wrap render-fn)

        items (js/Array. len)

        ;; {<key> <item>}, same instance as in array
        keys
        (persistent!
          (reduce-kv
            (fn [keys idx val]
              (let [key (kfn val)
                    rendered (rfn val idx key)
                    managed (p/as-managed rendered env)
                    item (KeyedItem. key val managed false)]

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

(deftype KeyedCollectionInit [coll key-fn render-fn]
  p/IConstruct
  (as-managed [this env]
    (construct-keyed-seq env coll key-fn render-fn))

  IEquiv
  (-equiv [this ^KeyedCollectionInit other]
    (and (instance? KeyedCollectionInit other)
         ;; could be a keyword, can't use identical?
         (keyword-identical? key-fn (.-key-fn other))
         ;; FIXME: this makes it never equal if fn is created in :render fn
         (identical? render-fn (.-render-fn other))
         ;; compare coll last since its pointless if the others changed and typically more expensive to compare
         (= coll (.-coll other)))))

(defn keyed-seq [coll key-fn render-fn]
  {:pre [(or (nil? coll) (sequential? coll))
         (ifn? key-fn)
         (ifn? render-fn)]}
  ;; handling nil and empty colls, can skip a lot of work
  (when-not (empty? coll)
    ;; we always need compatible collections, it should already be a vector in most cases
    ;; it must not allow lazy sequences since the sequence may not be used immediately
    ;; some item may suspend and whatever the lazy seq did will happen in totally different phases
    ;; cannot guarantee that some other data it previously may have relied upon is still valid
    (KeyedCollectionInit. (vec coll) key-fn render-fn)))

(declare SimpleCollectionInit)

(deftype SimpleItem [data managed])

(deftype SimpleCollection
  [env
   ^:mutable coll
   ^:mutable ^function render-fn
   ^:mutable ^array items
   marker-before
   marker-after
   ^boolean ^:mutable dom-entered?]

  p/IManaged
  (dom-first [this] marker-before)

  (dom-insert [this parent anchor]
    (.insertBefore parent marker-before anchor)
    (.forEach items
      (fn [^SimpleItem item]
        (p/dom-insert ^not-native (.-managed item) parent anchor)))
    (.insertBefore parent marker-after anchor))

  (dom-entered! [this]
    (set! dom-entered? true)
    (.forEach items
      (fn [^SimpleItem item]
        (p/dom-entered! ^not-native (.-managed item)))))

  (supports? [this next]
    (instance? SimpleCollectionInit next))

  (dom-sync! [this ^SimpleCollectionInit next]
    (let [rfn-identical? (identical? render-fn (.-render-fn next))

          ^not-native old-coll coll
          ^not-native new-coll (.-coll next)]

      ;; only checking identical since we want to avoid deep comparisons for items
      ;; since the rendered results will likely do that again
      (when-not (and rfn-identical? (identical? old-coll new-coll))

        (let [dom-parent (.-parentNode marker-after)

              oc (-count old-coll)
              nc (-count new-coll)

              max-idx (js/Math.min oc nc)]

          (when-not dom-parent
            (throw (ex-info "sync while not in dom?" {})))

          (set! coll new-coll)
          (set! render-fn (.-render-fn next))

          (let [^function rfn (common/ifn2-wrap render-fn)]

            (dotimes [idx max-idx]
              (let [^SimpleItem item (aget items idx)
                    ^not-native managed (.-managed item)
                    new-data (-nth new-coll idx)]

                (when-not (and rfn-identical? (identical? new-data (.-data item)))

                  (let [new-rendered (rfn new-data idx)]
                    (set! item -data new-data)

                    (if (p/supports? managed new-rendered)
                      (p/dom-sync! managed new-rendered)
                      (let [new-managed (common/replace-managed env managed new-rendered)]
                        (when dom-entered?
                          (p/dom-entered! new-managed))

                        (set! item -managed new-managed)))))))

            (cond
              (= oc nc)
              :done

              ;; old had more items, remove tail
              ;; FIXME: might be faster to remove in last one first? less node re-ordering
              (> oc nc)
              (do (dotimes [idx (- oc nc)]
                    (let [idx (+ max-idx idx)
                          ^SimpleItem item (aget items idx)]
                      (p/destroy! ^not-native (.-managed item) true)))
                  (set! (.-length items) max-idx))

              ;; old had fewer items, append at end
              (< oc nc)
              (dotimes [idx (- nc oc)]
                (let [idx (+ max-idx idx)
                      data (-nth new-coll idx)
                      rendered (rfn data idx)
                      managed (p/as-managed rendered env)]
                  (.push items (SimpleItem. data managed))
                  (p/dom-insert managed dom-parent marker-after)
                  (when dom-entered?
                    (p/dom-entered! managed)))))))))

    :synced)

  (destroy! [this ^boolean dom-remove?]
    (when dom-remove?
      (doto (js/document.createRange)
        (.setStartBefore marker-before)
        (.setEndAfter marker-after)
        (.deleteContents)))

    (.forEach items
      (fn [^SimpleItem item]
        (p/destroy! ^not-native (.-managed item) false)))
    ))

(defn construct-simple-seq [env coll render-fn]
  (let [marker-before (common/dom-marker env "simple-seq-start")
        marker-after (common/dom-marker env "simple-seq-end")
        arr (js/Array. (count coll))
        rfn (common/ifn2-wrap render-fn)]

    (reduce-kv
      (fn [_ idx data]
        (aset arr idx (SimpleItem. data (p/as-managed (rfn data idx) env))))
      nil
      coll)

    (SimpleCollection. env coll render-fn arr marker-before marker-after false)))

(deftype SimpleCollectionInit [coll render-fn]
  p/IConstruct
  (as-managed [this env]
    (construct-simple-seq env coll render-fn))

  IEquiv
  (-equiv [this ^SimpleCollectionInit other]
    (and (instance? SimpleCollectionInit other)
         ;; could be a keyword, can't use identical?
         ;; FIXME: this makes it never equal if fn is created in :render fn
         (identical? render-fn (.-render-fn other))
         ;; compare coll last since its pointless if the others changed and typically more expensive to compare
         (= coll (.-coll other)))))

(defn simple-seq [coll render-fn]
  {:pre [(or (nil? coll) (sequential? coll))
         (ifn? render-fn)]}
  ;; skip work if no coll or empty coll
  (when-not (empty? coll)
    ;; forced vec, eliminates lazy seqs and allows skipping checks later
    (SimpleCollectionInit. (vec coll) render-fn)))

;; diff-seq / diff-keyed-seq
;;
;; matching modes, each item has a key
;;   diff-seq           key is the item, keys match when identical?
;;                      (JS Map is SameValueZero, so strings/numbers by value)
;;   diff-seq-by-value  key is the item, keys match when =
;;   diff-keyed-seq     key is (key-fn item), keys match when =
;;
;; sync steps, all on the remaining old/new ranges
;;   1. skip matching heads, then matching tails. covers insert/remove anywhere,
;;      append, prepend, single item update, without any lookup structure
;;   2. one side exhausted -> plain insert or remove
;;   3. old head matches new tail (or vice versa) -> move it, back to 1.
;;      handles swaps and single item moves
;;   4. unkeyed only: heads differ but next pair matches -> render new head over
;;      old head in place, back to 1. same for tails. handles scattered updates
;;   5. otherwise general case: lookup over remaining old (head idx per key,
;;      duplicates chained via Int32Array so [1 1 1] works), match remaining new.
;;      unkeyed only: unmatched new reuse unmatched old in order (simple-seq
;;      style render-over). leftover old destroyed, leftover new created.
;;      if reused items are out of order, keep the longest increasing
;;      subsequence in place, move the rest
;;
;; keyed mode never renders one key over another key's DOM, so per item DOM
;; state stays with its key, same as keyed-seq.
;;
;; matched items skip rendering when render-fn is identical and the data is
;; identical (or = in by-value mode). the idx passed to render-fn may be stale
;; for such items, same as keyed-seq.

(declare DiffCollectionInit)

(deftype DiffItem [^:mutable key ^:mutable data ^:mutable managed])

(defn- diff-lookup-key
  "JS Map key. identity mode uses the key itself. = modes use strings/numbers
   directly and hash everything else, collisions are resolved by checking ="
  [^boolean equiv? k]
  (if (and equiv? (not (string? k)) (not (number? k)))
    (hash k)
    k))

(defn- diff-new-key
  "key of new item n. new-keys is nil when unkeyed (key is the item),
   otherwise a cache of computed (kfn item), filled lazily"
  [^function kfn ^array new-keys ^not-native new-coll n]
  (if (nil? new-keys)
    (-nth new-coll n)
    (let [k (aget new-keys n)]
      (if (undefined? k)
        (let [k (kfn (-nth new-coll n))]
          (aset new-keys n k)
          k)
        k))))

(defn- diff-match? [^boolean equiv? kfn new-keys ^DiffItem item new-coll n]
  (or (identical? (.-data item) (-nth new-coll n))
      (and equiv? (= (.-key item) (diff-new-key kfn new-keys new-coll n)))))

(defn- diff-render [^function rfn ^boolean keyed? data idx key]
  (if keyed?
    (rfn data idx key)
    (rfn data idx)))

(defn- diff-item-sync!
  [env ^DiffItem item key data idx ^function rfn ^boolean keyed? ^boolean skip-render? ^boolean dom-entered?]
  (when-not skip-render?
    (let [^not-native managed (.-managed item)
          rendered (diff-render rfn keyed? data idx key)]
      (if (p/supports? managed rendered)
        (p/dom-sync! managed rendered)
        (let [new-managed (common/replace-managed env managed rendered)]
          (when dom-entered?
            (p/dom-entered! new-managed))
          (set! item -managed new-managed)))))
  (set! item -key key)
  (set! item -data data))

(defn- diff-anchor
  "DOM anchor for inserting at new idx ne. everything after ne is final."
  [^array new-items ne marker-after]
  (let [n (inc ne)]
    (if (< n (alength new-items))
      (p/dom-first ^not-native (.-managed ^DiffItem (aget new-items n)))
      marker-after)))

(defn- diff-lis
  "positions in arr (ascending) forming a longest strictly increasing
   subsequence, ignoring negative entries"
  [^js arr]
  (let [n (alength arr)
        prev (js/Int32Array. n)
        tails (js/Int32Array. n)]
    (loop [i 0 len 0]
      (if (< i n)
        (let [v (aget arr i)]
          (cond
            (neg? v)
            (recur (inc i) len)

            (or (zero? len) (> v (aget arr (aget tails (dec len)))))
            (do (aset prev i (if (zero? len) -1 (aget tails (dec len))))
                (aset tails len i)
                (recur (inc i) (inc len)))

            :else
            (let [x (loop [lo 0 hi (dec len)]
                      (if (< lo hi)
                        (let [mid (unsigned-bit-shift-right (+ lo hi) 1)]
                          (if (< (aget arr (aget tails mid)) v)
                            (recur (inc mid) hi)
                            (recur lo mid)))
                        lo))]
              (aset prev i (if (pos? x) (aget tails (dec x)) -1))
              (aset tails x i)
              (recur (inc i) len))))

        (let [out (js/Int32Array. len)]
          (loop [k (dec len)
                 j (if (pos? len) (aget tails (dec len)) -1)]
            (when (>= k 0)
              (aset out k j)
              (recur (dec k) (aget prev j))))
          out)))))

(defn- diff-sync-range!
  "general case for old-items[os..oe] -> new-coll[ns..ne], inclusive, both non-empty.
   in the DOM the remaining old items sit between the final head and the final tail."
  [env dom-parent marker-after ^array old-items ^not-native new-coll ^array new-items
   os oe ns ne ^function rfn ^function kfn new-keys
   ^boolean equiv? ^boolean keyed? ^boolean skip-equiv? ^boolean rfn-identical? ^boolean dom-entered?]
  (let [om (inc (- oe os))
        nm (inc (- ne ns))
        anchor-after (diff-anchor new-items ne marker-after)

        ;; lookup key -> relative old idx of first unclaimed item with that key
        lookup (js/Map.)
        ;; relative old idx -> next relative old idx with same lookup key, or -1
        next-same (js/Int32Array. om)
        ;; relative new idx -> relative old idx, or -1 for create
        sources (.fill (js/Int32Array. nm) -1)
        ;; 1 if new item matched an old item by key (render may be skipped)
        matched (js/Uint8Array. nm)
        ;; 1 if old item is reused
        used (js/Uint8Array. om)]

    ;; build lookup backwards so chains are in ascending order
    (loop [k (dec om)]
      (when (>= k 0)
        (let [lkey (diff-lookup-key equiv? (.-key ^DiffItem (aget old-items (+ os k))))
              head (.get lookup lkey)]
          (aset next-same k (if (undefined? head) -1 head))
          (.set lookup lkey k)
          (recur (dec k)))))

    ;; match new against old
    (dotimes [j nm]
      (let [key (diff-new-key kfn new-keys new-coll (+ ns j))
            lkey (diff-lookup-key equiv? key)
            head (.get lookup lkey)]
        (when-not (undefined? head)
          (let [k (if-not equiv?
                    ;; identity: head always matches, unlink it
                    (let [nxt (aget next-same head)]
                      (if (neg? nxt)
                        (.delete lookup lkey)
                        (.set lookup lkey nxt))
                      head)
                    ;; =: hash collision possible, walk chain checking =
                    (loop [prev -1 k head]
                      (cond
                        (neg? k)
                        -1

                        (= key (.-key ^DiffItem (aget old-items (+ os k))))
                        (let [nxt (aget next-same k)]
                          (cond
                            (not (neg? prev))
                            (aset next-same prev nxt)
                            (neg? nxt)
                            (.delete lookup lkey)
                            :else
                            (.set lookup lkey nxt))
                          k)

                        :else
                        (recur k (aget next-same k)))))]
            (when-not (neg? k)
              (aset sources j k)
              (aset matched j 1)
              (aset used k 1))))))

    ;; unkeyed only: pair unmatched new with unmatched old, in order
    (when-not keyed?
      (loop [j 0 k 0]
        (when (and (< j nm) (< k om))
          (cond
            (not (neg? (aget sources j)))
            (recur (inc j) k)

            (== 1 (aget used k))
            (recur j (inc k))

            :else
            (do (aset sources j k)
                (aset used k 1)
                (recur (inc j) (inc k)))))))

    ;; destroy old items nobody wants, before moving anything
    (dotimes [k om]
      (when (zero? (aget used k))
        (p/destroy! ^not-native (.-managed ^DiffItem (aget old-items (+ os k))) true)))

    ;; only need moves if reused items are out of order
    (let [moved?
          (loop [j 0 max-k -1]
            (if (< j nm)
              (let [k (aget sources j)]
                (cond
                  (neg? k) (recur (inc j) max-k)
                  (< k max-k) true
                  :else (recur (inc j) k)))
              false))

          stay (if moved? (diff-lis sources) (js/Int32Array. 0))]

      ;; walk backwards, insert before anchor
      (loop [j (dec nm)
             anchor anchor-after
             stay-pos (dec (alength stay))]
        (when (>= j 0)
          (let [idx (+ ns j)
                data (-nth new-coll idx)
                key (diff-new-key kfn new-keys new-coll idx)
                k (aget sources j)]
            (if (neg? k)
              (let [^not-native managed (p/as-managed (diff-render rfn keyed? data idx key) env)]
                (p/dom-insert managed dom-parent anchor)
                (when dom-entered?
                  (p/dom-entered! managed))
                (aset new-items idx (DiffItem. key data managed))
                (recur (dec j) (p/dom-first managed) stay-pos))

              (let [^DiffItem item (aget old-items (+ os k))
                    stays? (and (>= stay-pos 0) (== j (aget stay stay-pos)))]
                (diff-item-sync! env item key data idx rfn keyed?
                  (and rfn-identical?
                       (== 1 (aget matched j))
                       (or skip-equiv? (identical? data (.-data item))))
                  dom-entered?)
                (let [^not-native managed (.-managed item)]
                  (when (and moved? (not stays?))
                    (p/dom-insert managed dom-parent anchor))
                  (aset new-items idx item)
                  (recur (dec j)
                    (p/dom-first managed)
                    (if stays? (dec stay-pos) stay-pos)))))))))))

(deftype DiffCollection
  [env
   ^:mutable coll
   ^:mutable ^function render-fn
   ^:mutable key-fn
   ^:mutable ^array items
   marker-before
   marker-after
   ^boolean ^:mutable dom-entered?]

  p/IManaged
  (dom-first [this] marker-before)

  (dom-insert [this parent anchor]
    (.insertBefore parent marker-before anchor)
    (.forEach items
      (fn [^DiffItem item]
        (p/dom-insert ^not-native (.-managed item) parent anchor)))
    (.insertBefore parent marker-after anchor))

  (dom-entered! [this]
    (set! dom-entered? true)
    (.forEach items
      (fn [^DiffItem item]
        (p/dom-entered! ^not-native (.-managed item)))))

  (supports? [this next]
    (and (instance? DiffCollectionInit next)
         ;; stored keys mean something else when switching between keyed and unkeyed
         (identical? (nil? key-fn) (nil? (.-key-fn ^DiffCollectionInit next)))))

  (dom-sync! [this ^DiffCollectionInit next]
    (let [rfn-identical? (identical? render-fn (.-render-fn next))
          ^not-native old-coll coll
          ^not-native new-coll (.-coll next)]

      (when-not (and rfn-identical? (identical? old-coll new-coll))
        (let [dom-parent (.-parentNode marker-after)]
          (when-not dom-parent
            (throw (ex-info "sync while not in dom?" {})))

          (set! coll new-coll)
          (set! render-fn (.-render-fn next))
          (set! key-fn (.-key-fn next))

          (let [keyed? (some? key-fn)
                by-value? (.-by-value? next)
                ;; keys compared by = instead of identical?
                equiv? (or keyed? by-value?)
                ;; key match implies data match, so render can be skipped
                skip-equiv? (and by-value? (not keyed?))

                ^function rfn (if keyed? (common/ifn3-wrap render-fn) (common/ifn2-wrap render-fn))
                ^function kfn (when keyed? (common/ifn1-wrap key-fn))
                old-items items
                nc (-count new-coll)
                new-items (js/Array. nc)
                new-keys (when keyed? (js/Array. nc))]

            (loop [os 0
                   oe (dec (alength old-items))
                   ns 0
                   ne (dec nc)]

              ;; 1. matching heads
              (let [h (loop [h 0]
                        (let [o (+ os h)
                              n (+ ns h)]
                          (if (and (<= o oe) (<= n ne))
                            (let [^DiffItem item (aget old-items o)
                                  data (-nth new-coll n)]
                              (cond
                                ;; hot path, no key work needed
                                (identical? (.-data item) data)
                                (do (when-not rfn-identical?
                                      (diff-item-sync! env item (.-key item) data n rfn keyed? false dom-entered?))
                                    (aset new-items n item)
                                    (recur (inc h)))

                                (and equiv? (= (.-key item) (if keyed? (diff-new-key kfn new-keys new-coll n) data)))
                                (do (diff-item-sync! env item (if keyed? (diff-new-key kfn new-keys new-coll n) data) data n rfn keyed?
                                      (and rfn-identical? skip-equiv?) dom-entered?)
                                    (aset new-items n item)
                                    (recur (inc h)))

                                :else
                                h))
                            h)))
                    os (+ os h)
                    ns (+ ns h)

                    ;; 1. matching tails
                    t (loop [t 0]
                        (let [o (- oe t)
                              n (- ne t)]
                          (if (and (>= o os) (>= n ns))
                            (let [^DiffItem item (aget old-items o)
                                  data (-nth new-coll n)]
                              (cond
                                ;; hot path, no key work needed
                                (identical? (.-data item) data)
                                (do (when-not rfn-identical?
                                      (diff-item-sync! env item (.-key item) data n rfn keyed? false dom-entered?))
                                    (aset new-items n item)
                                    (recur (inc t)))

                                (and equiv? (= (.-key item) (if keyed? (diff-new-key kfn new-keys new-coll n) data)))
                                (do (diff-item-sync! env item (if keyed? (diff-new-key kfn new-keys new-coll n) data) data n rfn keyed?
                                      (and rfn-identical? skip-equiv?) dom-entered?)
                                    (aset new-items n item)
                                    (recur (inc t)))

                                :else
                                t))
                            t)))
                    oe (- oe t)
                    ne (- ne t)]

                (cond
                  ;; 2. old exhausted, insert remaining new
                  (> os oe)
                  (let [anchor (diff-anchor new-items ne marker-after)]
                    (loop [idx ns]
                      (when (<= idx ne)
                        (let [data (-nth new-coll idx)
                              key (diff-new-key kfn new-keys new-coll idx)
                              ^not-native managed (p/as-managed (diff-render rfn keyed? data idx key) env)]
                          (p/dom-insert managed dom-parent anchor)
                          (when dom-entered?
                            (p/dom-entered! managed))
                          (aset new-items idx (DiffItem. key data managed))
                          (recur (inc idx))))))

                  ;; 2. new exhausted, remove remaining old
                  (> ns ne)
                  (loop [idx os]
                    (when (<= idx oe)
                      (p/destroy! ^not-native (.-managed ^DiffItem (aget old-items idx)) true)
                      (recur (inc idx))))

                  :else
                  (let [^DiffItem os-item (aget old-items os)
                        ^DiffItem oe-item (aget old-items oe)]
                    (cond
                      ;; 3. old head moved to tail
                      (diff-match? equiv? kfn new-keys os-item new-coll ne)
                      (let [data (-nth new-coll ne)]
                        (diff-item-sync! env os-item (diff-new-key kfn new-keys new-coll ne) data ne rfn keyed?
                          (and rfn-identical? (or skip-equiv? (identical? data (.-data os-item))))
                          dom-entered?)
                        (p/dom-insert ^not-native (.-managed os-item) dom-parent
                          (diff-anchor new-items ne marker-after))
                        (aset new-items ne os-item)
                        (recur (inc os) oe ns (dec ne)))

                      ;; 3. old tail moved to head, goes before first remaining old
                      (diff-match? equiv? kfn new-keys oe-item new-coll ns)
                      (let [data (-nth new-coll ns)]
                        (diff-item-sync! env oe-item (diff-new-key kfn new-keys new-coll ns) data ns rfn keyed?
                          (and rfn-identical? (or skip-equiv? (identical? data (.-data oe-item))))
                          dom-entered?)
                        (p/dom-insert ^not-native (.-managed oe-item) dom-parent
                          (p/dom-first ^not-native (.-managed os-item)))
                        (aset new-items ns oe-item)
                        (recur os (dec oe) (inc ns) ne))

                      ;; 4. unkeyed: render new head over old head, if next pair matches or it's the last pair
                      (and (not keyed?)
                           (or (and (== os oe) (== ns ne))
                               (and (< os oe) (< ns ne)
                                    (diff-match? equiv? kfn new-keys (aget old-items (inc os)) new-coll (inc ns)))))
                      (let [data (-nth new-coll ns)]
                        (diff-item-sync! env os-item data data ns rfn false false dom-entered?)
                        (aset new-items ns os-item)
                        (recur (inc os) oe (inc ns) ne))

                      ;; 4. unkeyed: same for tails
                      (and (not keyed?)
                           (< os oe) (< ns ne)
                           (diff-match? equiv? kfn new-keys (aget old-items (dec oe)) new-coll (dec ne)))
                      (let [data (-nth new-coll ne)]
                        (diff-item-sync! env oe-item data data ne rfn false false dom-entered?)
                        (aset new-items ne oe-item)
                        (recur os (dec oe) ns (dec ne)))

                      ;; 5.
                      :else
                      (diff-sync-range! env dom-parent marker-after old-items new-coll new-items
                        os oe ns ne rfn kfn new-keys
                        equiv? keyed? skip-equiv? rfn-identical? dom-entered?))))))

            (set! items new-items)))))

    :synced)

  (destroy! [this ^boolean dom-remove?]
    (when dom-remove?
      (doto (js/document.createRange)
        (.setStartBefore marker-before)
        (.setEndAfter marker-after)
        (.deleteContents)))

    (.forEach items
      (fn [^DiffItem item]
        (p/destroy! ^not-native (.-managed item) false)))))

(defn construct-diff-seq [env coll render-fn key-fn]
  (let [marker-before (common/dom-marker env "diff-seq-start")
        marker-after (common/dom-marker env "diff-seq-end")
        arr (js/Array. (count coll))
        keyed? (some? key-fn)
        rfn (if keyed? (common/ifn3-wrap render-fn) (common/ifn2-wrap render-fn))
        kfn (when keyed? (common/ifn1-wrap key-fn))]

    (reduce-kv
      (fn [_ idx data]
        (let [key (if keyed? (kfn data) data)]
          (aset arr idx (DiffItem. key data (p/as-managed (diff-render rfn keyed? data idx key) env)))))
      nil
      coll)

    (DiffCollection. env coll render-fn key-fn arr marker-before marker-after false)))

(deftype DiffCollectionInit [coll render-fn ^boolean by-value? key-fn]
  p/IConstruct
  (as-managed [this env]
    (construct-diff-seq env coll render-fn key-fn))

  IEquiv
  (-equiv [this ^DiffCollectionInit other]
    (and (instance? DiffCollectionInit other)
         (identical? render-fn (.-render-fn other))
         (identical? by-value? (.-by-value? other))
         ;; could be a keyword, can't use identical?
         (keyword-identical? key-fn (.-key-fn other))
         (= coll (.-coll other)))))

(defn diff-seq
  "like simple-seq, but tracks items by identical? so inserts, removes and
   reorders move existing DOM instead of rendering over it"
  [coll render-fn]
  {:pre [(or (nil? coll) (sequential? coll))
         (ifn? render-fn)]}
  (when-not (empty? coll)
    (DiffCollectionInit. (vec coll) render-fn false nil)))

(defn diff-seq-by-value
  "like diff-seq, but items match by ="
  [coll render-fn]
  {:pre [(or (nil? coll) (sequential? coll))
         (ifn? render-fn)]}
  (when-not (empty? coll)
    (DiffCollectionInit. (vec coll) render-fn true nil)))

(defn diff-keyed-seq
  "same contract as keyed-seq, using the diff-seq algorithm.
   duplicate keys only throw in dev builds."
  [coll key-fn render-fn]
  {:pre [(or (nil? coll) (sequential? coll))
         (ifn? key-fn)
         (ifn? render-fn)]}
  (when-not (empty? coll)
    (let [coll (vec coll)]
      (when ^boolean js/goog.DEBUG
        (when (not= (count coll) (count (into #{} (map key-fn) coll)))
          (throw (ex-info "collection contains duplicated keys" {:coll coll}))))
      (DiffCollectionInit. coll render-fn false key-fn))))
