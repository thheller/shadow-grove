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
    [shadow.experiments.arborist.attributes :as a]
    [shadow.experiments.arborist.collections :as coll]
    [shadow.experiments.grove.keyboard :as keyboard]
    [shadow.dom :as dom]
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove.components :as comp])
  (:import [goog.events KeyHandler]))

(declare VirtualInit)

(deftype ListItem [idx data managed])

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

  (constructor [this e c o]
    (set! env e)
    (set! config c)
    (set! opts o)
    (set! query-id (util/next-id))
    (set! ident (:ident opts))

    (let [rt (::rt/runtime-ref env)
          qe (::gp/query-engine @rt)]
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

        (set! key-handler (KeyHandler. container-el))

        (comment
          (.listen key-handler "key" #_js/goog.events.KeyHandler.EventType
            (fn [^goog e]
              (case (keyboard/str-key e)
                "arrowup"
                (do (.focus-move! this -1)
                    (dom/ev-stop e))

                "pageup"
                (do (.focus-move! this -10)
                    (dom/ev-stop e))

                "arrowdown"
                (do (.focus-move! this 1)
                    (dom/ev-stop e))

                "pagedown"
                (do (.focus-move! this 10)
                    (dom/ev-stop e))

                "enter"
                (when-some [select-event (:select-event opts)]
                  (let [item (aget items focus-idx)
                        comp (comp/get-component env)]

                    (gp/handle-event! comp (assoc select-event :idx focus-idx :item (.-data item)) nil env)
                    ))

                nil
                )))

          (.addEventListener container-el "focus"
            (fn [e]
              (set! focused? true)
              (.update-item! this focus-idx)
              ))

          (.addEventListener container-el "blur"
            (fn [e]
              (set! focused? false)
              (.update-item! this focus-idx)
              )))

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

        (set! box-root (common/managed-root env))

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
    (when query
      (gp/query-destroy query-engine query-id))

    (when dom-remove?
      (.remove container-el))

    (ap/destroy! box-root false)

    (when items ;; query might still be pending
      (.forEach items ;; sparse array, doseq processes too many
        (fn [^ListItem item idx]
          (ap/destroy! (.-managed item) false)))))

  Object
  ;; FIXME: this unfortunately has to fetch all visible rows
  ;; since the query will also be responsible for pushing updates to us
  ;; that means its very inefficient when scrolling down/up a single row
  ;; assuming 50 visible rows we already have 49 of them yet fetch 50
  ;; maybe the query abstraction is not the best for this
  ;; or maybe handle it on the "server" side so that is still knows it
  ;; needs to update us for the visible rows but only provide a subset
  ;; initially sometimes?
  ;; good enough for now to now worry about it but sometimes to
  ;; optimize later.
  ;; currently there is no "query-update" so it just destroys/creates
  ;; a fresh query each time
  (update-query! [this]
    (when query
      (gp/query-destroy query-engine query-id))

    (let [attr-opts {:offset visible-offset
                     :num visible-count}
          attr-with-opts (list (.-attr config) attr-opts)]
      (set! query (if ident [{ident [attr-with-opts]}] [attr-with-opts])))

    (gp/query-init query-engine query-id query {} #(.handle-query-result! this %)))

  (handle-query-result! [this result]
    (let [{:keys [item-count offset slice] :as data}
          (if ident
            (get-in result [ident (.-attr config)])
            (get result (.-attr config)))

          {:keys [item-height key-fn]}
          (.-config config)]

      (set! last-result result)

      (when (not= remote-count item-count)
        (gs/setStyle inner-el "height" (str (* item-count item-height) "px"))
        (set! remote-count item-count))

      (ap/update! box-root
        (if key-fn
          (coll/keyed-seq slice key-fn
            (fn [val idx key]
              ;; FIXME: restore focus handling
              (. config (item-fn val {:idx (+ offset idx)}))))

          (coll/simple-seq slice
            (fn [val idx]
              ;; FIXME: restore focus handling
              (. config (item-fn val {:idx (+ offset idx)}))))))

      (gs/setStyle box-el "top" (str (* item-height visible-offset) "px"))))

  (measure! [this]
    (let [scroll-top (.-scrollTop container-el)
          {:keys [item-height]}
          (.-config config)

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
    (let [old-idx focus-idx]
      (set! focus-idx next-idx)
      (.update-item! this old-idx)
      (.update-item! this next-idx)))

  ;; FIXME: need to load more data when moving out of visible area
  (focus-move! [this dir]
    (let [max
          (dec (alength items))

          next-idx
          (-> (+ focus-idx dir)
              (js/Math.min max)
              (js/Math.max 0))]

      (when (<= 0 next-idx max)
        (.focus-set! this next-idx)))))

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
