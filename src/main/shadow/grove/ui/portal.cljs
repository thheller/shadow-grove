(ns shadow.grove.ui.portal
  (:require
    [shadow.arborist.protocols :as ap]
    [shadow.arborist.common :as common]))

(declare PortalSeed)

(deftype PortalNode [env ref-node root]
  ap/IManaged
  (supports? [this ^PortalSeed next]
    (and (instance? PortalSeed next)
         (identical? ref-node (.-ref-node next))))

  (dom-sync! [this ^PortalSeed next]
    (ap/update! root (.-body next)))

  (dom-insert [this parent anchor]
    (ap/dom-insert root ref-node nil))

  (dom-first [this]
    (ap/dom-first root))

  (dom-entered! [this]
    (ap/dom-entered! root))

  (destroy! [this ^boolean dom-remove?]
    ;; always dom-remove? true since the root is a child of the ref-node not the parent
    (ap/destroy! root true)))

(deftype PortalSeed [ref-node body]
  ap/IConstruct
  (as-managed [this env]
    (PortalNode.
      env
      ref-node
      (doto (common/managed-root env)
        (ap/update! body)))))

(defn portal [ref-node body]
  (PortalSeed. ref-node body))
