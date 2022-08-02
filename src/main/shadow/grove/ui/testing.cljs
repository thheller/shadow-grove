(ns shadow.grove.ui.testing
  (:require
    [shadow.grove.protocols :as gp]
    [shadow.grove.components :as comp]))

(set! *warn-on-infer* false)

(deftype DelayHook
  [^:mutable max
   ^:mutable component-handle
   ^:mutable timeout]
  gp/IHook
  (hook-init! [this ch]
    (set! component-handle ch)
    (let [timeout-ms (rand-int max)]
      (set! timeout (js/setTimeout #(.on-timeout! this) timeout-ms))))

  (hook-ready? [this]
    (nil? timeout))

  (hook-value [this] ::timeout)
  (hook-deps-update! [this ^DelayHook val]
    (set! max (.-max val)))

  (hook-update! [this])

  (hook-destroy! [this]
    (when timeout
      (js/clearTimeout timeout)
      (set! timeout nil)))

  Object
  (on-timeout! [this]
    (set! timeout nil)
    (gp/hook-invalidate! component-handle)))

(defn rand-delay [max]
  (DelayHook. max nil nil))
