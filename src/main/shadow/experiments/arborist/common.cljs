(ns shadow.experiments.arborist.common
  (:require [shadow.experiments.arborist.protocols :as p]))


(defn marker [env]
  (js/document.createTextNode ""))

(defn fragment-replace [old-managed new-managed]
  (let [first-node (p/dom-first old-managed)
        parent (.-parentNode first-node)]

    (p/dom-insert new-managed parent first-node)
    (p/destroy! old-managed)))

(defn replace-managed [env old nval]
  (let [new (p/as-managed nval env)]
    (fragment-replace old new)
    new
    ))

;; swappable root
(deftype ManagedRoot [env ^:mutable node ^:mutable val]
  p/IManageNodes
  (dom-first [this]
    (p/dom-first node))

  (dom-insert [this parent anchor]
    (when-not node
      (throw (ex-info "root not initialized" {})))

    (p/dom-insert node parent anchor))

  p/ITreeNode
  (sync! [this]
    (p/sync! node))

  p/ITraverseNodes
  (managed-nodes [this]
    (if-not node
      []
      [node]))

  p/IDirectUpdate
  (update! [this next]
    (when (not= next val)
      (set! val next)
      (cond
        (not node)
        (let [el (p/as-managed val env)]
          (set! node el))

        (p/supports? node next)
        (p/dom-sync! node next)

        :else
        (let [new (replace-managed env node next)]
          (set! node new)))))

  p/IDestructible
  (destroy! [this]
    (when node
      (p/destroy! node))))

(deftype ManagedText [env ^:mutable val node]
  p/IManageNodes
  (dom-first [this] node)

  (dom-insert [this parent anchor]
    (.insertBefore parent node anchor))

  p/ITraverseNodes
  (managed-nodes [this] [])

  p/IUpdatable
  (supports? [this next]
    ;; FIXME: anything else?
    (or (string? next)
        (number? next)
        (nil? next)))

  (dom-sync! [this next]
    (when (not= val next)
      (set! val next)
      ;; https://twitter.com/_developit/status/1129093390883315712
      (set! node -data (str next)))
    :synced)

  p/IDestructible
  (destroy! [this]
    (.remove node)))

(defn managed-text [env val]
  (ManagedText. env val (js/document.createTextNode (str val))))

(extend-protocol p/IConstruct
  string
  (as-managed [this env]
    (managed-text env this))

  number
  (as-managed [this env]
    (managed-text env this))

  ;; as a placeholder for (when condition (<< [:deep [:tree]]))
  nil
  (as-managed [this env]
    (managed-text env this)))
