(ns shadow.experiments.grove.main.vlist
  (:require
    [goog.style :as gs]
    [goog.functions :as gfn]
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.main.util :as util]
    [shadow.experiments.arborist.common :as common]))

(declare VirtualInit)

(deftype VirtualList
  [^VirtualConfig config
   env
   ^not-native query-engine
   query-id
   ^:mutable query
   ident
   opts
   ^:mutable items
   ^:mutable ^js container-el
   ^:mutable ^js inner-el
   ^:mutable dom-entered?]

  ap/IUpdatable
  (supports? [this ^VirtualInit next]
    (and (instance? VirtualInit next)
         (identical? config (.-config next))
         ;; might be nil, set when constructing from opts
         (= ident (:ident (.-opts next)))))

  (dom-sync! [this ^VirtualInit next]
    (when (not= opts (.-opts next))
      (js/console.log "vlist sync, opts changed" this next)))

  ap/IManageNodes
  (dom-insert [this parent anchor]
    (.insertBefore parent container-el anchor))

  (dom-first [this]
    container-el)

  (dom-entered! [this]
    (set! dom-entered? true)

    ;; can only measure once added to the actual document
    ;; FIXME: should also add a resizeobserver in case things get resized
    (.measure! this)
    (.update-query! this (.-visible-offset this) (.-max-items this)))

  ap/IDestructible
  (destroy! [this]
    (.remove container-el)
    (.forEach items ;; sparse array, doseq processes too many
      (fn [item idx]
        (ap/destroy! (:managed item)))))

  Object
  (init! [this]
    (set! container-el (js/document.createElement "div"))
    (gs/setStyle container-el
      #js {"outline" "none"
           "overflow-y" "auto"
           "width" "100%"
           "min-height" "100%"
           "height" "100%"})

    (set! inner-el (js/document.createElement "div"))
    (gs/setStyle inner-el
      #js {"width" "100%"
           "position" "relative"
           "height" "0"})
    (.appendChild container-el inner-el)

    (.addEventListener container-el "scroll"
      (gfn/debounce #(.handle-scroll! this %)
        ;; there is a good balance between too much work and too long wait
        ;; every scroll update will trigger a potentially complex DOM change
        ;; so it shouldn't do too much
        (:scroll-delay config 25))))

  (update-query! [this offset num]
    (when query
      (gp/query-destroy query-engine query-id))

    (let [attr-opts {:offset offset :num num}
          attr-with-opts (list (.-attr config) attr-opts)]
      (set! query (if ident [{ident [attr-with-opts]}] [attr-with-opts])))

    (gp/query-init query-engine query-id query {} #(.handle-query-result! this %)))

  (handle-query-result! [this result]
    (let [{:keys [item-count offset slice] :as data}
          (if ident (get-in result [ident (.-attr config)] (get result (.-attr config))))

          {:keys [item-height]}
          (.-config config)]

      (cond
        (not items)
        (do (set! items (js/Array. item-count))
            (gs/setStyle inner-el "height" (str (* item-count item-height) "px")))

        (not= item-count (.-length items))
        (throw (ex-info "item count changed, TBD" {:this this :data data}))

        :else
        nil)

      (reduce-kv
        (fn [_ idx val]
          (when (.in-visible-range? this idx)
            (let [current (aget items idx)]
              (if-not current
                (let [rendered (. config (item-fn val idx opts))
                      managed (ap/as-managed rendered env)

                      el-wrapper
                      (doto (js/document.createElement "div")
                        (gs/setStyle #js {"position" "absolute"
                                          "top" (str (* item-height idx) "px")
                                          "height" (str item-height "px")
                                          "width" "100%"}))]

                  (set! el-wrapper -shadow$idx idx)

                  (ap/dom-insert managed el-wrapper nil)
                  (aset items idx {:wrapper el-wrapper :managed managed :idx idx :val val})

                  ;; FIXME: this should probably insert in the correct dom order?
                  ;; might be bad to rely on positioning to order things?
                  ;; dunno how to benchmark this, appending works just fine for now
                  (.appendChild inner-el el-wrapper))

                ;; current exists, try to update or replace
                (let [{:keys [managed]} current
                      rendered (. config (item-fn val idx opts))]
                  (if (ap/supports? managed rendered)
                    (do (ap/dom-sync! managed rendered)
                        (aset items idx (assoc current :val val)))
                    (let [new-managed (common/replace-managed env current rendered)]
                      (aset items idx (assoc current
                                        :val val
                                        :managed new-managed))
                      (when dom-entered?
                        (ap/dom-entered! new-managed)
                        ))))))))
        nil
        slice))

    (.cleanup! this))

  (in-visible-range? [this idx]
    (<= (.-visible-offset this) idx (.-visible-end this)))

  (cleanup! [this]
    (.forEach items
      (fn [item idx]
        (when-not (.in-visible-range? this idx)
          (ap/destroy! (:managed item))
          (.remove (:wrapper item))
          (js-delete items idx)))))

  (measure! [this]
    (let [scroll-top (.-scrollTop container-el)
          {:keys [item-height]}
          (.-config config)

          min-idx
          (js/Math.floor (/ scroll-top item-height))

          container-height
          (.-clientHeight container-el)

          _ (when (zero? container-height)
              (js/console.warn "vlist container height measured zero!" container-el this))

          max-items ;; inc to avoid half items
          (inc (js/Math.ceil (/ container-height item-height)))]

      (set! this -visible-offset min-idx)
      (set! this -max-items max-items)
      (set! this -visible-end (+ min-idx max-items))
      ))

  (handle-scroll! [this e]
    (.measure! this)
    (.update-query! this (.-visible-offset this) (.-max-items this))
    ))

(deftype VirtualInit [config opts]
  ap/IConstruct
  (as-managed [this env]
    (let [query-engine (::gp/query-engine env)]
      (when-not query-engine
        (throw (ex-info "missing query engine" {:env env})))
      (doto (VirtualList.
              config
              env
              query-engine
              (util/next-id)
              nil
              (:ident opts)
              opts
              nil
              nil
              nil
              false)
        (.init!)))))

(deftype VirtualConfig [attr config item-fn]
  IFn
  (-invoke [this opts]
    (VirtualInit. this opts)))

(defn configure [vlist-attr config item-fn]
  {:pre [(keyword? vlist-attr)
         (map? config)
         ;; FIXME: variable size would be nice but thats a lot more work
         (pos-int? (:item-height config))
         ;; FIXME: item-fn should be allowed to be IFn (ie. components)?
         ;; it is not because it needs to be invoked differently then
         ;; investigate if components actually make sense first though
         ;; the result should only ever be one element?
         (fn? item-fn)]}

  ;; shadow-tweak so it skips checking and blindly invokes IFN
  ;; FIXME: this should work via (defn configure ^not-native [...])
  ;; but doesn't because tag inference overwrites it with the deftype type
  ^not-native
  (VirtualConfig. vlist-attr config item-fn))
