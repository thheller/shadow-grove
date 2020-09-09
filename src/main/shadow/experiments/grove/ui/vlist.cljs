(ns shadow.experiments.grove.ui.vlist
  (:require
    [goog.style :as gs]
    [goog.functions :as gfn]
    [shadow.cljs.modern :refer (defclass)]
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.arborist.dom-scheduler :as ds]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.ui.util :as util]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.attributes :as a]))

(declare VirtualInit)

(defclass VirtualList
  (field ^VirtualConfig config)
  (field env)
  (field ^not-native query-engine)
  (field query-id)
  (field query)
  (field ident)
  (field opts)
  (field last-result)
  (field items)
  (field ^js container-el) ;; the other scroll container
  (field ^js inner-el) ;; the inner container providing the height
  (field ^js box-el) ;; the box element moving inside inner-el
  (field dom-entered? false)
  (field focus-idx 0)
  (field focused? false)

  (constructor [this e c o]
    (set! env e)
    (set! config c)
    (set! opts o)
    (set! query-id (util/next-id))
    (set! ident (:ident opts))

    (let [qe (::gp/query-engine env)]
      (when-not qe
        (throw (ex-info "missing query engine" {:env env})))

      (set! query-engine qe)

      ;; FIXME: this (.-config config) stuff sucks
      ;; only have it because config is VirtualConfig class which we check identical? on
      (let [{:keys [scroll-delay box-style] :or {scroll-delay 16}} (.-config config)]

        (set! container-el (js/document.createElement "div"))
        (gs/setStyle container-el
          #js {"outline" "none"
               "overflow-y" "auto"
               "width" "100%"
               "min-height" "100%"
               "height" "100%"})

        (when-some [tabindex (:tabindex opts)]
          (set! container-el -tabIndex tabindex))

        (.addEventListener container-el "focus"
          (fn [e]
            (set! focused? true)
            ;; (.update-item! this focus-idx)
            ))

        (.addEventListener container-el "blur"
          (fn [e]
            (set! focused? false)
            ;; (.update-item! this focus-idx)
            ))

        (set! inner-el (js/document.createElement "div"))
        (gs/setStyle inner-el
          #js {"width" "100%"
               "position" "relative"
               "height" "0"})
        (.appendChild container-el inner-el)

        (set! box-el (js/document.createElement "div"))
        (let [box-style (merge box-style {:position "absolute" :top "0px" :width "100%"})]
          (a/set-attr env box-el :style nil box-style))
        (.appendChild inner-el box-el)

        (.addEventListener container-el "scroll"
          (gfn/debounce #(.handle-scroll! this %)
            ;; there is a good balance between too much work and too long wait
            ;; every scroll update will trigger a potentially complex DOM change
            ;; so it shouldn't do too much
            scroll-delay)))))

  ap/IManaged
  (supports? [this ^VirtualInit next]
    (and (instance? VirtualInit next)
         (identical? config (.-config next))
         ;; might be nil, set when constructing from opts
         (= ident (:ident (.-opts next)))))

  (dom-sync! [this ^VirtualInit next]
    (let [{:keys [tabindex] :as next-opts} (.-opts next)]
      (when (not= opts next-opts)

        (when (not= (:tabindex opts) tabindex)
          (set! container-el -tabIndex tabindex))

        (set! opts next-opts)

        ;; FIXME: this is least efficient way to re-render all items
        ;; should be smarter here
        (.handle-query-result! this last-result)

        ;; (js/console.log "vlist sync, opts changed" this next)
        )))

  (dom-insert [this parent anchor]
    (.insertBefore parent container-el anchor))

  (dom-first [this]
    container-el)

  (dom-entered! [this]
    (set! dom-entered? true)

    (ds/read!
      ;; can only measure once added to the actual document
      ;; FIXME: should also add a resizeobserver in case things get resized
      (.measure! this)
      (.update-query! this (.-visible-offset this) (.-max-items this))))

  (destroy! [this]
    (when query
      (gp/query-destroy query-engine query-id))

    (.remove container-el)
    (when items ;; query might still be pending
      (.forEach items ;; sparse array, doseq processes too many
        (fn [item idx]
          (ap/destroy! item)))))

  Object
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

      (set! last-result result)

      ;; FIXME: rewrite this!
      ;; sparse array makes no sense anymore. only ever keeping the elements visible anyways

      (cond
        (not items)
        (do (set! items (js/Array. item-count))
            (gs/setStyle inner-el "height" (str (* item-count item-height) "px")))

        ;; FIXME: this needs to be handled differently, shouldn't just throw everything away
        (not= item-count (.-length items))
        (do (.forEach items ;; sparse array, doseq processes too many
              (fn [item idx]
                (ap/destroy! item)))
            (set! items (js/Array. item-count))
            (set! container-el -scrollTop 0)
            (gs/setStyle inner-el "height" (str (* item-count item-height) "px")))

        :else
        nil)

      ;; FIXME: can do this directly on scroll?
      (gs/setStyle box-el "top" (str (* item-height offset) "px"))

      (.cleanup! this)

      ;; FIXME: this would likely be more efficient DOM wise when traversing backwards
      ;; easier to keep track of anchor-el for dom-insert
      (reduce-kv
        (fn [_ offset-idx val]
          (let [idx (+ offset-idx offset)]
            ;; might have scrolled out while query was loading
            ;; can skip render of elements that will be immediately replaced
            (when (.in-visible-range? this idx)
              ;; render and update/insert
              (let [rendered (. config (item-fn val idx opts))]
                (if-let [current (aget items idx)]
                  ;; current exists, try to update or replace
                  (if (ap/supports? current rendered)
                    (ap/dom-sync! current rendered)
                    (let [new-managed (common/replace-managed env current rendered)]
                      (aset items idx new-managed)
                      (when dom-entered?
                        (ap/dom-entered! new-managed)
                        )))
                  ;; doesn't exist, create managed and insert at correct DOM position
                  (let [managed (ap/as-managed rendered env)
                        ;; FIXME: would be easier with wrapper elements
                        ;; just create them once and replace the contents
                        ;; but no wrappers means potentially supporting CSS grid?
                        next-item (.find-next-item this idx)
                        anchor-el (when next-item (ap/dom-first next-item))]

                    ;; insert before next item, or append if it doesn't exist
                    (ap/dom-insert managed box-el anchor-el)
                    (when dom-entered?
                      (ap/dom-entered! managed))
                    (aset items idx managed)))))))
        nil
        slice)))

  ;; sparse array, idx might be 6 and the next item is 10
  ;; FIXME: should maintain the rendered items better and avoid all this logic
  (find-next-item [this idx]
    (loop [idx (inc idx)]
      (when (< idx (.-visible-end this))
        (if-some [item (aget items idx)]
          item
          (recur (inc idx))))))

  (in-visible-range? [this idx]
    (<= (.-visible-offset this) idx (.-visible-end this)))

  (cleanup! [this]
    (.forEach items
      (fn [item idx]
        (when-not (.in-visible-range? this idx)
          (ap/destroy! item)
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
    (ds/read!
      (.measure! this)
      (.update-query! this (.-visible-offset this) (.-max-items this)))
    ))

(deftype VirtualInit [config opts]
  ap/IConstruct
  (as-managed [this env]
    (VirtualList. env config opts)))

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
