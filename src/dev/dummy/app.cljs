(ns dummy.app
  (:require
    [shadow.grove :as sg :refer (<< defc css)]
    [shadow.grove.ref-components :as rc]
    [shadow.grove.css-fx :as fx]))

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

(defc ui-dialog []
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

(comment
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
            (ui-dialog))))))

(comment
  (defrc ui-root []
    (ref clicks 0)
    (ref clicks+1 (inc @clicks))
    (render
      (<< [:div {:on-click {:e ::click!}} "clicks: " @clicks " and " @clicks+1]))
    (event ::click! [_]
      (swap! clicks inc))
    ))

(def ui-root
  (rc/ComponentConfig.
    ;; check-args
    (fn [])

    ;; refs
    #js [(rc/RefConfig. {} (fn [comp] 0))
         (rc/RefConfig. {} (fn [comp]
                             (let [clicks (rc/get-ref comp 0)]
                               (inc @clicks))))]

    ;; render
    (fn render [comp]
      (let [clicks (rc/get-ref comp 0)
            clicks+1 (rc/get-ref comp 1)]
        (<< [:div {:on-click {:e ::click!}} "clicks: " @clicks " and " @clicks+1])))

    ;; events
    {::click!
     (fn [comp env ev e]
       (let [clicks (rc/get-ref comp 0)]
         (swap! clicks inc)
         ))}

    ;; effects
    #js [(fn [comp]
           ;; trigger is get-arg?
           (let [arg (rc/get-arg comp 0)]
             (js/console.log "only triggers when arg changes" arg)))
         (fn [comp]
           (let [foo (rc/get-ref comp 0)]
             ;; trigger is actual deref, not `get-ref`
             (js/console.log "only triggers when foo changes" @foo)))]
    ))

(defonce root-el
  (js/document.getElementById "root"))

(defonce data-ref
  (-> {}
      (atom)))

(defonce rt-ref
  (sg/prepare {} data-ref ::rt))

(defn ^:dev/after-load start []
  (sg/render rt-ref root-el (ui-root)))

(defn init []
  (start))
