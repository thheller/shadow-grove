(ns shadow.arborist.attributes
  (:require
    [goog.object :as gobj]
    [goog.string :as gstr]
    [goog.functions :as gfn]
    [clojure.string :as str]
    [shadow.arborist.protocols :as p]
    ))

(defonce attr-handlers #js {})

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
  (gobj/set attr-handlers (.-fqn kw) handler))

(defn dom-attribute? [name]
  (or (str/starts-with? name "data-")
      (str/starts-with? name "aria-")))

(defn set-dom-attribute [^js node prop-name nval]
  (cond
    (string? nval)
    (.setAttribute node prop-name nval)

    (number? nval)
    (.setAttribute node prop-name nval)

    (nil? nval)
    (.removeAttribute node prop-name)

    (false? nval)
    (.removeAttribute node prop-name)

    ;; convention according to
    ;; https://developer.mozilla.org/en-US/docs/Web/API/Element/setAttribute
    ;; looks a little bit better in the inspector, no clue if this actually
    ;; makes a difference anywhere
    ;; <div data-thing> vs <div data-thing="true">
    (true? nval)
    (.setAttribute node prop-name "")

    :else
    (.setAttribute node prop-name nval)))

(defn set-style-property [^js node prop-name nval]
  (if (nil? nval)
    (.. node -style (removeProperty prop-name))
    (.. node -style (setProperty prop-name nval))))

(defn wrap-stop! [^function target]
  (fn [^js e]
    (.stopPropagation e)
    (.preventDefault e)
    (target e)))

(defn wrap-stop [^function target]
  (fn [^js e]
    (.stopPropagation e)
    (target e)))

(defn wrap-prevent-default [^function target]
  (fn [^js e]
    (.preventDefault e)
    (target e)))

(defn maybe-wrap-ev-fn [ev-fn m ev-opts]
  (let [{:e/keys
         [debounce
          throttle
          rate-limit
          once
          passive
          capture
          signal
          stop!
          stop
          prevent-default]}
        m]

    ;; FIXME: need to track if once already happened. otherwise may re-attach and actually fire more than once
    ;; but it should be unlikely to have a changing val with :e/once?
    (when once
      (gobj/set ev-opts "once" true))

    (when passive
      (gobj/set ev-opts "passive" true))

    (when capture
      (gobj/set ev-opts "capture" true))

    (when signal
      (gobj/set ev-opts "signal" true))

    ;; FIXME: should these be exclusive?
    (cond-> ev-fn
      debounce
      (gfn/debounce debounce)

      throttle
      (gfn/debounce throttle)

      rate-limit
      (gfn/debounce rate-limit)

      ;; FIXME: would it be better to default these to true?
      prevent-default
      (wrap-prevent-default)

      stop
      (wrap-stop)

      stop!
      (wrap-stop!)
      )))

(defn make-attr-handler [^Keyword key]
  (let [prop-name (.-name key)
        prop-ns (.-ns key)]

    (cond
      (dom-attribute? prop-name)
      (fn [env node oval nval]
        (set-dom-attribute node prop-name nval))

      (identical? "style" prop-ns)
      (fn [env node oval nval]
        (set-style-property node prop-name nval))

      prop-ns
      (throw
        (ex-info
          (str "namespaced attribute without setter: " key)
          {:attr key}))

      ;; :on-* convention for events
      ;; only handled when there is an actual handler for it registered in the env
      ;; which will usually be components which I don't want to reference here
      ;; but is common enough that it should also be extensible somewhat
      (str/starts-with? prop-name "on-")
      (let [event (subs prop-name 3)
            ev-key (str "__shadow$" event)]

        (fn [env node oval nval]
          (when-let [ev-fn (gobj/get node ev-key)]
            (.removeEventListener node event ev-fn))

          ;; FIXME: should maybe allow to just use a function as value
          ;; skipping all the ev-handler logic and just calling it as a regular callback
          (when (some? nval)
            (let [^not-native ev-handler (::p/dom-event-handler env)]

              (when-not ev-handler
                (throw (ex-info "missing dom-event-handler!" {:env env :event event :node node :value nval})))

              (when ^boolean js/goog.DEBUG
                ;; validate value now in dev so it fails on construction
                ;; slightly better experience than firing on-event
                ;; easier to miss in tests and stuff that don't test particular events
                (p/validate-dom-event-value! ev-handler env event nval))

              (let [ev-fn
                    (fn [dom-event]
                      (p/handle-dom-event! ev-handler env event nval dom-event))

                    ev-opts
                    #js {}

                    ev-fn
                    (if-not (map? nval)
                      ev-fn
                      (maybe-wrap-ev-fn ev-fn nval ev-opts))]

                ;; FIXME: ev-opts are not supported by all browsers
                ;; closure lib probably has something to handle that
                (.addEventListener node event ev-fn ev-opts)

                (gobj/set node ev-key ev-fn))))))

      :else
      (let [prop (gstr/toCamelCase prop-name)]
        (fn [env node oval nval]
          ;; FIXME: must all attributes in svg elements go with setAttribute?
          ;; can you make web components for svg elements?
          ;; seems to break if we try to go with node.width=24 instead of .setAttribute
          (if ^boolean (:dom/svg env)
            (set-dom-attribute node (.-name key) nval)
            (gobj/set node prop nval)
            ))))))

;; quasi multi-method. not using multi-method because it does too much stuff I don't accidentally
;; want to run into (eg. keyword inheritance). while that might be interesting for some cases
;; it may also blow up badly. also this is less code in :advanced.
(defn set-attr [env ^js node ^Keyword key oval nval]
  {:pre [(keyword? key)]}
  (let [^function handler (gobj/get attr-handlers (.-fqn key))]
    (if ^boolean handler
      (handler env node oval nval)

      ;; create and store attr handler for later
      ;; avoids doing the same kind of work over and over to figure out what a key meant
      (let [^function handler (make-attr-handler key)]
        (gobj/set attr-handlers (.-fqn key) handler)
        (handler env node oval nval)
        ))))

;; special case "for" -> "htmlFor"
(add-attr :for
  (fn [env ^js node oval nval]
    (set! node -htmlFor nval)))

(add-attr :style
  (fn [env ^js node oval nval]
    (cond
      (and (nil? oval) (nil? nval))
      :empty

      (map? nval)
      (reduce-kv
        (fn [_ ^not-native k v]
          (set-style-property node (-name k) v))
        nil
        nval)

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

(add-attr :dom/ref
  (fn [env node oval nval]
    (cond
      (nil? nval)
      (vreset! oval nil)

      (some? nval)
      (vreset! nval node)

      :else
      nil)))

(add-attr :dom/inner-html
  (fn [env node oval nval]
    (when (seq nval)
      (set! node -innerHTML nval))))

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




