(ns shadow.experiments.grove.main.loadable
  (:require-macros [shadow.experiments.grove.main.loadable])
  (:require
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.arborist.common :as common]
    [shadow.lazy :as lazy]))

;; FIXME: shadow.lazy is only available with shadow-cljs since it requires compiler support
;; must not use this namespace directly in the framework elsewhere since that would
;; make everything shadow-cljs only. not that important but also not necessary to do that.

(declare LoadableInit)

(deftype LoadableRoot
  [env
   scheduler
   loadable
   marker
   ^not-native ^:mutable managed
   ^:mutable opts
   ^:mutable dom-entered?]

  ap/IUpdatable
  (supports? [this ^LoadableInit next]
    (and (instance? LoadableInit next)
         (identical? loadable (.-loadable next))))

  (dom-sync! [this ^LoadableInit next]
    (set! opts (.-opts next))

    (when managed
      (let [renderable @loadable
            rendered (renderable opts)]
        (if (ap/supports? managed rendered)
          (ap/dom-sync! managed rendered)
          (let [new (common/replace-managed env managed rendered)]
            (set! managed new)
            (when dom-entered?
              (ap/dom-entered! new))
            )))))

  ap/IManageNodes
  (dom-insert [this parent anchor]
    ;; (js/console.log ::dom-insert this)
    (.insertBefore parent marker anchor)
    (when managed
      (ap/dom-insert managed parent anchor)))

  (dom-first [this]
    marker)

  (dom-entered! [this]
    ;; (js/console.log ::dom-entered! this)
    (set! dom-entered? true)
    (when managed
      (ap/dom-entered! managed)))

  ap/IDestructible
  (destroy! [this]
    ;; (js/console.log ::destroy! this)
    (.remove marker)
    (when managed
      (ap/destroy! managed)))

  Object
  (init! [this]
    ;; (js/console.log ::init! (lazy/ready? loadable))
    (if (lazy/ready? loadable)
      (.render! this)
      (do (gp/did-suspend! scheduler this)
          ;; (js/console.log ::did-suspend! this)
          (lazy/load loadable
            (fn []
              ;; (js/console.log ::loaded this)
              (.render! this)

              ;; FIXME: dom-insert should have happened by now but might not be because of suspense
              (when-some [parent-el (.-parentElement marker)]
                (ap/dom-insert managed parent-el marker)
                (when dom-entered?
                  (ap/dom-entered! managed)))

              (gp/did-finish! scheduler this))
            (fn [err]
              (js/console.warn "lazy loading failed" this err)
              )))))

  (render! [this]
    ;; (js/console.log ::render! this (lazy/ready? loadable))
    (let [renderable @loadable
          rendered (renderable opts)
          new (ap/as-managed rendered env)]
      (set! managed new))))

(deftype LoadableInit [loadable opts]
  ap/IConstruct
  (as-managed [this env]
    ;; (js/console.log ::as-managed this env)
    (doto (->LoadableRoot env (::gp/scheduler env) loadable (common/dom-marker env) nil opts false)
      (.init!))))

(defn wrap-loadable [loadable]
  (fn [opts]
    ;; this should NOT check if loadable is ready
    ;; otherwise may lead to situation where it is not ready at first
    ;; and rendering the loadable
    ;; then on re-render it would be ready but replace the content
    ;; since the new rendered is not compatible with the managed loadable
    ;; so the impl takes care of rendering immediately if available
    (LoadableInit. loadable opts)))

