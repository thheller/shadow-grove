(ns shadow.grove.ui.vlist2
  (:require
    [goog.style :as gs]
    [goog.functions :as gfn]
    [shadow.cljs.modern :refer (defclass)]
    [shadow.arborist.protocols :as ap]
    [shadow.arborist.dom-scheduler :as ds]
    [shadow.grove.protocols :as gp]
    [shadow.grove.ui.util :as util]
    [shadow.arborist.common :as common]
    [shadow.arborist.attributes :as a]
    [shadow.arborist.collections :as coll]
    [shadow.grove :as-alias sg]
    [shadow.grove.devtools.protocols :as devp]
    [shadow.grove.keyboard :as keyboard]
    [shadow.grove.runtime :as rt]
    [shadow.grove.components :as comp]
    [shadow.grove.impl :as impl])
  (:import [goog.events KeyHandler]))

(defn ev-stop [^js e]
  (.stopPropagation e)
  (.preventDefault e))

(declare VirtualSeed)

(defclass VirtualList
  (field env)
  (field opts)
  (field read-fn)
  (field item-fn)

  (field ^rt/SlotRef slot-ref)
  (field last-result)

  (field remote-count 0)

  (field visible-offset 0)
  (field visible-count 0)
  (field visible-end 0)

  (field ^js container-el) ;; the other scroll container
  (field ^js inner-el) ;; the inner container providing the height
  (field ^js box-el) ;; the box element moving inside inner-el
  (field ^not-native box-root)
  (field dom-entered? false)

  (field ^KeyHandler key-handler)

  (field focus-idx 0)
  (field focused? false)

  (constructor [this e o rfn ifn]
    (set! env e)
    (set! opts o)
    (set! read-fn rfn)
    (set! item-fn ifn)

    (set! slot-ref (rt/SlotRef. this 0 nil nil))

    ;; FIXME: this (.-config config) stuff sucks
    ;; only have it because config is VirtualConfig class which we check identical? on
    (let [{:keys [scroll-delay box-style box-class] :or {scroll-delay 16}} opts]

      (set! container-el (js/document.createElement "div"))
      (gs/setStyle container-el
        #js {"outline" "none"
             "overflow-y" "auto"
             "width" "100%"
             "min-height" "100%"
             "height" "100%"})

      (when-some [tabindex (:tab-index opts)]
        (set! container-el -tabIndex tabindex))

      (set! key-handler (KeyHandler. container-el))

      (.listen key-handler "key" #_js/goog.events.KeyHandler.EventType
        (fn [^goog e]
          (case (keyboard/str-key e)
            "arrowup"
            (do (.focus-move! this -1)
                (ev-stop e))

            "pageup"
            (do (.focus-move! this -10)
                (ev-stop e))

            "arrowdown"
            (do (.focus-move! this 1)
                (ev-stop e))

            "pagedown"
            (do (.focus-move! this 10)
                (ev-stop e))

            "enter"
            (when-some [select-event (:select-event opts)]
              (let [{:keys [offset slice]}
                    last-result

                    item-idx
                    (- focus-idx offset)

                    item
                    (nth slice item-idx)

                    comp
                    (comp/get-component env)]

                (gp/handle-event! comp (assoc select-event :idx focus-idx :item item) nil env)
                ))

            nil
            )))

      (.addEventListener container-el "focus"
        (fn [e]
          (set! focused? true)
          (.render-slice! this)
          ))

      (.addEventListener container-el "blur"
        (fn [e]
          (set! focused? false)
          (.render-slice! this)))

      (set! inner-el (js/document.createElement "div"))
      (gs/setStyle inner-el
        #js {"width" "100%"
             "position" "relative"
             "height" "0"})
      (.appendChild container-el inner-el)

      (set! box-el (js/document.createElement "div"))
      (let [box-style (merge box-style {:position "absolute" :top "0px" :width "100%"})]
        (a/set-attr env box-el :style nil box-style))

      (when box-class
        (a/set-attr env box-el :class nil box-class))

      (.appendChild inner-el box-el)

      (set! box-root (common/managed-root env))

      (.addEventListener container-el "scroll"
        (gfn/debounce #(.handle-scroll! this %)
          ;; there is a good balance between too much work and too long wait
          ;; every scroll update will trigger a potentially complex DOM change
          ;; so it shouldn't do too much
          scroll-delay))))

  gp/IProvideSlot
  (-init-slot-ref [this idx]
    slot-ref)

  (-invalidate-slot! [this idx]
    (.update-query! this))

  ap/IManaged
  (supports? [this ^VirtualSeed next]
    (instance? VirtualSeed next))

  (dom-sync! [this ^VirtualSeed next]
    (let [prev-opts opts
          {:keys [tab-index] :as next-opts} (.-opts next)

          prev-read-fn read-fn
          next-read-fn (.-read-fn next)

          prev-item-fn item-fn
          next-item-fn (.-item-fn next)]

      (set! opts next-opts)
      (set! item-fn next-item-fn)
      (set! read-fn next-read-fn)

      (when (not= prev-opts next-opts)
        ;; FIXME: update more opts
        (when (not= (:tab-index opts) tab-index)
          (set! container-el -tabIndex tab-index)))

      (if-not (identical? next-read-fn prev-read-fn)
        ;; read-fn changed, fetch new query
        ;; can ignore item-fn in this path, since it re-renders anyway
        (.update-query! this)

        ;; only item-fn changed, just re-render
        (when-not (identical? prev-item-fn next-item-fn)
          (.render-slice! this)))))

  (dom-insert [this parent anchor]
    (.insertBefore parent container-el anchor)
    (ap/dom-insert box-root box-el nil))

  (dom-first [this]
    container-el)

  (dom-entered! [this]
    (set! dom-entered? true)

    (ap/dom-entered! box-root)

    (ds/read!
      ;; can only measure once added to the actual document
      ;; FIXME: should also add a resizeobserver in case things get resized
      (.measure! this)
      (.update-query! this)))

  (destroy! [this ^boolean dom-remove?]
    (when-some [cleanup (.-cleanup slot-ref)]
      (cleanup @slot-ref))

    (when dom-remove?
      (.remove container-el))

    (ap/destroy! box-root false))

  Object
  (update-query! [this]
    (let [query-opts
          {:offset visible-offset
           :num visible-count}

          [ready result]
          (binding [rt/*slot-provider* this
                    rt/*env* env
                    rt/*slot-idx* 0
                    rt/*slot-value* last-result
                    rt/*claimed* false
                    rt/*ready* true]

            ;; sg/suspend! may set this to false
            [rt/*ready* (read-fn query-opts)])]

      ;; only update when ready, otherwise wait for invalidation to trigger again
      ;; FIXME: maybe after a delay show loading spinner?
      (when ready

        (let [item-count (:item-count result)
              item-height (:item-height opts)]

          (set! last-result result)

          (when (not= remote-count item-count)
            (gs/setStyle inner-el "height" (str (* item-count item-height) "px"))
            (set! remote-count item-count))

          (gs/setStyle box-el "top" (str (* item-height visible-offset) "px"))
          (.render-slice! this)))))

  (render-slice! [this]
    (let [{:keys [offset slice]}
          last-result

          {:keys [key-fn content-wrap]}
          opts

          content
          (if key-fn
            (coll/keyed-seq slice key-fn
              (fn [val idx key]
                (item-fn val {:idx (+ offset idx)
                              :key key
                              :focus (and focused? (= focus-idx idx))})))

            (coll/simple-seq slice
              (fn [val idx]
                (item-fn val {:idx (+ offset idx)
                              :focus (and focused? (= focus-idx idx))}))))]

      ;; FIXME: there are issues with rendering items into a box and only moving that box
      ;; don't know why yet but scrolling slowly with mousewheel causes visible flicker

      (ap/update! box-root
        (if-not content-wrap
          content
          (content-wrap content))
        )))

  (measure! [this]
    (let [scroll-top
          (.-scrollTop container-el)

          {:keys [item-height]}
          opts

          min-idx
          (js/Math.floor (/ scroll-top item-height))

          container-height
          (.-clientHeight container-el)

          _ (when (zero? container-height)
              (js/console.warn "vlist container height measured zero!" this)
              (loop [el container-el]
                (when el
                  (js/console.log (.-clientHeight el) (.-isConnected el) el)
                  (recur (.-parentElement el)))))

          max-items ;; inc to avoid half items
          (inc (js/Math.ceil (/ container-height item-height)))]

      (set! visible-offset min-idx)
      (set! visible-count max-items)
      (set! visible-end (+ min-idx max-items))))

  (handle-scroll! [this e]
    (ds/read!
      (.measure! this)
      (.update-query! this)))

  (focus-set! [this next-idx]
    (set! focus-idx next-idx)
    (.render-slice! this))

  ;; FIXME: need to load more data when moving out of visible area
  (focus-move! [this dir]
    (let [max
          (dec remote-count)

          next-idx
          (-> (+ focus-idx dir)
              (js/Math.min max)
              (js/Math.max 0))]

      (when (<= 0 next-idx max)
        (.focus-set! this next-idx)))))

(deftype VirtualSeed [opts read-fn item-fn]
  ap/IConstruct
  (as-managed [this env]
    (VirtualList. env opts read-fn item-fn)))

(defn render [opts read-fn item-fn]
  (VirtualSeed. opts read-fn item-fn))

(when ^boolean js/goog.DEBUG
  (extend-protocol devp/ISnapshot
    VirtualList
    (snapshot [this ctx]
      {:type `VirtualList
       :children [(devp/snapshot (.-node (.-box-root this)) ctx)]})))