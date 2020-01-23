(ns shadow.experiments.arborist.common
  (:require
    [goog.dom :as gdom]
    [shadow.experiments.arborist.protocols :as p]))

(defn dom-marker [env]
  (js/document.createTextNode ""))

(defn in-document? [el]
  (gdom/isInDocument el))

(defn fragment-replace [old-managed new-managed]
  (let [first-node (p/dom-first old-managed)
        _ (assert (some? first-node) "fragment replacing a node that isn't in the DOM")
        parent (.-parentNode first-node)]

    (p/dom-insert new-managed parent first-node)
    (p/destroy! old-managed)
    new-managed))

(defn replace-managed [env old nval]
  (let [new (p/as-managed nval env)]
    (fragment-replace old new)))

;; swappable root
(deftype ManagedRoot
  [env
   ^boolean ^:mutable dom-entered?
   ^:mutable marker
   ^:mutable
   ^not-native node
   ^:mutable val]

  p/IManaged
  (dom-first [this] marker)

  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor)
    (when node
      (p/dom-insert node parent anchor)
      ))

  (dom-entered! [this]
    (set! dom-entered? true)
    (when node
      (p/dom-entered! node)))

  (supports? [this next]
    (throw (ex-info "invalid use, don't sync roots?" {:this this :next next})))

  (dom-sync! [this next]
    (throw (ex-info "invalid use, don't sync roots?" {:this this :next next})))

  (destroy! [this]
    (.remove marker)
    (when node
      (p/destroy! node)))

  p/IDirectUpdate
  (update! [this next]
    (set! val next)
    (cond
      (not node)
      (let [el (p/as-managed val env)]
        (set! node el)
        ;; root was already inserted to dom but no node was available at the time
        (when-some [parent (.-parentElement marker)]
          (p/dom-insert node parent marker)
          ;; root might not be in document yet
          (when dom-entered?
            (p/dom-entered! node))))

      (p/supports? node next)
      (p/dom-sync! node next)

      :else
      (let [new (replace-managed env node next)]
        (set! node new)
        (when dom-entered?
          (p/dom-entered! new)
          )))))

(defn managed-root [env]
  (ManagedRoot. env false (dom-marker env) nil nil))

(deftype ManagedText [env ^:mutable val node]
  p/IManaged
  (dom-first [this] node)

  (dom-insert [this parent anchor]
    (.insertBefore parent node anchor))

  (dom-entered! [this])

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
