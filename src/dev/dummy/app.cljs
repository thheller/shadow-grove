(ns dummy.app
  (:require
    [shadow.grove :as sg :refer (<< defc css)]
    [shadow.grove.css-fx :as fx]
    [shadow.grove.local :as local-eng]
    [shadow.grove.runtime :as rt]
    [shadow.cljs.modern :refer (defclass)]
    [shadow.arborist.protocols :as ap]
    [shadow.grove.protocols :as gp]
    [shadow.arborist.attributes :as attr]))

(def $fade
  (css
    ["&-enter"
     {:opacity "0"
      :transform "scale(0.9)"}]
    ["&-enter-active"
     {:opacity "1"
      :transform "translateX(0)"
      :transition "opacity 300ms, transform 300ms"}]
    ["&-exit"
     {:opacity "1"}]
    ["&-exit-active"
     {:opacity "0"
      :transform "scale(0.9)"
      :transition "opacity 300ms, transform 300ms"}]))

(defc ui-dialog-css-transition []
  (event ::out! [env ev e origin]
    (fx/trigger-out! origin
      (fn []
        (sg/dispatch-up! env {:e ::close!})
        )))

  (render
    (sg/portal
      (fx/transition-group
        {:class $fade :timeout 300}
        (<< [:div {:on-click ::out!
                   :class (css :fixed :inset-0 :bg-red-700)}
             "Hello World, click me"])))))

(defclass AnimationHook
  (field opts)
  (field keyframes)
  (field timing)
  (field elements)
  (field component-handle)
  (field first-render? true)

  (constructor [this new-opts new-keyframes new-timing]
    (set! elements [])
    (set! opts new-opts)
    (set! keyframes new-keyframes)
    (set! timing new-timing))

  gp/IBuildHook
  (hook-build [this new-component-handle]
    (set! component-handle new-component-handle)
    this)

  gp/IHookDomEffect
  (hook-did-update! [this did-render?]
    (when (and did-render? first-render?)
      (set! first-render? false)

      (when (:play-on-enter opts)
        (dotimes [x (count elements)]
          (let [el (nth elements x)]
            (.animate
              el
              (clj->js keyframes)
              (clj->js (assoc timing
                         :delay
                         (+ (:delay opts 0) (* (:stagger opts 0) x))))))))))

  gp/IHook
  (hook-init! [this])
  (hook-ready? [this] true)
  (hook-value [this] this)
  ;; true-ish return if component needs further updating
  (hook-deps-update! [this ^AnimationHook val]
    (set! opts (.-opts val))
    (set! keyframes (.-keyframes val))
    (set! timing (.-timing val))
    false)

  (hook-update! [this] false)
  (hook-destroy! [this])

  Object
  (add-element [this el]
    (set! elements (conj elements el)))

  (remove-element [this el]
    (set! elements (->> elements (remove #{el}) (vec)))))

(attr/add-attr ::anim-ref
  (fn [env el ^AnimationHook oval ^AnimationHook nval]
    (if nval
      (.add-element nval el)
      (.remove-element oval el))))

(defc ui-dialog []
  (bind anim-in
    (AnimationHook.
      {:play-on-enter true
       :stagger 100}
      [{:transform "translateX(10%)"}
       {:transform "translateX(0)"}]
      {:duration 2000}))

  (render
    (<< [:div {:on-click ::close!}
         "Hello World, click me"

         [:div {::anim-ref anim-in} "a"]
         [:div {::anim-ref anim-in} "b"]
         [:div {::anim-ref anim-in} "c"]
         [:div {::anim-ref anim-in} "d"]
         [:div {::anim-ref anim-in} "e"]])))

(defc ui-root []
  (bind state-ref
    (atom false))

  (bind visible?
    (sg/watch state-ref))

  (event ::show! [env ev e]
    (reset! state-ref true))

  (event ::close! [env ev e]
    (reset! state-ref false))

  (render
    (<< [:div
         {:class (css :p-4 :text-lg :border)
          :on-click ::show!}
         "click me"]

        (when visible?
          (ui-dialog)))))

(defonce root-el
  (js/document.getElementById "root"))

(defonce data-ref
  (-> {}
      (atom)))

(defonce rt-ref
  (rt/prepare {} data-ref ::rt))

(defn ^:dev/after-load start []
  (sg/render rt-ref root-el (ui-root)))

(defn init []
  (local-eng/init! rt-ref)
  (start))
