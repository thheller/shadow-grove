(ns shadow.experiments.grove.effects
  (:require
    [shadow.experiments.arborist.attributes :as saa]
    [shadow.experiments.grove.components :as comp]
    [shadow.experiments.grove.protocols :as gp]))

;; FIXME: make this actually useful, not just a dummy effect

(deftype EffectHook [^:mutable dom-node component-handle]
  gp/IBuildHook
  (hook-build [this ch]
    (EffectHook. dom-node ch))

  gp/IHook
  (hook-init! [this])
  (hook-ready? [this] true)
  (hook-value [this] this)
  (hook-update! [this]
    (throw (ex-info "effect hook update, TBD" {})))
  (hook-deps-update! [this new-val]
    (throw (ex-info "effect hook update, TBD" {})))
  (hook-destroy! [this]
    ;; track if running and maybe do early cleanup
    )

  IFn
  (-invoke [this]
    (.trigger! this))
  (-invoke [this after]
    (.trigger! this)
    (js/setTimeout #(gp/run-now! (gp/get-scheduler component-handle) after ::effect-hook) 200))

  Object
  (trigger! [this after]
    (set! (.. dom-node -style -transition) "opacity 200ms ease-out, transform 200ms ease-out")
    ;; (set! (.. dom-node -style -transform) "scale(1)")
    ;; css trigger
    ;; (js/goog.reflect.sinkValue (.. dom-node -offsetWidth))
    (set! (.. dom-node -style -opacity) 0)
    (set! (.. dom-node -style -transform) "scale(0)"))

  (set-node! [this node]
    (when (and dom-node (not (identical? node dom-node)))
      (throw (ex-info "already had a node" {:node node :dom-node dom-node})))
    (set! dom-node node)))

(defn make-test-effect [ignored]
  (EffectHook. nil nil nil))

(saa/add-attr ::effect
  (fn [env ^js node oval ^EffectHook nval]
    {:pre [(instance? EffectHook nval)]}
    (.set-node! nval node)))

;; FIXME: figure out how to make custom fx without getting too OOP-ish
(def fade-out
  {:init (fn [args] (js/console.log "fade-out-init") {})
   :start (fn [args] {})
   :stop (fn [args] {})})