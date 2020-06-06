(ns shadow.experiments.grove.ui.streams
  (:require
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.arborist.attributes :as attr]
    [shadow.experiments.grove.protocols :as gp]
    [goog.style :as gs]
    [shadow.experiments.grove.ui.util :as util]))

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
    (and (instance? StreamInit next)
         (keyword-identical? stream-key (.-stream-key next))
         (identical? item-fn (.-item-fn next))))

  (dom-sync! [this ^StreamInit next]
    ;; not sure what this should sync. shouldn't really need updating
    )

  (dom-insert [this parent anchor]
    (.insertBefore parent container-el anchor))

  (dom-first [this]
    container-el)

  (dom-entered! [this]
    (set! dom-entered? true))

  (destroy! [this]
    (.remove container-el))

  Object
  (init! [this]
    (set! container-el (js/document.createElement "div"))
    (gs/setStyle container-el
      #js {"outline" "none"
           "position" "relative"
           "overflow-y" "auto"
           "width" "100%"
           "height" "100%"})

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

  (make-item [this item]
    (let [el (js/document.createElement "div")
          rendered (item-fn item)
          managed (ap/as-managed rendered env)]
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

      ;; FIXME: don't delete all, try to dom-sync!
      :reset
      (do (doseq [^Element child (into [] (array-seq (.-children inner-el)))
                  :let [managed (.-shadow$managed child)]]
            (ap/destroy! managed)
            (.remove child))
          (.handle-stream-msg this (assoc msg :op :init)))

      :add
      (let [{:keys [item]} msg
            el (.make-item this item)]
        (.insertBefore inner-el el (.-firstChild inner-el)))

      (js/console.log "unhandled stream msg" op msg)
      )))

(deftype StreamInit [stream-key opts item-fn]
  ap/IConstruct
  (as-managed [this env]
    (let [stream-engine (::gp/query-engine env)]
      (when-not (satisfies? gp/IStreamEngine stream-engine)
        (throw (ex-info "engine does not implement streaming features" {:env env})))

      (doto (StreamRoot. env stream-engine (util/next-id) stream-key opts item-fn nil nil false)
        (.init!)))))
