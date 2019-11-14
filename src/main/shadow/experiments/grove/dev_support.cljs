(ns shadow.experiments.grove.dev-support
  (:require
    [clojure.core.protocols :as cp]
    [shadow.debug :refer (?> ?-> ?->>)]
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.arborist.components :as comp]
    [shadow.remote.runtime.api :as rapi]
    [shadow.experiments.grove :as sg]
    [shadow.experiments.arborist :as sa]))

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
     :parent-env (.-parent-env this)
     :component-env (.-component-env this)
     :props (.-props this)
     :state (.-state this)
     :rendered-props (.-rendered-props this)
     :rendered-state (.-rendered-state this)
     :config (.-config this)
     :events (.-events this)
     :root (.-root this)
     :slots (.-slots this)
     :current-idx (.-current-idx this)
     :hooks (vec (.-hooks this))
     :dirty-hooks (.-dirty-hooks this)
     :updated-hooks (.-updated-hooks this)
     :needs-render? (.-needs-render? this)
     :destroyed? (.-destroyed? this)})

  IPrintWithWriter
  (-pr-writer [component writer opts]
    (-pr-writer
      [::comp/ManageComponent
       (symbol (-> component .-config .-component-name))
       (-> component .-component-env ::comp/component-id)]
      writer
      opts))

  ;; perf tracking should have no overhead in release builds
  ;; default impl does nothing but preload provides impl that actually does stuff
  ;; this way in :advanced builds all perf calls get removed entirely by closure
  p/IProfile
  (perf-count! [component key]
    (swap! perf-data-ref update-in (into [component] key) safe-inc))

  (perf-start! [component]
    ;; too noisy
    ;; (?> component ::component-init!)
    )

  (perf-destroy! [component]
    (let [data (get @perf-data-ref component)]
      (swap! perf-data-ref dissoc component))))

(extend-type p/ComponentConfig
  cp/Datafiable
  (datafy [this]
    {:component-name (.-component-name this)
     :hooks (.-hooks this)
     :init-state (.-init-state this)
     :props-affects-render (.-props-affects-render this)
     :props-affects (.-props-affects this)
     :state-affects-render (.-state-affects-render this)
     :state-affects (.-state-affects this)
     :render-deps (.-render-deps this)}))

(extend-type comp/HookConfig
  cp/Datafiable
  (datafy [this]
    {:depends-on (.-depends-on this)
     :affects (.-affects this)
     :run (.-run this)}))

(extend-type comp/SimpleVal
  cp/Datafiable
  (datafy [this]
    {:val (.-val this)}))

(extend-type sg/QueryNode
  cp/Datafiable
  (datafy [this]
    {:ident (.-ident this)
     :query (.-query this)
     :component (.-component this)
     :idx (.-idx this)
     :env (.-env this)
     :read-keys (.-read-keys this)
     :read-result (.-read-result this)}
    )

  IPrintWithWriter
  (-pr-writer [this writer opts]
    (-pr-writer
      [::sg/QueryNode
       (.-ident this)
       (.-query this)]
      writer
      opts)))

(extend-type sa/TreeScheduler
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
