(ns shadow.grove.others.react
  (:require-macros [shadow.grove.react])
  (:require
    ["react" :as react]
    [goog.object :as gobj]
    [shadow.grove.protocols :as p]
    [shadow.grove.builder :as b]
    [clojure.string :as str]))

(def react-element-type
  (-> (react/createElement "h1" nil)
      (gobj/get "$$typeof")))

(defn react-element? [^js thing]
  (and (object? thing)
       (identical? (gobj/get thing "$$typeof") react-element-type)))

(defprotocol IConvertToReact
  (as-react-element [val]))

(defn as-css-class [val]
  (cond
    (string? val)
    val

    (map? val)
    (reduce-kv
      (fn [s key val]
        (cond
          (not val)
          s

          (keyword? key)
          (str s " " (name key))

          :else
          (str s " " key)))
      ""
      val)

    (coll? val)
    (->> val
         (remove nil?)
         (str/join " "))

    :else
    (throw (ex-info "invalid :class value" {:val val}))
    ))

(defn as-css-styles [val]
  (cond
    (string? val)
    val

    (map? val)
    (clj->js val)

    (object? val)
    val

    :else
    (throw (ex-info "invalid :style value" {:val val}))
    ))

(defonce custom-el-ref
  (atom {}))

(deftype ElementBuilder [^:mutable current as-fragment]

  cljs.core/IDeref
  (-deref [_] current)

  p/IBuildTrees
  (fragment-start [_ _ _]
    (set! current #js {:fragment true :parent current :children (array)}))

  (fragment-end [_]
    (let [^js el current
          children (.-children el)]

      (assert (.-fragment el))

      (set! current (.-parent el))

      (cond
        ;; react special cases
        (= 1 (alength children))
        (aget children 0)

        (and (nil? current) ;; return root as array if requested
             (not as-fragment))
        children

        :else
        #js {:$$typeof react-element-type
             :type react/Fragment
             :key nil
             :ref nil
             :props #js {:children children}})
      ))

  ;; always called with this, kw, array, array, num, nil or cljs map
  ;; akeys is an array of keywords always
  (element-open [this ^not-native type akeys avals sc ^not-native specials]
    (if-not (nil? (-namespace type))
      (let [handler (get @custom-el-ref type)]
        (when-not handler
          (throw (ex-info "not custom handler installed for type" {:type type})))
        (let [el (handler type akeys avals sc specials)]
          (set! el -parent current)
          (set! current el)))

      (let [props #js {}
            el #js {:$$typeof react-element-type
                    :type (-name type)
                    :parent current
                    :key nil
                    :ref nil
                    :props props
                    :children (array)}

            len (alength akeys)]

        (dotimes [i len]
          (let [key (aget akeys i)
                val (aget avals i)]
            (when (some? val)
              (let [key-s (-name ^not-native key)]
                ;; FIXME: fix CLJS so (gobj/set ...) emits the same code as (js/goog.object.set ...)
                ;; currently it binds args which we don't need and closure won't remove
                (case key-s
                  "ref"
                  (js/goog.object.set el "ref" val)
                  "key"
                  (js/goog.object.set el "key" key)
                  "class"
                  (js/goog.object.set props "className" (as-css-class val))
                  "style"
                  (js/goog.object.set props "style" (as-css-styles val))
                  ;; default
                  (js/goog.object.set props key-s val))))))

        (when-not (nil? specials)
          ;; dynamic attr map
          (when-let [attrs (::attrs specials)]
            (reduce-kv
              (fn [_ ^not-native key val]
                (js/goog.object.set props (-name key) val))
              nil
              attrs)))

        (set! current el))))

  (element-close [_]
    (let [el current
          parent (.-parent el)
          children (.-children el)]

      (case (alength children)
        0 (js/goog.object.set (.-props el) "children" nil)
        1 (js/goog.object.set (.-props el) "children" (aget children 0))
        (js/goog.object.set (.-props el) "children" children))

      ;; FIXME: is delete expensive? react probably doesn't care about these anyways?
      ;; (js-delete el "parent")
      ;; (js-delete el "children")

      ;; element-open/close outside fragment is allowed yes/no?
      (when-not (nil? parent)
        (.. parent -children (push el)))

      (set! current parent)

      el))

  (text [_ val]
    (.. current -children (push val)))

  (interpret [this val]
    (cond
      (nil? val)
      ::skip

      (string? val)
      (p/text this val)

      (number? val)
      (p/text this (str val))

      (array? val)
      (.. current -children (push val))

      (identical? react-element-type (gobj/get val "$$typeof"))
      (.. current -children (push val))

      :else
      (.. current -children (push (as-react-element val)))
      )))

(defn get-props [react-props]
  ;; can't use (.-shadow$props props)
  ;; constructed via #js {} below so will end up as
  ;; {"shadow$props": ...} and won't be renamed
  ;; FIXME: could stick the react-props into meta in case they are needed somehow?
  (gobj/get react-props "shadow$props"))

(defn wrap-fn* [render-fn]
  (fn [props]
    (render-fn
      (get-props props))))

(defonce instance-id-seq (atom 0))

(defn next-id []
  (swap! instance-id-seq inc))

(defn wrap-component*
  [{:keys
    [component-id
     render
     state-update
     initial-state
     use-effect
     use-context
     should-update
     use]
    :or {initial-state {}
         should-update =}
    :as spec}]
  (let [context-fn
        (cond
          (not use-context)
          (constantly {})

          ;; {:foo some-context}
          (map? use-context)
          (fn []
            (reduce-kv
              (fn [ctx key value]
                (assoc ctx key (react/useContext value)))
              {}
              use-context))

          ;; single-context
          :else
          (fn []
            (react/useContext use-context)))

        effect-fn
        (cond
          (nil? use-effect)
          (fn [])

          (fn? use-effect)
          (fn []
            ;; FIXME: macro to figure out what the effect used
            (react/useEffect use-effect (array)))

          (vector? use-effect)
          (fn []
            (doseq [eff use-effect]
              (react/useEffect eff (array)))))

        reducer-fn
        (fn [props]
          (if (fn? initial-state)
            (react/useReducer state-update nil #(initial-state props))
            (react/useReducer state-update initial-state)))

        component-fn
        (if state-update
          (fn [props]
            (let [context
                  (context-fn)

                  [state dispatch]
                  (reducer-fn props)]

              (effect-fn)

              ;; FIXME: todo useLayoutEffect

              ;; FIXME: what if state-update wants access to context?
              ;; making the caller pass it in seems rather manual?
              (render props state dispatch context)))

          ;; stateless component
          (fn [props]
            (let [context
                  (context-fn)]

              (effect-fn)
              ;; FIXME: todo useLayoutEffect

              ;; does it make sense to pass context to handle-event?
              (render props context))))

        component-fn
        (reduce
          (fn [component-fn factory-fn]
            (let [use-fn (factory-fn spec)]
              (fn [props]
                (use-fn props component-fn))))
          component-fn
          use)

        component-fn
        (fn [react-props]
          (let [component-ref
                (react/useRef nil)

                [_ update-trigger]
                (react/useState nil)]

            (when-not (.-current component-ref)
              (set! component-ref -current {::instance-id (next-id)
                                            ::update-trigger update-trigger
                                            ::config spec}))
            (let [props
                  (-> react-props
                      (get-props)
                      (vary-meta assoc ::component (.-current component-ref)))]

              (component-fn props))))]

    (when component-id
      (assert (keyword? component-id)) ;; FIXME: totally ok to allow strings, just can't subs it then
      (gobj/set component-fn "displayName" (-> component-id str (subs 1))))

    (cond
      (or (nil? should-update)
          (true? should-update))
      component-fn

      (fn? should-update)
      (react/memo
        component-fn
        (fn [prev next]
          (should-update (get-props prev) (get-props next))))

      :else
      (throw (ex-info "invalid value for :initial-state, should be true or fn" spec))
      )))

(defn component-fn [^js type]
  (or (.-shadow$type type)
      (let [wrapped-fn
            (cond
              (fn? type)
              (wrap-fn* type)
              (map? type)
              (wrap-component* type)
              :else
              (throw (ex-info "unexpected component type, should be map or fn" {:type type})))]

        (set! type -shadow$type wrapped-fn)
        wrapped-fn)))

(defn render [^clj type props]
  {:pre [(or (fn? type)
             (map? type))
         (map? props)]}

  (let [wrapped-type (component-fn type)]

    #js {:$$typeof react-element-type
         :type wrapped-type
         :key (:key props)
         :ref (:ref props)
         :props #js {:shadow$props (vary-meta props assoc ::component type)
                     :children nil}}))

;; nice looking alias for render
(defn >> [type props]
  (render type props))

(defn fragment? [thing]
  (and (react-element? thing)
       (identical? react/Fragment (.-type ^js thing))))

(defn unwrap-fragment [thing]
  (if-not (fragment? thing)
    thing
    (.. ^js thing -props -children)))

(defn js-el* [type props key ref]
  #js {:$$typeof react-element-type
       :type type
       :key key
       :ref ref
       :props props})

(defn render-seq [items key-fn item-fn]
  {:pre [(sequential? items)
         (ifn? key-fn)
         (fn? item-fn)]}

  (let [result (array)]
    (reduce
      (fn [idx item]
        (let [key (if key-fn (key-fn item) idx)
              ^js el (item-fn item idx)]

          (assert (react-element? el))

          ;; beware: mutating an already constructed react element
          ;; so the inner code doesn't have to deal with key logic
          (set! el -key key)

          (.push result el)
          (inc idx)))
      0
      items)

    result))

(defn create-context [init-value]
  (react/createContext init-value))

(defn use-context [ctx]
  (react/useContext ctx))

(defn provide [^js context value body]
  #js {:$$typeof react-element-type
       :type (.-Provider context)
       :key nil
       :ref nil
       :props #js {:value value
                   :children (unwrap-fragment body)}})