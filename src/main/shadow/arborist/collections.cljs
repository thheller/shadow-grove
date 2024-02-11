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

(deftype KeyedCollectionInit [coll key-fn render-fn]
  p/IConstruct
  (as-managed [this env]
    (let [len (count coll)
          marker-before (common/dom-marker env "coll-start")
          marker-after (common/dom-marker env "coll-end")

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
  (when (empty? coll)
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

(deftype SimpleCollectionInit [coll render-fn]
  p/IConstruct
  (as-managed [this env]
    (let [marker-before (common/dom-marker env "coll-start")
          marker-after (common/dom-marker env "coll-end")
          arr (js/Array. (count coll))
          rfn (common/ifn2-wrap render-fn)]

      (reduce-kv
        (fn [_ idx data]
          (aset arr idx (SimpleItem. data (p/as-managed (rfn data idx) env))))
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

(defn simple-seq [coll render-fn]
  {:pre [(or (nil? coll) (sequential? coll))
         (ifn? render-fn)]}
  ;; skip work if no coll or empty coll
  (when (empty? coll)
    ;; forced vec, eliminates lazy seqs and allows skipping checks later
    (SimpleCollectionInit. (vec coll) render-fn)))
