(ns shadow.grove.devtools
  {:dev/always true}
  (:require
    [shadow.cljs.modern :refer (js-await)]
    [shadow.grove.history :as history]
    [shadow.grove.http-fx :as http-fx]
    [shadow.grove.impl :as impl]
    [shadow.grove.events :as ev]
    [shadow.grove.devtools :as-alias m]
    [shadow.grove.devtools.ui :as ui]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.transit :as transit]))

(defonce root-el
  (js/document.getElementById "root"))

(defonce rt-ref
  (sg/get-runtime ::m/ui))

(defn render []
  (sg/render rt-ref root-el (ui/ui-root)))

(defn register-events! []
  (ev/register-events! rt-ref))

(defn ^:dev/after-load reload! []
  (register-events!)
  (render))

(defn init []
  (sg/add-kv-table rt-ref :db
    {:validate-key keyword?}
    {::m/selected #{}})

  (sg/add-kv-table rt-ref ::m/target
    {:primary-key :target-id})

  (sg/add-kv-table rt-ref ::m/event
    {:primary-key :event-id})

  (register-events!)

  (transit/init! rt-ref
    {:reader-opts
     {:handlers
      {"transit-unknown"
       (fn [x]
         ;; FIXME: should it make an attempt at reading?
         ;; sender side used pr-str to convert
         ;; installing this only so that a transit internal TaggedValue type doesn't leak out
         ;; don't wanna be handling transit types anywhere else
         ;; shouldn't just pretend that it got a string, so abusing cljs tagged-literal for now
         (tagged-literal 'unsupported x))}}})

  #_(history/init! env/rt-ref
      {:start-token "/dashboard"
       :use-fragment true
       :root-el root-el})

  (sg/reg-fx rt-ref :shadow-api
    (http-fx/make-handler
      {:on-error {:e ::m/request-error!}
       :base-url "/api"
       :request-format :transit}))

  (when ^boolean js/goog.DEBUG
    ;; can't use devtools for this since it creates a recursive infinite loop
    ;; inspecting its own data causes more data, which then causes more data, ...
    ;; being limited to console.log sucks
    (set! impl/tx-reporter
      (fn [tx-env]
        (let [e (-> tx-env :event :e)]
          (case e
            ::relay-ws
            (js/console.log "[WS]" (-> tx-env :event :msg :op) (-> tx-env :event :msg) tx-env)
            (js/console.log e tx-env))))))

  (when-some [search js/document.location.search]
    (let [params (js/URLSearchParams. search)]
      (when-some [rt-id (.get params "runtime")]
        (let [target-id (js/parseInt rt-id 10)]
          (sg/run-tx! rt-ref
            {:e ::m/select-target!
             :target-id target-id})

          (when-some [node-id (.get params "component")]
            (sg/run-tx! rt-ref
              {:e ::m/set-selection!
               :target-id target-id
               :v #{node-id}})
            ))))

    ;; remove params until proper routing is implemented
    ;; otherwise may end up stuck with a runtime id in url that no longer exists
    ;; and then reloading the devtools causes an error
    (js/history.replaceState nil "" js/document.location.pathname))

  (js-await [req (js/fetch "/api/token")]
    (js-await [server-token (.text req)]
      (relay-ws/init rt-ref server-token
        (fn []
          ))))

  (render))