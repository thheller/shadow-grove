(ns dummy.flex
  (:require
    [shadow.arborist.common :as common]
    [shadow.arborist.protocols :as ap]
    [shadow.grove :as sg :refer (<< defc css)]
    [shadow.grove.components :as comp]
    [town.lilac.flex :as flex]
    ))

;; An example implemention of how signal support can be added to grove
;; using https://github.com/lilactown/flex as the signal implementation

;; THIS CODE WOULD BE PROVIDED BY A LIBRARY NAMESPACE, could be shadow.grove.flex
;; I'm not a huge fan of signals, so not yet committing to keeping this in grove directly

(deftype ManagedSignalSink [env src root ^:mutable sub-fn]
  ap/IManaged
  (supports? [this next]
    (identical? src next))

  (dom-sync! [this _]
    (ap/update! root @src))

  (dom-insert [this parent anchor]
    (ap/dom-insert root parent anchor))

  (dom-first [this]
    (ap/dom-first root))

  (dom-entered! [this]
    (ap/dom-entered! root))

  (destroy! [this ^boolean dom-remove?]
    (when sub-fn
      (flex/dispose! sub-fn))
    (ap/destroy! root dom-remove?))

  Object
  (subscribe-now! [this]
    (set! sub-fn (flex/listen src
                   (fn [new-val]
                     ;; for simplity sake just update directly
                     ;; in a real impl this should use the scheduler implementation like components do
                     (ap/update! root new-val))))
    ))

(defn as-managed-sink [src env]
  (let [root (doto (common/managed-root env)
               (ap/update! @src))]
    (doto (ManagedSignalSink. env src root nil)
      (.subscribe-now!))))

(extend-protocol ap/IConstruct
  flex/SyncSource
  (as-managed [src env]
    (as-managed-sink src env))

  flex/SyncSignal
  (as-managed [src env]
    (as-managed-sink src env)))

(defn sig-listen [signal]
  (let [ref (comp/claim-bind! ::sig-listen)]

    (when-not @ref
      (comp/set-cleanup! ref #(flex/dispose! (:cleanup @ref)))
      (reset! ref {:updates 0
                   :cleanup
                   (flex/listen signal
                     (fn [new-val]
                       ;; ignoring new-val, sig-listen is called again when it is time to actually render
                       ;; fine to just deref again then to never have the issue of rendering outdated vals
                       ;; using this swap as a trigger to schedule a re-render of the component
                       (swap! ref update :updates inc)))}))

    @signal
    ))

;; actual user code starts there

(defc ui-example [counter]
  (bind val (flex/signal (* @counter 2)))
  (render
    (<< [:div
         [:div "here's the counter: " counter]
         [:div "and here's the counter twice: " val]])))

(defc ui-example2 [counter]
  (bind val (sig-listen counter))
  (bind doubled (* val 2))
  (render
    (<< [:div
         [:div "here's the counter: " val]
         [:div "and here's the counter twice: " doubled]])))


(defn ui-root [counter]
  ;; placing the flex source directly into the DOM
  (<< [:div "count: " counter]
      ;; or pass it to a component, to be used in bind
      (ui-example counter)
      (ui-example2 counter)
      [:div {:on-click #(counter inc)} "click me"]
      ))

(defonce root-el
  (js/document.getElementById "root"))

(defonce rt-ref
  (sg/get-runtime ::rt))

(def counter (flex/source 0))

(defn ^:dev/after-load start []
  (sg/render rt-ref root-el (ui-root counter)))

(defn init []
  (start))
