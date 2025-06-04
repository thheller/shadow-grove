(ns shadow.grove.ui.lazy
  (:refer-clojure :exclude #{use})
  (:require
    [shadow.arborist.protocols :as ap]
    [shadow.arborist.common :as common]
    [shadow.grove :as-alias sg]
    [shadow.grove.protocols :as gp]
    [shadow.esm :as esm]
    ))

(declare LoadableInit)

(deftype LoadableRoot
  [env
   scheduler
   loadable-name
   marker
   ^not-native ^:mutable managed
   ^:mutable result
   ^:mutable opts
   ^:mutable dom-entered?]

  ap/IManaged
  (supports? [this ^LoadableInit next]
    (and (instance? LoadableInit next)
         (identical? loadable-name (.-loadable-name next))))

  (dom-sync! [this ^LoadableInit next]
    (set! opts (.-opts next))

    (when (and managed result)
      (let [rendered (apply (result) opts)]
        (if (ap/supports? managed rendered)
          (ap/dom-sync! managed rendered)
          (let [new (common/replace-managed env managed rendered)]
            (set! managed new)
            (when dom-entered?
              (ap/dom-entered! new))
            )))))

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

  (destroy! [this ^boolean dom-remove?]
    ;; (js/console.log ::destroy! this)
    (when dom-remove?
      (.remove marker))
    (when managed
      (ap/destroy! managed dom-remove?)))

  Object
  (init! [this]
    ;; (js/console.log ::init! (lazy/ready? loadable))

    (if-some [val (esm/get-loaded loadable-name)]
      (do (set! this -result val)
          (.render! this))
      (do (gp/did-suspend! scheduler this)
          (-> (esm/load-by-name loadable-name)
              (.then (fn [res]
                       (set! this -result res)
                       (.render! this)

                       (when-some [parent-el (.-parentElement marker)]
                         (ap/dom-insert managed parent-el marker)
                         (when dom-entered?
                           (ap/dom-entered! managed)))

                       (gp/did-finish! scheduler this)
                       ))))))

  (render! [this]
    ;; (js/console.log ::render! this (lazy/ready? loadable))
    (let [rendered (apply (result) opts)
          new (ap/as-managed rendered env)]
      (set! managed new))))

(deftype LoadableInit [loadable-name args]
  ap/IConstruct
  (as-managed [this env]
    ;; (js/console.log ::as-managed this env)
    (doto (->LoadableRoot env (::sg/scheduler env) loadable-name (common/dom-marker env) nil nil args false)
      (.init!))))


(defn use [name & args]
  (LoadableInit. name args))

