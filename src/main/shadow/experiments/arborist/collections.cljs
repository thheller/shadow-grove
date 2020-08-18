(ns shadow.experiments.arborist.collections
  (:require
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.arborist.fragments :as frag]
    [shadow.experiments.arborist.common :as common]))

(declare KeyedCollectionInit)

(defn index-map ^not-native [key-vec]
  (persistent! (reduce-kv #(assoc! %1 %3 %2) (transient {}) key-vec)))

(deftype KeyedCollection
  [env
   ^:mutable coll
   ^:mutable key-fn
   ^:mutable render-fn
   ^:mutable ^not-native items ;; map of {key managed}
   ^:mutable item-keys ;; vector of (key-fn item)
   ^:mutable item-vals ;; map of {key rendered}
   marker-before
   marker-after
   ^boolean ^:mutable dom-entered?
   ]

  p/IManaged
  (dom-first [this] marker-before)

  (dom-insert [this parent anchor]
    (.insertBefore parent marker-before anchor)
    (run! #(p/dom-insert (get items %) parent anchor) item-keys)
    (.insertBefore parent marker-after anchor))

  (dom-entered! [this]
    (set! dom-entered? true)
    (reduce
      (fn [_ key]
        (let [val (get items key)]
          (p/dom-entered! val)))
      nil
      item-keys))

  (supports? [this next]
    (instance? KeyedCollectionInit next))

  (dom-sync! [this ^KeyedCollectionInit next]
    (let [old-coll coll
          new-coll (vec (.-coll next)) ;; FIXME: could use into-array
          dom-parent (.-parentNode marker-after)]

      (when-not dom-parent
        (throw (ex-info "sync while not in dom?" {})))

      (set! coll new-coll)
      (set! key-fn (.-key-fn next))
      (set! render-fn (.-render-fn next))

      ;; FIXME: figure out how to pass args to render-fn properly, what to do about idx/key
      ;; maybe just allow one extra arg?

      ;; FIXME: this should probably be use separate phases
      ;; one that finds all the nodes that need to be removed (but doesn't yet)
      ;; one that finds all new nodes (and constructs them)
      ;; and then in a final pass touches the actual dom and realizes the changes
      ;; would make it easier to synchronize transition changes later on

      (let [old-keys item-keys
            old-indexes (index-map old-keys)
            new-keys (into [] (map key-fn) coll)

            updated
            (loop [anchor marker-after
                   idx (-> new-keys count dec)
                   updated (transient #{})]
              (if (neg? idx)
                (persistent! updated)
                (let [key (nth new-keys idx)
                      ^not-native item (get items key)
                      data (nth new-coll idx)
                      updated (conj! updated key)
                      rendered (render-fn data idx key)]

                  (if-not item
                    ;; new item added to list, nothing to compare to just insert
                    (let [item (p/as-managed rendered env)]
                      (p/dom-insert item (.-parentNode anchor) anchor)
                      (when dom-entered?
                        (p/dom-entered! item))
                      (set! item-vals (assoc item-vals key rendered))
                      (set! items (assoc items key item))
                      (recur (p/dom-first item) (dec idx) updated))

                    ;; item did exist
                    ;; skip dom-sync if render result is equal
                    (if (= rendered (get item-vals key))
                      (let [next-anchor (p/dom-first item)]

                        ;; still may need to move though
                        (when (not= idx (get old-indexes key))
                          (p/dom-insert item dom-parent anchor))

                        (recur next-anchor (dec idx) updated))

                      (do (set! item-vals (assoc item-vals key rendered))
                          (if (p/supports? item rendered)
                            ;; update in place if supported
                            (do (p/dom-sync! item rendered)
                                (let [next-anchor (p/dom-first item)]

                                  ;; FIXME: this is probably not ideal
                                  (when (not= idx (get old-indexes key))
                                    (p/dom-insert item dom-parent anchor))

                                  (recur next-anchor (dec idx) updated)))

                            ;; not updateable, swap
                            (let [new-item (p/as-managed rendered env)]
                              (set! items (assoc items key new-item))
                              (p/dom-insert new-item dom-parent anchor)
                              (when dom-entered?
                                (p/dom-entered! new-item))
                              (p/destroy! item)

                              (recur (p/dom-first new-item) (dec idx) updated)
                              ))))))))]

        (set! item-keys new-keys)

        ;; remove old items/render results
        (reduce-kv
          (fn [_ key item]
            (when-not (contains? updated key)
              (p/destroy! item)
              (set! items (dissoc items key))
              (set! item-vals (dissoc item-vals key))))
          nil
          items)))
    :synced)

  (destroy! [this]
    (.remove marker-before)
    (when items
      (reduce-kv
        (fn [_ _ item]
          (p/destroy! item))
        nil
        items))
    (.remove marker-after)))

;; FIXME: this shouldn't initialize everything in sync. might take too long
;; could do work in chunks, maybe even check if items are visible at all?
;; FIXME: with 6x throttle this already takes 150ms for 100 items
(deftype KeyedCollectionInit [coll key-fn render-fn]
  p/IConstruct
  (as-managed [this env]
    (let [coll (vec coll) ;; FIXME: could use into-array, colls are never modified again, only used to look stuff up by index
          len (count coll)
          marker-before (js/document.createComment "coll-start")
          marker-after (js/document.createComment "coll-end")]

      ;; FIXME: should find a way to remove the transient/persistent collections
      ;; they account for at least 50% of the time spent here
      ;; could maybe use an array and do the key->idx mapping sometime later
      (loop [idx 0
             items (transient {})
             keys (transient [])
             vals (transient {})]

        (if (>= idx len)
          (KeyedCollection.
            env
            coll
            key-fn
            render-fn
            (persistent! items)
            (persistent! keys)
            (persistent! vals)
            marker-before
            marker-after
            false)

          (let [val (nth coll idx)
                key (key-fn val)
                rendered (render-fn val idx key)
                managed (p/as-managed rendered env)]

            (recur
              (inc idx)
              (assoc! items key managed)
              (conj! keys key)
              (assoc! vals key rendered)))))))

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
   ^:mutable render-fn
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
          new-coll (vec (.-coll next))
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
    (let [coll (vec coll)
          marker-before (js/document.createComment "coll-start")
          marker-after (js/document.createComment "coll-end")
          arr (js/Array.)]

      (reduce
        (fn [idx val]
          (.push arr (p/as-managed (render-fn val idx) env))
          (inc idx))
        0
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
  (if-not (nil? key-fn)
    (KeyedCollectionInit. coll key-fn render-fn)
    (SimpleCollectionInit. coll render-fn)))