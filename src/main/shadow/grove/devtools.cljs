(ns shadow.grove.devtools
  {:dev/always true}
  (:require
    [shadow.cljs.modern :refer (js-await)]
    [shadow.grove.db :as db]
    [shadow.grove.history :as history]
    [shadow.grove.runtime :as rt]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools :as-alias m]
    [shadow.grove.devtools.env :as env]
    [shadow.grove.devtools.ui :as ui]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.transit :as transit]))

(defonce root-el
  (js/document.getElementById "root"))

(defn render []
  (sg/render env/rt-ref root-el (ui/ui-root)))

(defn register-events! []
  (ev/register-events! env/rt-ref))

(defn ^:dev/after-load reload! []
  (register-events!)
  (render))

(defn init []
  (register-events!)

  (transit/init! env/rt-ref)

  #_(history/init! env/rt-ref
      {:start-token "/dashboard"
       :use-fragment true
       :root-el root-el})

  (when ^boolean js/goog.DEBUG
    (swap! env/rt-ref assoc :shadow.grove.runtime/tx-reporter
      (fn [report]
        (let [e (-> report :event :e)]
          (case e
            ::relay-ws
            (js/console.log "[WS]" (-> report :event :msg :op) (-> report :event :msg) report)
            (js/console.log e report))))))

  (when-some [search js/document.location.search]
    (let [params (js/URLSearchParams. search)]
      (when-some [rt-id (.get params "runtime")]
        (let [ident (db/make-ident ::m/runtime (js/parseInt rt-id 10))]
          (sg/run-tx! env/rt-ref
            {:e :form/set-attr
             :a [::m/selected-runtime]
             :v ident})

          (when-some [node-id (.get params "component")]
            (sg/run-tx! env/rt-ref
              {:e :form/set-attr
               :a ::m/selected
               :v #{node-id}})
            )))))

  (js-await [req (js/fetch "/api/token")]
    (js-await [server-token (.text req)]
      (relay-ws/init env/rt-ref server-token
        (fn []
          ))))

  (render))