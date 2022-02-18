(ns shadow.experiments.grove.ui.testing
  (:require
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.components :as comp]))

(set! *warn-on-infer* false)

(deftype DelayHook [component-handle max ^:mutable timeout]
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
    (gp/hook-invalidate! component-handle)))

(deftype DelayInit [max]
  gp/IBuildHook
  (hook-build [this ch]
    (DelayHook. ch max nil)))

(defn rand-delay [max]
  (DelayInit. max))
