(ns shadow.grove.vlist3-test
  (:require
    ["jsdom" :refer (JSDOM)]
    [cljs.test :as ct :refer (deftest is async)]
    [shadow.grove :as sg :refer (defc <<)]
    [shadow.grove.ui.vlist3 :as vlist]))

;; jsdom has no layout. every element reports the given clientHeight,
;; scrollTop is stored per element so code setting it can be checked
(defn make-env [rt-id height items]
  (let [dom (JSDOM. "<!DOCTYPE html>")
        win (.-window dom)
        doc (.-document win)
        rt-ref (sg/get-runtime rt-id)
        scroll-tops (js/WeakMap.)]

    (set! js/global -document doc)
    (set! js/global -requestAnimationFrame (fn [f] (js/setTimeout f 0)))

    (js/Object.defineProperty (.. win -HTMLElement -prototype) "clientHeight"
      #js {:configurable true :get (fn [] height)})

    (js/Object.defineProperty (.. win -HTMLElement -prototype) "scrollTop"
      #js {:configurable true
           :get (fn [] (this-as el (or (.get scroll-tops el) 0)))
           :set (fn [v] (this-as el (.set scroll-tops el v)))})

    (sg/add-kv-table rt-ref ::data {} (if items {:items items} {}))

    {:root (.-body doc)
     :win win
     :rt-ref rt-ref}))

(defn wait []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 10))))

(defn run-steps [done steps]
  (-> (reduce
        (fn [p step]
          (.then p (fn [] (step) (wait))))
        (js/Promise.resolve nil)
        steps)
      (.catch (fn [e]
                (js/console.error e)
                (is (nil? e) "step failed")))
      (.then done)))

(defn take-log! [log-ref]
  (let [log @log-ref]
    (reset! log-ref [])
    log))

(defn ?rows [env log-ref {:keys [offset num]}]
  ;; logging in a query is only ok in tests, shows which windows were queried
  (swap! log-ref conj [:query offset num])
  (if-some [items (get-in env [::data :items])]
    (let [total (count items)]
      {:item-count total
       :offset offset
       :more? (get-in env [::data :more?])
       :slice (subvec items (min offset total) (min (+ offset num) total))})
    (sg/suspend! {:item-count 0})))

(defn row [item {:keys [idx focus]}]
  (<< [:div {:class (when focus "focus")} (str idx ":" item)]))

;; the one-call variant
(defc ui-list [log-ref]
  (event ::selected! [env {:keys [idx item]} e]
    (swap! log-ref conj [:selected idx item]))

  (render
    (vlist/render
      {:item-height 10
       :item-fn row
       :overscan 2
       :tab-index 0
       :select-event {:e ::selected!}}
      ?rows log-ref)))

(defn container ^js [^js root]
  (.querySelector root "[tabindex]"))

(defn inner ^js [root]
  (.-firstElementChild (container root)))

(defn box ^js [root]
  (.-firstElementChild (inner root)))

(defn row-texts [root]
  (mapv #(.-textContent %) (array-seq (.-children (box root)))))

(defn focused-texts [root]
  (mapv #(.-textContent %) (array-seq (.querySelectorAll (box root) ".focus"))))

(defn scroll-to! [^js win ^js el top]
  (set! el -scrollTop top)
  (.dispatchEvent el (new (.-Event win) "scroll")))

(defn key-event [^js win type key key-code]
  (let [ev (new (.-KeyboardEvent win) type #js {:key key :bubbles true :cancelable true})]
    ;; goog KeyHandler still looks at keyCode/charCode
    (js/Object.defineProperty ev "keyCode" #js {:get (fn [] key-code)})
    (when (= "keypress" type)
      (js/Object.defineProperty ev "charCode" #js {:get (fn [] key-code)}))
    ev))

(defn press! [^js win ^js el key key-code]
  (.dispatchEvent el (key-event win "keydown" key key-code))
  ;; goog KeyHandler reports keys that produce a keypress (e.g. Enter) from keypress, not keydown
  (when (= "Enter" key)
    (.dispatchEvent el (key-event win "keypress" key key-code))))

(defn focus! [^js win ^js el]
  (.dispatchEvent el (new (.-FocusEvent win) "focus")))

(deftest test-window-follows-scroll
  (let [{:keys [root win rt-ref]} (make-env ::window 100 (vec (range 100)))
        log-ref (atom [])
        old-container (volatile! nil)]

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-list log-ref)))

         ;; initial guess of 20 rows, then the measured window: 11 visible + 2 overscan
         (fn []
           (is (= [[:query 0 20] [:query 0 13]] (take-log! log-ref)))
           (is (= 13 (count (row-texts root))))
           (is (= "0:0" (first (row-texts root))))
           (is (= "1000px" (.. (inner root) -style -height)))
           (is (= "0px" (.. (box root) -style -top)))

           (scroll-to! win (container root) 500))

         ;; row 50 is first visible, window starts 2 rows above it
         (fn []
           (is (= [[:query 48 15]] (take-log! log-ref)))
           (is (= "48:48" (first (row-texts root))))
           (is (= 15 (count (row-texts root))))
           (is (= "480px" (.. (box root) -style -top)))

           ;; one row further is still covered by the window
           (scroll-to! win (container root) 510))

         (fn []
           (is (= [] (take-log! log-ref))))

         ;; unmounted lists stop listening
         (fn []
           (vreset! old-container (container root))
           (sg/render rt-ref root "done"))

         (fn []
           (is (= "done" (.-textContent root)))
           (scroll-to! win @old-container 800))

         (fn []
           (is (= [] (take-log! log-ref))))]))))

(deftest test-keyboard-focus-and-select
  (let [{:keys [root win rt-ref]} (make-env ::keyboard 100 (vec (range 100)))
        log-ref (atom [])]

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-list log-ref)))

         (fn []
           (take-log! log-ref)
           (is (= [] (focused-texts root)) "no focus row while not focused")
           (focus! win (container root)))

         (fn []
           (is (= ["0:0"] (focused-texts root)))
           (press! win (container root) "ArrowDown" 40))

         (fn []
           (is (= ["1:1"] (focused-texts root)))
           ;; end scrolls the last row into view, the window follows
           (press! win (container root) "End" 35))

         (fn []
           (is (= 900 (.-scrollTop (container root))))
           (is (= [[:query 88 15]] (take-log! log-ref)))
           (is (= ["99:99"] (focused-texts root)))
           (press! win (container root) "Enter" 13))

         (fn []
           (is (= [[:selected 99 99]] (take-log! log-ref))))]))))

(deftest test-data-changes-and-more
  (let [{:keys [root win rt-ref]} (make-env ::data-changes 100 (vec (range 5)))
        log-ref (atom [])]

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-list log-ref)))

         (fn []
           (take-log! log-ref)
           (is (= ["0:0" "1:1" "2:2" "3:3" "4:4"] (row-texts root)))
           (is (= "50px" (.. (inner root) -style -height)))

           ;; same args from the parent, nothing to query
           (sg/render rt-ref root (ui-list log-ref)))

         (fn []
           (is (= [] (take-log! log-ref)))

           (sg/run-tx! rt-ref
             (fn [env]
               (-> env
                   (assoc-in [::data :items] (vec (range 7)))
                   (assoc-in [::data :more?] true)))))

         ;; more? adds a row after the last item
         (fn []
           (is (= [[:query 0 13]] (take-log! log-ref)))
           (is (= 7 (count (row-texts root))))
           (is (= "80px" (.. (inner root) -style -height)))
           (let [more-el (.-lastElementChild (inner root))]
             (is (= "..." (.-textContent more-el)))
             (is (= "70px" (.. more-el -style -top)))))]))))

(deftest test-suspends-until-rows-arrive
  (let [{:keys [root win rt-ref]} (make-env ::suspend 100 nil)
        log-ref (atom [])]

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-list log-ref)))

         (fn []
           (is (= [[:query 0 20]] (take-log! log-ref)))
           (is (nil? (container root)) "nothing rendered while suspended")

           (sg/run-tx! rt-ref
             (fn [env]
               (assoc-in env [::data :items] (vec (range 3))))))

         (fn []
           (is (= [[:query 0 20] [:query 0 13]] (take-log! log-ref)))
           (is (= ["0:0" "1:1" "2:2"] (row-texts root))))]))))

;; the handle variant, owned by the component using the list

(defc ui-handle-host [^:stable log-ref ^:stable holder prefix active? show?]
  (ref items
    (doto (vlist/prepare {:item-height 10 :overscan 2 :select-event {:e ::selected!}}
            ?rows log-ref)
      (->> (vreset! holder))))

  ;; closure over prefix, only replaced when prefix changes
  (hook
    (vlist/set-item-fn! items
      (fn [item {:keys [idx focus]}]
        (<< [:div {:class (when focus "focus")} (str prefix idx ":" item)]))))

  (hook
    (vlist/set-opts! items {:tab-index (if active? 0 -1)}))

  (event ::selected! [env {:keys [idx item]} e]
    (swap! log-ref conj [:selected idx item]))

  (render
    (<< [:div {:class (when active? "active")}
         (when show?
           items)])))

(deftest test-handle
  (let [{:keys [root win rt-ref]} (make-env ::handle 100 (vec (range 100)))
        log-ref (atom [])
        holder (volatile! nil)
        el-before (volatile! nil)]

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-handle-host log-ref holder "a" false true)))

         (fn []
           (is (= [[:query 0 20] [:query 0 13]] (take-log! log-ref)))
           (is (= "a0:0" (first (row-texts root))))
           (is (= -1 (.-tabIndex (container root))))
           (vreset! el-before (container root))

           ;; prefix only reaches the item-fn hook
           (sg/render rt-ref root (ui-handle-host log-ref holder "b" false true)))

         (fn []
           (is (= "b0:0" (first (row-texts root))))
           (is (= [] (take-log! log-ref)) "new item-fn doesn't re-query")
           (is (identical? @el-before (container root)))

           ;; host renders, its fragment updates around the handle, same list stays
           (sg/render rt-ref root (ui-handle-host log-ref holder "b" true true)))

         (fn []
           (is (= 0 (.-tabIndex (container root))))
           (is (= "active" (.. root -firstElementChild -className)))
           (is (identical? @el-before (container root)))
           (is (= [] (take-log! log-ref)))

           ;; the host may move focus from outside
           (vlist/focus-row! @holder 50))

         (fn []
           (is (= 410 (.-scrollTop (container root))))
           (is (= 50 (:focus @@holder)))
           (is (= [[:query 39 15]] (take-log! log-ref)))

           (sg/render rt-ref root (ui-handle-host log-ref holder "b" true false)))

         ;; state lives in the handle, so a remount continues where it was
         (fn []
           (is (nil? (container root)))
           (sg/render rt-ref root (ui-handle-host log-ref holder "b" true true)))

         (fn []
           (is (not (identical? @el-before (container root))))
           (is (= 410 (.-scrollTop (container root))))
           (is (= [[:query 39 15]] (take-log! log-ref)))
           (is (= "b39:39" (first (row-texts root))))

           (focus! win (container root)))

         (fn []
           (is (= ["b50:50"] (focused-texts root)))
           (press! win (container root) "Enter" 13))

         (fn []
           (is (= [[:selected 50 50]] (take-log! log-ref))))]))))

;; config arriving late, via ref init or hooks, still there before the first render

(defc ui-late-hooks [^:stable log-ref active?]
  (ref items
    (vlist/prepare))

  (hook
    (vlist/set-query! items ?rows log-ref))

  (hook
    (vlist/set-opts! items
      {:item-height 10
       :overscan 2
       :item-fn row
       :tab-index (if active? 0 -1)}))

  (render items))

(defc ui-late-doto [^:stable log-ref active?]
  (ref items
    (doto (vlist/prepare)
      (vlist/set-query! ?rows log-ref)))

  (hook
    (vlist/set-opts! items
      {:item-height 10
       :overscan 2
       :item-fn row
       :tab-index (if active? 0 -1)}))

  (render items))

(defn late-config-steps [root rt-ref log-ref host]
  (let [el-before (volatile! nil)]
    [(fn []
       (sg/render rt-ref root (host log-ref false)))

     (fn []
       ;; first query already had the query and opts, no extra round
       (is (= [[:query 0 20] [:query 0 13]] (take-log! log-ref)))
       (is (= "0:0" (first (row-texts root))))
       (is (= -1 (.-tabIndex (container root))))
       (vreset! el-before (container root))
       (sg/render rt-ref root (host log-ref true)))

     (fn []
       (is (= 0 (.-tabIndex (container root))))
       (is (identical? @el-before (container root)))
       (is (= [] (take-log! log-ref)) "opts change doesn't re-query"))]))

(deftest test-late-config-hooks
  (let [{:keys [root rt-ref]} (make-env ::late-hooks 100 (vec (range 100)))
        log-ref (atom [])]
    (async done
      (run-steps done (late-config-steps root rt-ref log-ref ui-late-hooks)))))

(deftest test-late-config-doto
  (let [{:keys [root rt-ref]} (make-env ::late-doto 100 (vec (range 100)))
        log-ref (atom [])]
    (async done
      (run-steps done (late-config-steps root rt-ref log-ref ui-late-doto)))))
