(ns shadow.experiments.grove.ui.streams
  (:require
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.arborist.attributes :as attr]
    [shadow.experiments.grove.protocols :as gp]
    [goog.style :as gs]
    [shadow.experiments.grove.ui.util :as util]))


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
   ]

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

    (.remove container-el)

    (doseq [^goog div (array-seq (.-children inner-el))]
      (let [managed (.-shadow$managed div)]
        (ap/destroy! managed))))

  Object
  (init! [this]
    (set! container-el (js/document.createElement "div"))
    (gs/setStyle container-el
      #js {"outline" "none"
           "position" "relative"
           "overflow-y" "auto"
           "width" "100%"
           "height" "100%"})

    ;; prep for keyboard support somehow
    ;; needs a lot more changes in the framework before this can work

    #_(set! container-el -tabIndex 0)

    #_(.addEventListener container-el "focus"
        (fn [e]
          (js/console.log "focused" this)))

    #_(.addEventListener container-el "blur"
        (fn [e]
          (js/console.log "blur" this)))


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

  (make-item [this item]
    (let [el (js/document.createElement "div")
          rendered (item-fn item)
          managed (ap/as-managed rendered env)]
      (set! (.. el -style -height) (str (:item-height opts) "px"))
      (set! el -shadow$managed managed)
      (ap/dom-insert managed el nil)
      el))

  (handle-stream-msg [this {:keys [op] :as msg}]
    (case op
      :init
      (let [{:keys [items]} msg]
        (doseq [item items]
          (let [el (.make-item this item)]
            (.insertBefore inner-el el (.-firstChild inner-el))
            )))

      :add
      (let [{:keys [item]} msg
            el (.make-item this item)]
        (.insertBefore inner-el el (.-firstChild inner-el))

        ;; FIXME: take actual capacity config, 1000 is arbitrary
        (when (> (.-childElementCount inner-el) 1000)
          (let [^goog last (.-lastElementChild inner-el)
                managed (.-shadow$managed last)]

            (ap/destroy! managed)
            (.remove last))))

      (js/console.log "unhandled stream msg" op msg)
      )))

(defprotocol StreamHandleActions
  (clear! [this]))

(deftype StreamHandle [stream-key opts item-fn ^clj ^:mutable mounted]
  ap/IConstruct
  (as-managed [this env]
    (let [stream-engine (::gp/query-engine env)]
      (when-not (satisfies? gp/IStreamEngine stream-engine)
        (throw (ex-info "engine does not implement streaming features" {:env env})))

      (let [root (doto (StreamRoot. env stream-engine (util/next-id) stream-key opts item-fn nil nil false)
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

