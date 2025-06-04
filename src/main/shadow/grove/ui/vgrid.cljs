(ns shadow.grove.ui.vgrid
  (:refer-clojure :exclude #{use})
  (:require
    [shadow.arborist.attributes :as a]
    [shadow.arborist.collections :as coll]
    [shadow.arborist.common :as common]
    [shadow.cljs.modern :refer (defclass)]
    [shadow.arborist.protocols :as ap]
    [goog.functions :as gfn]
    [goog.style :as gs]

    [shadow.grove.components :as comp]
    [shadow.grove.keyboard :as keyboard]
    [shadow.grove.protocols :as gp]
    [shadow.grove.runtime :as rt])
  (:import
    [goog.events KeyHandler]))

(defn ev-stop [^js e]
  (.stopPropagation e)
  (.preventDefault e))

(defrecord Rect [top left width height]
  Object
  ;; gets called a lot during scrolling, so perf matters. even kw lookups.
  (contains? [this other]
    (and (<= left (.-left other))
         (>= (+ left width) (+ (.-left other) (.-width other)))
         (<= top (.-top other))
         (>= (+ top width) (+ (.-top other) (.-height other))))))


(defclass GridState
  (field ref)
  (field config)
  (field value)
  (field grid)

  (constructor [this ref config]
    (set! this -ref ref)
    (set! this -config config))

  Object
  (init! [this]
    ;; FIXME: start this out with some values pre-calculated from config
    ;; but without knowing actual size this won't be very useful and will result in a second render anyway
    ;; maybe use a map instead to inform whatever loads the data that this is initial render
    ;; and returning :total-rows is kinda enough?
    (set! value (with-meta (Rect. 0 0 0 0) {::state this})))

  (set-grid! [this new-grid]
    (when grid
      (throw (js/Error. "grid is already mounted?")))

    ;; FIXME: have grid notify this when unmounting?
    (set! grid new-grid))

  (sync! [this next-config]
    ;; must verify that columns are still same
    ;; but they may contain functions, so they never are
    ;; forcing for now via identical?

    (js/console.log "state sync" this next-config)
    )

  (get-value! [this] value)

  (trigger-update! [this new-rect]
    (set! value (with-meta new-rect {::state this}))
    (gp/provide-new-value! ref value)))

(declare VirtualSeed)

(defclass VirtualGrid
  (field env)
  (field ^GridState grid-state)

  (field column-widths)

  (field ^js rs) ;; resize observer
  (field ^js container-el) ;; the other scroll container
  (field ^js inner-el) ;; the inner container providing the height
  (field dom-entered? false)

  (field rect-visible)

  (constructor [this env ^GridState grid-state]
    (set! this -env env)
    (set! this -grid-state grid-state)

    (.set-grid! grid-state this)

    ;; FIXME: this is relying on containing element being a fixed size
    ;; otherwise height 100% ends up as 0
    ;; ok with that for now and better than user having to provide a fixed number
    ;; plays better with flexbox and stuff
    (set! container-el (js/document.createElement "div"))
    (gs/setStyle container-el
      #js {"outline" "none"
           "overflow" "auto"
           "width" "100%"
           "height" "100%"})

    ;; the element that gets larger than visible area and contains all rows/cols
    ;; must be the size of theoretical maximum size that would show everything rows+cols
    ;; must be fixed size because otherwise things get weird due to absolute positioned elements
    (set! inner-el (js/document.createElement "div"))
    (gs/setStyle inner-el
      #js {"position" "relative"
           "height" "2000px"
           "width" "2500px"})
    (.appendChild container-el inner-el)

    (set! column-widths (-> (js/Array. 500) (.fill 50)))

    (dotimes [x 50]
      (let [row (js/document.createElement "div")]
        (gs/setStyle row
          #js {"position" "absolute"
               "border-bottom" "1px solid #eee"
               "transform" (str "translateY(" (js/CSS.px (* x 40)) ")")
               "width" "100%"
               "height" "40px"})

        (.forEach column-widths
          (fn [w idx]

            (let [el (js/document.createElement "div")]
              (set! el -textContent (str "#" x "." idx))

              (gs/setStyle el
                #js {"width" (js/CSS.px w)
                     "left" (js/CSS.px (* idx w))
                     "position" "absolute"
                     "display" "block"})
              (.appendChild row el))))


        (.appendChild inner-el row)))

    (set! rs (js/ResizeObserver.
               (fn [entries]
                 ;; not using actual entries, since we also need scroll values
                 ;; so just getting things from el directly
                 (.handle-resize! this))))

    (.addEventListener container-el "scroll" #(.handle-scroll! this %)))

  ap/IManaged
  (supports? [this ^VirtualSeed next]
    (and (instance? VirtualSeed next)
         (identical? grid-state (.-grid-state next))))

  (dom-sync! [this ^VirtualSeed next]
    (js/console.log "sync grid" this next)
    )

  (dom-insert [this parent anchor]
    (.insertBefore parent container-el anchor))

  (dom-first [this]
    container-el)

  (dom-entered! [this]
    (set! dom-entered? true)

    ;; this triggers the rs callback in microtask
    (.observe rs container-el))

  (destroy! [this ^boolean dom-remove?]
    (.disconnect rs container-el)

    (when dom-remove?
      (.remove container-el)))

  Object
  (init! [this window data opts])

  (measure! [this]
    (let [container-height
          (.-clientHeight container-el)

          container-width
          (.-clientWidth container-el)]

      (when (or (zero? container-height)
                (zero? container-width))
        (js/console.error "vgrid container measured zero!" container-el this)
        (throw (js/Error. "can't render grid with unknown size")))

      (let [scroll-top
            (.-scrollTop container-el)

            scroll-left
            (.-scrollLeft container-el)

            row-height 40
            total-rows 50
            oversize-x 200
            oversize-y 120

            min-idx
            (js/Math.floor (/ scroll-top row-height))

            ref-left (- scroll-left oversize-x)

            ;; all visible columns + oversize so that things getting scrolled in had a chance to render before actually visible

            col-start
            (loop [idx 0
                   w 0]

              (let [w (+ w (aget column-widths idx))]
                (cond
                  (> w ref-left) idx
                  (= idx (dec (alength column-widths))) idx
                  :else
                  (recur (inc idx) w))))

            ref-right (+ scroll-left container-width oversize-x)

            col-end
            (loop [idx col-start
                   w 0]

              (let [w (+ w (aget column-widths idx))]
                (cond
                  (> w ref-right) idx
                  (= idx (dec (alength column-widths))) idx
                  :else
                  (recur (inc idx) w))))

            row-start
            (js/Math.max 0 (- min-idx (js/Math.ceil (/ oversize-y row-height))))

            max-items ;; visible
            (js/Math.ceil (/ (+ container-height oversize-y) row-height))

            row-end
            (js/Math.min (dec total-rows) (+ min-idx max-items))

            w (- col-end col-start)
            h (- row-end row-start)]

        (Rect. col-start row-start w h))))

  (handle-scroll! [this e]
    (let [^Rect owin rect-visible
          ^Rect nwin (.measure! this)]

      (set! rect-visible nwin)

      (when (or (nil? owin)
                (not (or (= owin nwin) (.contains? owin nwin))))

        ;; (js/console.log "scroll update")
        (.trigger-update! grid-state nwin)
        ))

    js/undefined)

  (handle-resize! [this]
    (let [^Rect owin rect-visible
          ^Rect nwin (.measure! this)]

      (set! rect-visible nwin)

      (when (or (nil? owin)
                ;; if resized smaller we don't really need to update
                (not (or (= owin nwin) (.contains? owin nwin))))

        ;; (js/console.log "resize update")
        (.trigger-update! grid-state nwin)
        ))

    js/undefined))


(deftype VirtualSeed [grid-state state window opts]
  ap/IConstruct
  (as-managed [this env]
    (doto (VirtualGrid. env grid-state)
      (.init! state window opts))))

(defn use [config]
  (let [ref (rt/claim-slot! ::use)]
    (if-some [^GridState instance (::instance @ref)]
      (do (.sync! instance config)
          (.get-value! instance))
      (let [instance (GridState. ref config)]
        (swap! ref assoc ::instance instance ::i 0)
        (.init! instance)
        (.get-value! instance)))))


(defn render [grid-data window opts]
  (let [grid-state (::state (meta grid-data))]
    (when-not grid-state
      (throw (js/Error. "expected to get result of (vgrid/use config) from bind?")))

    (VirtualSeed. grid-state grid-data window opts)))

(comment

  (defc ui-thing [id]
    (render
      (<< [:div (vgrid/render my-grid {:id id})])))

  (def columns
    [{:key :id
      :width 400
      :resize false
      :render-fn (fn [row opts]
                   (:id row))}
     {:key :name
      :render-fn str/upper-case}
     {:key :price
      :render-fn format-number}])

  (defc ui-thing [id]
    (bind grid
      (vgrid/use
        {:id [::my-grid id]
         :row-height 40
         :min-rows 25
         :min-cols 4}
        ;; changing columns is very pricey but must be possible
        columns))

    (bind grid-data
      (sg/query ?only-the-window grid))

    (render
      (<< [:div (vgrid/render grid grid-data {:id id})])))
  )
