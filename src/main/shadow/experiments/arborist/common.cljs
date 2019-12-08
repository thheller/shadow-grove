(ns shadow.experiments.arborist.common
  (:require [shadow.experiments.arborist.protocols :as p]))

(defn dom-marker [env]
  (js/document.createTextNode ""))

(defn fragment-replace [old-managed new-managed]
  (let [first-node (p/dom-first old-managed)
        _ (assert (some? first-node) "fragment replacing a node that isn't in the DOM")
        parent (.-parentNode first-node)]

    (p/dom-insert new-managed parent first-node)
    (p/destroy! old-managed)))

(defn replace-managed [env old nval]
  (let [new (p/as-managed nval env)]
    (fragment-replace old new)
    new
    ))

;; swappable root
(deftype ManagedRoot [env ^:mutable marker ^:mutable ^not-native node ^:mutable val]
  p/IManageNodes
  (dom-first [this] marker)

  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor)
    (when node
      (p/dom-insert node parent anchor)))

  p/IDirectUpdate
  (update! [this next]
    ;; FIXME: should this even compare?
    ;; unlikely to be called with identical fragments right?
    (when (not= next val)
      (set! val next)
      (cond
        (not node)
        (let [el (p/as-managed val env)]
          (set! node el)
          ;; root was already added to dom but no node was available at the time
          (when-some [parent (.-parentElement marker)]
            (p/dom-insert node parent marker)))

        (p/supports? node next)
        (p/dom-sync! node next)

        :else
        (let [new (replace-managed env node next)]
          (set! node new)))))

  p/IDestructible
  (destroy! [this]
    (.remove marker)
    (when node
      (p/destroy! node))))

(defn managed-root [env node val]
  (ManagedRoot. env (dom-marker env) node val))

(deftype ManagedText [env ^:mutable val node]
  p/IManageNodes
  (dom-first [this] node)

  (dom-insert [this parent anchor]
    (.insertBefore parent node anchor))

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
