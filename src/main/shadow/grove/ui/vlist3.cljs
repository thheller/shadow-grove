(ns shadow.grove.ui.vlist3
  "virtual list as a regular defc. only renders the rows in view (plus overscan)
   and asks a query for just that window of rows.

   the list lives in a handle, which the using component owns via ref and renders directly:

   (defc ui-rows [^:stable oid active?]
     (ref items
       (vlist3/prepare {:item-height 22 :item-fn ui-row :select-event {:e ::row-selected!}}
         db/?rows oid))
     (hook (vlist3/set-opts! items {:tab-index (if active? 0 -1)}))
     (render items))

   options that change go through hooks, which only re-run when what they use changes.
   so (hook (vlist3/set-item-fn! items (fn [item opts] ...))) gives a closure over locals
   that is only replaced when one of those locals changes.

   for simple cases (vlist3/render opts query-fn & query-args) does the same in one call.

   the query is called as (sg/query db/?rows oid {:offset n :num m}) and must return
   {:item-count total :offset n :slice [...]}, optionally :more? true when more rows exist
   beyond :item-count (lazy seqs). it may sg/suspend! while rows are missing, the list
   keeps showing the previous rows until they arrive.

   replaces shadow.grove.ui.vlist2, which is left as is."
  (:require
    [shadow.arborist.protocols :as ap]
    [shadow.grove :as sg :refer (defc <<)]
    [shadow.grove.devtools.protocols :as devp]
    [shadow.grove.keyboard :as keyboard]))

(declare ui-vlist)

;; keys set via prepare/set-opts!, everything else in the state is managed by the list
(def opt-keys
  #{:item-height :item-fn :key-fn :select-event :tab-index
    :box-class :class :content-wrap :more-fn :overscan :initial-num})

(def default-opts
  {:tab-index -1 :overscan 10 :initial-num 20})

(defn check-opts [opts]
  (when ^boolean js/goog.DEBUG
    (when-some [unknown (seq (remove opt-keys (keys opts)))]
      (js/console.warn "vlist3: unknown opts" (vec unknown))))
  opts)

;; the managed node for a rendered handle. the list itself is the ui-vlist component,
;; this only makes the handle accept itself on updates, so re-rendering the same handle
;; (e.g. a parent fragment updating something else) doesn't replace the list
(deftype VListNode [handle ^not-native inner]
  ap/IManaged
  (supports? [this next]
    (identical? handle next))
  (dom-sync! [this next]
    ;; same handle, changes arrive through it
    nil)
  (dom-insert [this parent anchor]
    (ap/dom-insert inner parent anchor))
  (dom-first [this]
    (ap/dom-first inner))
  (dom-entered! [this]
    (ap/dom-entered! inner))
  (destroy! [this dom-remove?]
    (ap/destroy! inner dom-remove?)))

(deftype VList
  [state
   ;; set while mounted, used by focus-row!
   ^:mutable container-el
   ;; kept so a remounted list continues where it was
   ^:mutable scroll-top
   ;; last item count rendered, used to clamp focus
   ^:mutable item-count]

  IDeref
  (-deref [this]
    @state)

  ;; so sg/watch works on the handle
  IWatchable
  (-notify-watches [this old new]
    (-notify-watches state old new))
  (-add-watch [this key f]
    (-add-watch state key f)
    this)
  (-remove-watch [this key]
    (-remove-watch state key)
    this)

  ap/IConstruct
  (as-managed [this env]
    (VListNode. this (ap/as-managed (ui-vlist this) env))))

(defn prepare
  "(prepare), (prepare opts) or (prepare opts query-fn & query-args)

   creates a list handle, meant for (ref name (vlist3/prepare ...)). render the handle
   itself, (render items) or anywhere in markup, but only in one place.

   opts and query may also be set later via set-opts!/set-query!, in the ref init
   (doto (prepare) (set-query! ...)) or from hooks. hooks run before render, so the
   list has them by the time it first renders.

   opts
   :item-height   required, fixed row height in px
   :item-fn       (fn [item {:keys [idx focus key]}] renderable), a defc works too
   :key-fn        renders rows with keyed-seq instead of simple-seq
   :select-event  event map dispatched up on Enter with :idx and :item added
   :tab-index     default -1 (focusable by click or script, not by tab)
   :box-class     class for the box holding the rows (e.g. a grid)
   :class         class for the scroll container
   :content-wrap  (fn [rows] renderable)
   :more-fn       (fn [] renderable) for the row shown while :more? is true
   :overscan      rows rendered beyond the visible ones, default 10
   :initial-num   rows queried before the container was measured, default 20"
  ([]
   (prepare {}))
  ([opts & query]
   (let [[query-fn & query-args] query]
     (VList.
       (atom (merge default-opts
                    (check-opts opts)
                    {:query-fn query-fn
                     :query-args (vec query-args)
                     :offset 0
                     :num 0
                     :focus 0
                     :focused? false}))
       nil
       nil
       0))))

(defn set-opts!
  "merges opts into the handle, meant to be called from a hook"
  [^VList handle opts]
  (swap! (.-state handle) merge (check-opts opts))
  nil)

(defn set-item-fn!
  "meant for (hook (set-item-fn! handle (fn [item opts] ...))), which only re-runs
   and replaces the fn when something it closes over changes"
  [^VList handle item-fn]
  (swap! (.-state handle) assoc :item-fn item-fn)
  nil)

(defn set-query!
  [^VList handle query-fn & query-args]
  (swap! (.-state handle) assoc :query-fn query-fn :query-args (vec query-args))
  nil)

(defn update-window
  "returns state unchanged while its window still covers the rows in view plus half the overscan,
   otherwise a new window around the rows in view. keeps query churn down while scrolling."
  [{:keys [offset num] :as state} scroll-top height item-height overscan]
  (let [first-row (js/Math.floor (/ scroll-top item-height))
        ;; inc for the partially visible row at the bottom
        visible (inc (js/Math.ceil (/ height item-height)))
        margin (js/Math.floor (/ overscan 2))]
    (if (and (pos? num)
             (>= (js/Math.max 0 (- first-row margin)) offset)
             (<= (+ first-row visible margin) (+ offset num)))
      state
      (let [start (js/Math.max 0 (- first-row overscan))]
        (assoc state
          :offset start
          :num (- (+ first-row visible overscan) start))))))

(defn measure! [^VList handle ^js el]
  (let [{:keys [item-height overscan]} @handle
        height (.-clientHeight el)
        scroll-top (.-scrollTop el)]
    (when (and ^boolean js/goog.DEBUG (zero? height))
      (js/console.warn "vlist3 container measured zero height, it needs a parent with bounded height" el))
    (set! (.-scroll-top handle) scroll-top)
    ;; without item-height there is nothing to compute, render warns about that
    (when (pos? item-height)
      (swap! (.-state handle) update-window scroll-top height item-height overscan))))

(defn next-frame [callback]
  (if (exists? js/requestAnimationFrame)
    (js/requestAnimationFrame callback)
    (js/setTimeout callback 16)))

(defn observe!
  "keeps the window in sync with scroll position and container size. returns cleanup fn."
  [^VList handle ^js el]
  (let [active? (volatile! true)
        pending? (volatile! false)

        ;; at most one measure per frame, instead of debouncing which waits for scrolling to stop
        on-scroll
        (fn [e]
          (when-not @pending?
            (vreset! pending? true)
            (next-frame
              (fn []
                (vreset! pending? false)
                (when @active?
                  (measure! handle el))))))

        resize-observer
        (when (exists? js/ResizeObserver)
          (doto (js/ResizeObserver.
                  (fn [entries]
                    (when @active?
                      (measure! handle el))))
            (.observe el)))]

    (set! (.-container-el handle) el)

    ;; a remounted handle (e.g. after switching views) continues where it was
    (when-some [top (.-scroll-top handle)]
      (set! el -scrollTop top))

    ;; dom focus doesn't survive a remount, but does survive this effect re-running
    (swap! (.-state handle) assoc :focused? (identical? el (.. el -ownerDocument -activeElement)))

    (measure! handle el)
    (.addEventListener el "scroll" on-scroll #js {:passive true})

    (fn cleanup []
      (vreset! active? false)
      (.removeEventListener el "scroll" on-scroll)
      (when resize-observer
        (.disconnect resize-observer))
      (when (identical? el (.-container-el handle))
        (set! (.-container-el handle) nil)))))

(defn focus-set!
  "moves keyboard focus to idx, scrolling it into view if needed"
  [^VList handle ^js el idx]
  (let [{:keys [item-height overscan]} @handle
        item-count (.-item-count handle)]
    (when (pos? item-count)
      (let [idx (-> idx (js/Math.max 0) (js/Math.min (dec item-count)))
            top (* idx item-height)
            bottom (+ top item-height)
            height (.-clientHeight el)
            scroll-top (.-scrollTop el)]

        (cond
          (< top scroll-top)
          (set! el -scrollTop top)

          (> bottom (+ scroll-top height))
          (set! el -scrollTop (- bottom height)))

        (set! (.-scroll-top handle) (.-scrollTop el))

        ;; update the window right away, the scroll event that follows then finds nothing to do
        (swap! (.-state handle)
          (fn [state]
            (-> state
                (assoc :focus idx)
                (update-window (.-scrollTop el) height item-height overscan))))))))

(defn focus-move! [^VList handle el delta]
  ;; using the state, not focus from the last render. two key presses may arrive before it
  (focus-set! handle el (+ (:focus @handle) delta)))

(defn focus-row!
  "moves keyboard focus to row idx, scrolling it into view when mounted.
   doesn't move DOM focus to the list."
  [^VList handle idx]
  (if-some [el (.-container-el handle)]
    (focus-set! handle el idx)
    (swap! (.-state handle) assoc :focus (js/Math.max 0 idx))))

(defn page-size [^js el item-height]
  (js/Math.max 1 (js/Math.floor (/ (.-clientHeight el) item-height))))

(defn ev-stop [^js e]
  (.stopPropagation e)
  (.preventDefault e))

(defc ui-vlist [^:stable ^VList handle]
  (ref container)

  (bind {:keys [query-fn query-args initial-num offset num
                item-height item-fn key-fn tab-index box-class class content-wrap more-fn
                overscan focus focused?]}
    (sg/watch handle))

  (bind result
    (when query-fn
      ;; num is 0 until mounted and measured, query a reasonable guess so the first render has rows
      (apply sg/query query-fn
        (conj query-args {:offset offset :num (if (pos? num) num initial-num)}))))

  (bind total
    (or (:item-count result) 0))

  (bind slice
    (or (:slice result) []))

  (bind slice-offset
    (or (:offset result) offset))

  (bind more?
    (true? (:more? result)))

  ;; after the query, so it only runs once there is something rendered to measure
  ;; re-observes when the measurements it depends on change
  (effect [item-height overscan] [env]
    (observe! handle @container))

  (effect :auto [env]
    (set! (.-item-count handle) total))

  (event ::focus! [env ev e]
    (swap! (.-state handle) assoc :focused? true))

  (event ::blur! [env ev e]
    (swap! (.-state handle) assoc :focused? false))

  (event ::keyboard/arrowup [env ev e]
    (ev-stop e)
    (focus-move! handle @container -1))

  (event ::keyboard/arrowdown [env ev e]
    (ev-stop e)
    (focus-move! handle @container 1))

  (event ::keyboard/pageup [env ev e]
    (ev-stop e)
    (focus-move! handle @container (- (page-size @container item-height))))

  (event ::keyboard/pagedown [env ev e]
    (ev-stop e)
    (focus-move! handle @container (page-size @container item-height)))

  (event ::keyboard/home [env ev e]
    (ev-stop e)
    (focus-set! handle @container 0))

  (event ::keyboard/end [env ev e]
    (ev-stop e)
    (focus-set! handle @container (dec total)))

  (event ::keyboard/enter [env ev e]
    (when-some [select-event (:select-event @handle)]
      (let [idx (:focus @handle)]
        ;; focus may be outside the rendered rows, nothing to select then
        (when-some [item (nth slice (- idx slice-offset) nil)]
          (ev-stop e)
          (sg/dispatch-up! env (assoc select-event :idx idx :item item))))))

  (render
    (when (and ^boolean js/goog.DEBUG (not (pos? item-height)))
      (js/console.warn "vlist3: :item-height must be set before the list renders" handle))

    (let [rows
          (+ total (if more? 1 0))

          content
          (if key-fn
            (sg/keyed-seq slice key-fn
              (fn [item i key]
                (let [idx (+ slice-offset i)]
                  (item-fn item {:idx idx :key key :focus (and focused? (= focus idx))}))))
            (sg/simple-seq slice
              (fn [item i]
                (let [idx (+ slice-offset i)]
                  (item-fn item {:idx idx :focus (and focused? (= focus idx))})))))]

      (<< [:div {:dom/ref container
                 ::keyboard/listen true
                 :tab-index tab-index
                 :class class
                 :on-focus ::focus!
                 :on-blur ::blur!
                 :style/outline "none"
                 :style/overflow-y "auto"
                 :style/width "100%"
                 :style/height "100%"}
           ;; full height, so the scrollbar matches all rows
           [:div {:style/position "relative"
                  :style/width "100%"
                  :style/height (str (* rows item-height) "px")}
            ;; the rendered rows, moved to where they belong
            [:div {:class box-class
                   :style/position "absolute"
                   :style/width "100%"
                   :style/top (str (* slice-offset item-height) "px")}
             (if content-wrap
               (content-wrap content)
               content)]
            (when more?
              (<< [:div {:style/position "absolute"
                         :style/width "100%"
                         :style/height (str item-height "px")
                         :style/top (str (* total item-height) "px")}
                   (if more-fn (more-fn) "...")]))]]))))

;; one-call variant, owns the handle and forwards its args to it
(defc ui-vlist-wrapper [opts query-fn query-args]
  (ref handle
    (prepare))

  (hook
    ;; replace, not merge. opts removed by the caller must go away
    (swap! (.-state handle)
      (fn [state]
        (merge (apply dissoc state opt-keys) default-opts (check-opts opts)))))

  (hook
    (apply set-query! handle query-fn query-args))

  (render handle))

(defn render
  "(render opts query-fn & query-args)

   one-call variant of (ref items (prepare opts query-fn & query-args)) + (render items).
   same opts as prepare. pass a top-level :item-fn, not a closure created in render,
   otherwise every parent render replaces it and re-renders the rows."
  [opts query-fn & query-args]
  (ui-vlist-wrapper opts query-fn (vec query-args)))

(when ^boolean js/goog.DEBUG
  (extend-protocol devp/ISnapshot
    VListNode
    (snapshot [this ctx]
      {:type `VListNode
       :children [(devp/snapshot (.-inner this) ctx)]})))
