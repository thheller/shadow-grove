(ns shadow.experiments.grove.ui.testing
  (:require
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.components :as comp]))

(set! *warn-on-infer* false)

(deftype DelayHook [component idx max ^:mutable timeout]
  gp/IHook
  (hook-init! [this]
    (let [timeout-ms (rand-int max)]
      (set! timeout (js/setTimeout #(.on-timeout! this) timeout-ms))))
  (hook-ready? [this]
    (nil? timeout))

  (hook-value [this] ::timeout)
  (hook-deps-update! [this val])
  (hook-update! [this])
  (hook-destroy! [this]
    (when timeout
      (js/clearTimeout timeout)
      (set! timeout nil)))

  Object
  (on-timeout! [this]
    (set! timeout nil)
    (comp/hook-ready! component idx)))

(deftype DelayInit [max]
  gp/IBuildHook
  (hook-build [this component idx]
    (DelayHook. component idx max nil)))

(defn rand-delay [max]
  (DelayInit. max))
