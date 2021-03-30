(ns shadow.experiments.arborist.common
  (:require
    [goog.dom :as gdom]
    [shadow.experiments.arborist.protocols :as p]))

;; helper functions that lets us bypass the common CLJS ifn dispatch check
;; helpful in hot loops or places where the same function (or ifn) is called multiple times
;; and we just want to avoid the extra generated code

;; in certain places we care about calling instead a function directly
;;   x(y)

;; instead of the inline check
;;   x.cljs$core$IFn$_invoke$arity$1 ? x.cljs$core$IFn$_invoke$arity$1(y) : x.call(y)

;; I'm sure JS engines are smart enough to skip the check after a while but best not to rely on it
;; also generates less code which is always good

;; never call these in a hot loop, better to leave the check for those cases

(defn ifn1-wrap ^function [x]
  (if ^boolean (.-cljs$core$IFn$_invoke$arity$1 x)
    (fn [a]
      (.cljs$core$IFn$_invoke$arity$1 x a))
    x))

(defn ifn2-wrap ^function [x]
  (if ^boolean (.-cljs$core$IFn$_invoke$arity$2 x)
    (fn [a b c]
      (.cljs$core$IFn$_invoke$arity$2 x a b))
    x))

(defn ifn3-wrap ^function [x]
  (if ^boolean (.-cljs$core$IFn$_invoke$arity$3 x)
    (fn [a b c]
      (.cljs$core$IFn$_invoke$arity$3 x a b c))
    x))

(defn dom-marker
  ([env]
   (js/document.createTextNode ""))
  ([env label]
   (if ^boolean js/goog.DEBUG
     (js/document.createComment label)
     (js/document.createTextNode ""))))

(defn in-document? [el]
  (gdom/isInDocument el))

(defn fragment-replace [old-managed new-managed]
  (let [first-node (p/dom-first old-managed)
        _ (assert (some? first-node) "fragment replacing a node that isn't in the DOM")
        parent (.-parentNode first-node)]

    (p/dom-insert new-managed parent first-node)
    (p/destroy! old-managed true)
    new-managed))

(defn replace-managed ^not-native [env old nval]
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

  (destroy! [this ^boolean dom-remove?]
    (when dom-remove?
      (.remove marker))
    (when node
      (p/destroy! node dom-remove?)))

  p/IDirectUpdate
  (update! [this next]
    (set! val next)
    (cond
      (not node)
      (let [el (p/as-managed val env)]
        (set! node el)
        ;; root was already inserted to dom but no node was available at the time
        (when-some [parent (.-parentElement marker)]
          (p/dom-insert node parent (.-nextSibling marker))
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

  (destroy! [this ^boolean dom-remove?]
    (when dom-remove?
      (.remove node))))

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
