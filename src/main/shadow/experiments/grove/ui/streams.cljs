(ns shadow.experiments.grove.ui.streams
  (:require
    [goog.style :as gs]
    [goog.object :as gobj]
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.arborist.attributes :as attr]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.ui.util :as util]
    [shadow.experiments.grove.keyboard :as keyboard])

  (:import [goog.events KeyHandler]))


;; an attempt at a totally mutable element that doesn't try to reconstruct the whole dom
;; every time. turns out that is pretty tricky and probably not worth.
;; bunch of tradeoffs when trying to merge state from different sources
;; and not knowing that the DOM is actually currently displaying

(util/assert-not-in-worker!)

(declare StreamInit)

(deftype StreamRoot
  [env
   stream-engine
   stream-id
   stream-key
   opts
   item-fn
   ^:mutable container-el
   ^:mutable inner-el
   ^boolean ^:mutable dom-entered?
   ^goog ^:mutable key-handler
   ^:mutable focus-idx
   items]

  ap/IManaged
  (supports? [this ^StreamInit next]
    (identical? this next))

  (dom-sync! [this ^StreamInit next]
    ;; not sure what this should sync. shouldn't really need updating
    )

  (dom-insert [this parent anchor]
    (.insertBefore parent container-el anchor))

  (dom-first [this]
    container-el)

  (dom-entered! [this]
    ;; (.focus container-el)

    (set! dom-entered? true))

  (destroy! [this]
    (gp/stream-destroy stream-engine stream-id stream-key)

    (.dispose key-handler)
    (.remove container-el)

    (doseq [{:keys [managed]} (array-seq items)]
      (ap/destroy! managed))

    (set! items -length 0))


  Object
  (init! [this]
    (set! container-el (js/document.createElement "div"))
    (gs/setStyle container-el
      #js {"outline" "none"
           "position" "relative"
           "overflow-y" "auto"
           "width" "100%"
           "height" "100%"})

    (gobj/set (.. container-el -dataset) "keyboardFocus" true)

    (set! key-handler (KeyHandler. container-el))

    (.listen key-handler "key" #_js/goog.events.KeyHandler.EventType
      (fn [^goog e]
        (let [pretty-key (keyboard/str-key e)]
          (case pretty-key
            "arrowup"
            (do (.focus-move! this -1)
                (.preventDefault e))

            "pageup"
            (do (.focus-move! this -10)
                (.preventDefault e))

            "arrowdown"
            (do (.focus-move! this 1)
                (.preventDefault e))

            "pagedown"
            (do (.focus-move! this 10)
                (.preventDefault e))

            nil
            ))))

    (set! container-el -tabIndex 0)

    (.addEventListener container-el "focus"
      (fn [e]
        ;; FIXME: should this focus the actual sub element or retain focus itself?
        ))

    (set! inner-el (js/document.createElement "div"))
    (gs/setStyle inner-el
      #js {"position" "absolute"
           "top" "0px"
           "left" "0px"
           "width" "100%"
           "height" "100%"})
    (.appendChild container-el inner-el)

    (gp/stream-init stream-engine env stream-id stream-key
      (:stream-opts opts {})
      #(.handle-stream-msg this %)))

  (clear! [this]
    (gp/stream-clear stream-engine stream-key)

    (loop []
      (when-some [^goog div (.-lastElementChild inner-el)]
        (let [managed (.-shadow$managed div)]
          (ap/destroy! managed)
          (.remove div)
          (recur)))))

  (make-item [this data item-idx]
    (let [el (js/document.createElement "div")
          rendered (item-fn data {:focus (= item-idx focus-idx)})
          managed (ap/as-managed rendered env)]
      ;; (set! (.. el -style -height) (str (:item-height opts) "px"))
      (set! el -shadow$managed managed)
      (ap/dom-insert managed el nil)

      {:el el
       :data data
       :managed managed}))

  (handle-stream-msg [this {:keys [op] :as msg}]
    (case op
      :init
      (do (assert (vector? (:items msg)))
          (reduce-kv
            (fn [_ idx item]
              (let [{:keys [el] :as item} (.make-item this item idx)]
                (.unshift items item)
                (.insertBefore inner-el el (.-firstChild inner-el))
                ))
            nil
            (:items msg)))

      :add
      (let [{:keys [el] :as item} (.make-item this (:item msg) 0)]

        (.insertBefore inner-el el (.-firstChild inner-el))

        ;; re-render current focus
        (cond
          (zero? (alength items))
          nil

          ;; if zero is currently focused, re-render without focus
          ;; since new items becomes focus
          (zero? focus-idx)
          (when-some [{:keys [data managed]} (aget items 0)]
            (ap/dom-sync! managed (item-fn data {:focus false})))

          ;; lower item is active, moves lower and move focus with it
          (pos? focus-idx)
          (set! focus-idx (inc focus-idx)))

        (.unshift items item)

        ;; FIXME: take actual capacity config, 1000 is arbitrary
        #_(when (> (.-childElementCount inner-el) 1000)
            (let [^goog last (.-lastElementChild inner-el)
                  managed (.-shadow$managed last)]

              (ap/destroy! managed)
              (.remove last))))

      (js/console.log "unhandled stream msg" op msg)
      ))

  (focus-move! [this dir]
    (let [max
          (dec (alength items))

          next-idx
          (-> (+ focus-idx dir)
              (js/Math.min max)
              (js/Math.max 0))]

      (when (<= 0 next-idx max)
        (let [current (aget items focus-idx)
              next (aget items next-idx)]
          (ap/dom-sync! (:managed current) (item-fn (:data current) {:focus false}))
          (ap/dom-sync! (:managed next) (item-fn (:data next) {:focus true}))
          (set! focus-idx next-idx))
        ))))

(defprotocol StreamHandleActions
  (clear! [this]))

(deftype StreamHandle [stream-key opts item-fn ^clj ^:mutable mounted]
  ap/IConstruct
  (as-managed [this env]
    (let [stream-engine (::gp/query-engine env)]
      (when-not (satisfies? gp/IStreamEngine stream-engine)
        (throw (ex-info "engine does not implement streaming features" {:env env})))

      (let [root (doto (StreamRoot. env stream-engine (util/next-id) stream-key opts item-fn nil nil false nil 0 #js [])
                   (.init!))]

        (set! mounted root)

        root)))

  StreamHandleActions
  (clear! [this]
    (when-not mounted
      (throw (ex-info "not mounted!" {:this this})))

    (.clear! mounted)))

(defn init [stream-key opts item-fn]
  (StreamHandle. stream-key opts item-fn nil))

