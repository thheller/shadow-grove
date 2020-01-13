(ns shadow.experiments.grove.main.streams
  (:require
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.grove.protocols :as gp]
    [goog.style :as gs]
    [shadow.experiments.grove.main.util :as util]))

(util/assert-not-in-worker!)

(declare StreamNode)

(deftype StreamRoot
  [env
   stream-engine
   stream-id
   stream-key
   opts
   item-fn
   ^:mutable container-el
   ^:mutable inner-el
   ]

  ap/IUpdatable
  (supports? [this next]
    (and (instance? StreamNode next)
         (keyword-identical? stream-key (.-stream-key next))))

  (dom-sync! [this ^StreamNode next])

  ap/IManageNodes
  (dom-insert [this parent anchor]
    (.insertBefore parent container-el anchor))

  (dom-first [this]
    container-el)

  ap/IDestructible
  (destroy! [this]
    (.remove container-el))

  Object
  (init! [this]
    (set! container-el (js/document.createElement "div"))
    (gs/setStyle container-el
      #js {"outline" "none"
           "position" "relative"
           "overflow-y" "auto"})

    (set! inner-el (js/document.createElement "div"))
    (gs/setStyle inner-el
      #js {"position" "absolute"
           "top" "0px"
           "left" "0px"
           "width" "100%"
           "height" "100%"})
    (.appendChild container-el inner-el)

    (let [callback
          (fn [msg]
            (js/console.log "stream msg" msg))]

      (gp/stream-init stream-engine env stream-id stream-key (:stream-opts opts {}) callback))))

(deftype StreamNode [stream-key opts item-fn]
  ap/IConstruct
  (as-managed [this env]
    (let [stream-engine (::gp/query-engine env)]
      (when-not (satisfies? gp/IStreamEngine stream-engine)
        (throw (ex-info "engine does not implement streaming features" {:env env})))

      (doto (StreamRoot. env stream-engine (util/next-id) stream-key opts item-fn nil nil)
        (.init!)))))
