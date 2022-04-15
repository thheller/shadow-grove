(ns shadow.experiments.grove.dev-support
  (:require
    [goog.functions :as gfn]
    [goog.style :as gs]
    [goog.positioning :as goog-pos]
    [clojure.core.protocols :as cp]
    [shadow.remote.runtime.api :as rapi]
    [shadow.debug :refer (?> ?-> ?->>)]
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.grove.components :as comp]
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.protocols :as sp]
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.local :as local]))

;; sketch of some of the development support might work

;; never used in production anyways
(set! *warn-on-infer* false)

(defonce perf-data-ref (atom {}))

(defn safe-inc [x]
  (if (nil? x)
    1
    (inc x)))

(extend-type comp/ManagedComponent
  cp/Datafiable
  (datafy [this]
    {:component-name (-> this .-config .-component-name)
     :config (.-config this)
     :parent-env (.-parent-env this)
     :component-env (.-component-env this)
     :args (.-args this)
     :rendered-args (.-rendered-args this)
     :events (.-events this)
     :root (.-root this)
     :current-idx (.-current-idx this)
     :hooks (vec (.-hooks this))
     :hook-values
     (->> (array-seq (.-hooks this))
          (map (fn [hook]
                 (if-not hook
                   :uninitialized
                   (gp/hook-value hook))))
          (vec))
     :dirty-from-args (.-dirty-from-args this)
     :dirty-hooks (.-dirty-hooks this)
     :updated-hooks (.-updated-hooks this)
     :needs-render? (.-needs-render? this)
     :suspended? (.-suspended? this)
     :destroyed? (.-destroyed? this)})

  IPrintWithWriter
  (-pr-writer [component writer opts]
    (-pr-writer
      [::comp/ManageComponent
       (symbol (-> component .-config .-component-name))]
      writer
      opts)))

(extend-type gp/ComponentConfig
  cp/Datafiable
  (datafy [this]
    {:component-name (.-component-name this)
     :hooks (.-hooks this)
     :opts (.-opts this)
     :render-deps (.-render-deps this)}))

(extend-type comp/HookConfig
  cp/Datafiable
  (datafy [this]
    {:depends-on (.-depends-on this)
     :affects (.-affects this)}))

(extend-type comp/SimpleVal
  cp/Datafiable
  (datafy [this]
    {:val (.-val this)}))

(extend-type local/QueryHook
  cp/Datafiable
  (datafy [this]
    {:ident (.-ident this)
     :query (.-query this)
     :config (.-config this)
     :query-id (.-query-id this)
     :ready? (.-ready? this)
     :read-count (.-read-count this)
     :read-keys (.-read-keys this)
     :read-result (.-read-result this)}
    )

  IPrintWithWriter
  (-pr-writer [this writer opts]
    (-pr-writer
      [::sg/QueryHook
       (.-ident this)
       (.-query this)]
      writer
      opts)))

#_(extend-type sa/TreeScheduler
    cp/Datafiable
    (datafy [this]
      {:root (.-root this)
       :pending-ref (.-pending-ref this)
       :update-pending? (.-update-pending? this)}))

(extend-type sa/TreeRoot
  cp/Datafiable
  (datafy [this]
    {:container (.-container this)
     :env (.-env this)
     :root (.-root this)}))

(comment
  (extend-type sp/Ident
    cp/Datafiable
    (datafy [this]
      [(.-entity-type this)
       (.-id this)])

    IPrintWithWriter
    (-pr-writer [this writer opts]
      (-write writer "#db-ident ")
      (-pr-writer
        [(.-entity-type this)
         (.-id this)]
        writer
        opts))))

(defn find-owning-component [e]
  (loop [current (.. e -target)]
    (if (and (= "#comment" (.-nodeName current))
             (.-shadow$instance current))
      (.-shadow$instance current)
      (if-some [prev (.-previousSibling current)]
        (recur prev)
        (when-some [parent (.-parentNode current)]
          (recur parent))))))

(defn select-element []
  (let [border-highlight
        (doto (js/document.createElement "div")
          (gs/setStyle
            #js {"border" "1px solid red"
                 "position" "absolute"
                 "pointer-events" "none"
                 "z-index" "1000"
                 "top" "0px"
                 "left" "0px"
                 "width" "0px"
                 "height" "0px"}))

        highlight-ref (atom nil)

        mouse-hook*
        (fn mouse-hook* [e]
          (when-some [component (find-owning-component e)]
            (when-not (identical? component @highlight-ref)
              (reset! highlight-ref component)
              (let [marker-before (p/dom-first component)
                    nodes
                    (loop [current (.-nextSibling marker-before)
                           nodes []]
                      (if (identical? (.-shadow$instance current) component)
                        nodes
                        (recur
                          (.-nextSibling current)
                          (if (not= (.-nodeType current) 1)
                            nodes
                            (conj nodes current)))))]

                (when (seq nodes)
                  (let [start (first nodes)
                        end (last nodes)

                        start-box (gs/getBounds start)
                        end-box (gs/getBounds end)

                        style #js {"top" (str (.-top start-box) "px")
                                   "left" (str (.-left start-box) "px")
                                   "height" (-> (+ (.-top end-box) (.-height end-box))
                                                (- (.-top start-box))
                                                (str "px"))
                                   "width" (str (.-width start-box) "px")}]

                    (gs/setStyle border-highlight style)))))))

        mouse-hook (gfn/throttle mouse-hook* 100)


        overlay
        (doto (js/document.createElement "div")
          (gs/setStyle
            #js {"position" "absolute"
                 "pointer-events" "none"
                 "z-index" "10000"
                 "top" "0px"
                 "left" "0px"
                 "bottom" "0px"
                 "right" "0px"}))

        all-your-clicks
        (fn [e]
          (.preventDefault e)
          (.remove border-highlight)
          (js/document.removeEventListener "mousemove" mouse-hook)
          (when-some [selected @highlight-ref]
            (js/console.log "selected" selected)
            (tap> selected)))]

    (js/document.body.appendChild border-highlight)
    (js/document.addEventListener "mousemove" mouse-hook)
    (js/window.addEventListener "click" all-your-clicks #js {:once true :capture true})))

(defonce keyboard-hook
  (do (js/window.addEventListener
        "keypress"
        (fn [e]
          ;; ctrl+shift+s
          (when (and (= (.-which e) 19) (.-ctrlKey e) (.-shiftKey e))
            (select-element))))
      true))
