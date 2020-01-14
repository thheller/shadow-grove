(ns shadow.experiments.grove.main.vlist
  (:require
    [goog.style :as gs]
    [goog.functions :as gfn]
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.main.util :as util]
    [shadow.experiments.arborist.common :as common]))

(declare VirtualNode)

(deftype VirtualList
  [env
   ^not-native query-engine
   query-id
   ^:mutable query
   ident
   attr
   opts
   item-fn
   ^:mutable items
   ^:mutable ^js container-el
   ^:mutable ^js inner-el]

  ap/IUpdatable
  (supports? [this ^VirtualNode next]
    (and (instance? VirtualNode next)
         (= ident (.-ident next))
         (keyword-identical? attr (.-attr next))))

  (dom-sync! [this next]
    (js/console.log "vlist sync" this next))

  ap/IManageNodes
  (dom-insert [this parent anchor]
    (.insertBefore parent container-el anchor))

  (dom-first [this]
    container-el)

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
           "height" "100%"})

    (set! inner-el (js/document.createElement "div"))
    (gs/setStyle inner-el
      #js {"width" "100%"
           "position" "relative"
           "height" "0"})
    (.appendChild container-el inner-el)

    (.addEventListener container-el "scroll"
      (gfn/debounce #(.handle-scroll! this %) 100))

    ;; FIXME: this should first get the size of the container-el
    ;; but for that we need to wait till inserted into the DOM
    (set! this -visible-offset 0)
    (set! this -max-items 40)
    (set! this -visible-end 40)
    (.update-query! this 0 40))

  (update-query! [this offset num]
    (when query
      (gp/unregister-query query-engine query-id))

    (let [attr-opts {:offset offset :num num}
          attr-with-opts (list attr attr-opts)]
      (set! query (if ident [{ident [attr-with-opts]}] [attr-with-opts])))

    (gp/register-query query-engine env query-id query {} #(.handle-query-result! this %)))

  (handle-query-result! [this result]
    (let [{:keys [item-count offset slice] :as data}
          (if ident (get-in result [ident attr] (get result attr)))

          {:keys [item-height]}
          opts]

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
                (let [rendered (item-fn val idx)
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
                      rendered (item-fn val idx)]
                  (if (ap/supports? managed rendered)
                    (do (ap/dom-sync! managed rendered)
                        (aset items idx (assoc current :val val)))
                    (aset items idx (assoc current
                                      :val val
                                      :managed (common/replace-managed env current rendered)))))))))
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
          {:keys [item-height]} opts

          min-idx
          (js/Math.floor (/ scroll-top item-height))

          container-height
          (.-clientHeight container-el)

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

(deftype VirtualNode [ident attr opts item-fn]
  ap/IConstruct
  (as-managed [this env]
    (let [query-engine (::gp/query-engine env)]
      (when-not query-engine
        (throw (ex-info "missing query engine" {:env env})))
      (doto (VirtualList. env query-engine (util/next-id) nil ident attr opts item-fn nil nil nil)
        (.init!)))))

