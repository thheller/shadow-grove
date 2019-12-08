(ns shadow.experiments.arborist.attributes
  (:require
    [goog.object :as gobj]
    [clojure.string :as str]
    [shadow.experiments.arborist.protocols :as p]
    ))

(defonce attr-handlers #js {})

;; FIXME: keep this code short, due to the set-attr* multimethod nothing this uses will ever be removed

(defn vec->class [v]
  (reduce
    (fn [s c]
      (cond
        (not c)
        s

        (not s)
        c

        :else
        (str s " " c)))
    nil
    v))

(defn map->class [m]
  (reduce-kv
    (fn [s k v]
      (cond
        (not v)
        s

        (not s)
        (if (keyword? k) (-name k) k)

        :else
        (str s " " (if (keyword? k) (-name k) k))))
    nil
    m))

(defn add-attr [^Keyword kw handler]
  {:pre [(keyword? kw)
         (fn? handler)]}
  (js/goog.object.set attr-handlers (.-fqn kw) handler))

;; quasi multi-method. not using multi-method because it does too much stuff I don't accidentally
;; want to run into (eg. keyword inheritance). while that might be interesting for some cases
;; it may also blow up badly. also this is less code in :advanced.
(defn set-attr [env ^js node ^Keyword key oval nval]
  {:pre [(keyword? key)]}
  (let [^function handler (js/goog.object.get attr-handlers (.-fqn key))]
    (if (some? handler)
      (handler env node oval nval)

      ;; FIXME: behave like goog.dom.setProperties?
      ;; https://github.com/google/closure-library/blob/31e914b9ecc5c6918e2e6462cbbd4c77f90be753/closure/goog/dom/dom.js#L453
      ;; FIXME: for web component interop this shouldn't be using setAttribute
      (if nval
        (.setAttribute node (.-name key) nval)
        (.removeAttribute node (.-name key))))))

(add-attr :for
  (fn [env ^js node oval nval]
    (set! node -htmlFor nval)))

(add-attr :style
  (fn [env ^js node oval nval]
    (cond
      (map? nval)
      (let [style (.-style node)]
        (reduce-kv
          (fn [_ ^not-native k v]
            (gobj/set style (-name k) v))
          nil
          nval))

      (string? nval)
      (set! (.. node -style -cssText) nval)

      :else
      (throw (ex-info "invalid value for :class" {:node node :val nval}))
      )))

(add-attr :class
  (fn [env ^js node oval nval]
    (cond
      (nil? nval)
      (set! node -className "")

      (string? nval)
      (set! node -className nval)

      ;; FIXME: classlist?
      (vector? nval)
      (if-let [s (vec->class nval)]
        (set! node -className s)
        (set! node -className ""))

      (map? nval)
      (if-let [s (map->class nval)]
        (set! node -className s)
        ;; FIXME: removeAttribute? nil?
        (set! node -className ""))

      :else
      (throw (ex-info "invalid value for :class" {:node node :val nval}))
      )))



