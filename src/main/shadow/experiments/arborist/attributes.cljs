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
      (and (nil? oval) (nil? nval))
      :empty

      (map? nval)
      (let [style (.-style node)]
        (reduce-kv
          (fn [_ ^not-native k v]
            (gobj/set style (-name k) v))
          nil
          nval))

      (string? nval)
      (set! (.. node -style -cssText) nval)

      ;; nil, undefined
      (not (some? nval))
      (set! (.. node -style -cssText) "")

      :else
      (throw (ex-info "invalid value for :style" {:node node :val nval}))
      )))

(add-attr :class
  (fn [^not-native env ^js node oval nval]
    (let [sval
          (cond
            (nil? nval)
            ""

            (string? nval)
            nval

            ;; FIXME: classlist?
            (vector? nval)
            (if-let [s (vec->class nval)]
              s
              "")

            (map? nval)
            (if-let [s (map->class nval)]
              s
              "")

            :else
            (throw (ex-info "invalid value for :class" {:node node :val nval})))]

      ;; setting className directly doesn't work for SVG elements since its a SVGAnimatedString
      ;; FIXME: could set baseVal directly?
      (if ^boolean (:dom/svg env)
        (.setAttribute node "class" sval)
        (set! node -className sval)))))

(defn merge-attrs
  "merge attributes from old/new attr maps"
  [env node old new]
  (reduce-kv
    (fn [_ key nval]
      (let [oval (get old key)]
        (when (not= nval oval)
          (set-attr env node key oval nval))))
    nil
    new)

  ;; {:a 1 :x 1} vs {:a 1}
  ;; {:a 1} vs {:b 1}
  ;; should be uncommon but need to unset props that are no longer used
  (reduce-kv
    (fn [_ key oval]
      (when-not (contains? new key)
        (set-attr env node key oval nil)))
    nil
    old))

(defn set-attrs
  "initial set attributes from key/val map"
  [env node attrs]
  (reduce-kv
    (fn [_ key val]
      (set-attr env node key nil val))
    nil
    attrs))




