(ns shadow.arborist.interpreted
  (:require
    [clojure.string :as str]
    [shadow.arborist.attributes :as attr]
    [shadow.arborist.collections :as coll]
    [shadow.cljs.modern :refer (defclass)]
    [shadow.arborist.fragments :as frag]
    [shadow.arborist.attributes :as a]
    [shadow.arborist.protocols :as p]
    [shadow.arborist.dom-scheduler :as ds]
    [shadow.arborist.common :as common]))

(deftype TagInfo [tag tag-id tag-class attr-class attrs child-offset]
  Object
  (with-form-info [this attr-class attrs child-offset]
    (TagInfo. tag tag-id tag-class attr-class attrs child-offset)))

(defn parse-tag* [spec]
  (let [spec (name spec)
        fdot (.indexOf spec ".")
        fhash (.indexOf spec "#")]
    (cond
      (and (identical? -1 fdot) (identical? -1 fhash))
      (->TagInfo spec nil nil nil nil nil)

      (identical? -1 fhash)
      (->TagInfo
        (subs spec 0 fdot)
        nil
        (str/replace (subs spec (inc fdot)) #"\." " ")
        nil
        nil
        nil)

      (identical? -1 fdot)
      (->TagInfo
        (subs spec 0 fhash)
        (subs spec (inc fhash))
        nil
        nil
        nil
        nil)

      (> fhash fdot)
      (throw (str "cant have id after class?" spec))

      :else
      (->TagInfo
        (subs spec 0 fhash)
        (subs spec (inc fhash) fdot)
        (str/replace (.substring spec (inc fdot)) #"\." " ")
        nil
        nil
        nil))))

(def tag-cache #js {})

(defn parse-tag ^TagInfo [^keyword spec]
  (js* "(~{} || ~{})"
    (unchecked-get tag-cache (.-fqn spec))
    (unchecked-set tag-cache (.-fqn spec) (parse-tag* spec))))

(defn desugar ^TagInfo [^not-native hiccup]
  (let [tag-kw (-nth hiccup 0)
        ^TagInfo tag-info (parse-tag tag-kw)
        ^not-native attrs (-lookup hiccup 1)]

    (if (map? attrs)
      (if-some [class-attr (-find attrs :class)]
        (.with-form-info tag-info (val class-attr) (dissoc attrs :class) 2)
        (.with-form-info tag-info nil attrs 2))
      (.with-form-info tag-info nil nil 1))))

(defn text? [x]
  (or (string? x) (number? x)))

(defn single-child [^not-native hiccup ^TagInfo tag-info]
  (let [co (.-child-offset tag-info)]
    (if (= (-count hiccup) (inc co))
      (-nth hiccup co)
      false)))

(defclass ManagedVector
  (field ^not-native env)
  (field ^not-native ^:mutable form)
  (field ^js node)
  (field ^TagInfo ^:mutable tag-info)
  (field ^:mutable text-content nil)
  (field ^not-native ^:mutable children)
  (field ^boolean ^:mutable entered?)

  (constructor [this ^not-native parent-env ^not-native hiccup]
    (set! this -env parent-env)
    (set! this -form hiccup)

    (let [^TagInfo tag-info
          (desugar hiccup)

          node
          (js/document.createElement (.-tag tag-info))

          tag-class
          (.-tag-class tag-info)

          attr-class
          (.-attr-class tag-info)

          children
          (subvec hiccup (.-child-offset tag-info))]

      (set! this -node node)
      (set! this -tag-info tag-info)

      (cond
        ;; [:div.foo {:class "bar"}]
        (and tag-class attr-class)
        (frag/set-attr env node :class nil [tag-class attr-class])

        ;; [:div.foo]
        tag-class
        (set! node -className tag-class)

        ;; [:div {:class "foo"}]
        attr-class
        (frag/set-attr env node :class nil attr-class))

      (when-some [tag-id (.-tag-id tag-info)]
        (set! node -id tag-id))

      (reduce-kv
        (fn [_ key val]
          (frag/set-attr env node key nil val))
        nil
        (.-attrs tag-info))

      ;; optimize single text child pass
      ;; textContent much faster than createText/appendChild
      (let [child (single-child hiccup tag-info)]

        (cond
          (false? child)
          (do (set! text-content nil)
              (set! this -children (into [] (map #(p/as-managed % env)) children)))

          (text? child)
          (let [text (str child)]
            (set! node -textContent text)
            (set! this -children [])
            (set! text-content text))

          :else
          (do (set! text-content nil)
              (set! this -children [(p/as-managed child env)]))
          )))

    this)

  p/IManaged
  (dom-first [this] node)

  (dom-insert [this parent anchor]
    (run! #(p/dom-insert ^not-native % node nil) children)
    (.insertBefore parent node anchor))

  (dom-entered! [this]
    (run! #(p/dom-entered! ^not-native %) children)
    (set! entered? true))

  (supports? [this next]
    (and (vector? next)
         ;; nth on form because we know it is a non-empty vector
         ;; get on next because it might be empty
         (keyword-identical? (-nth form 0) (-lookup next 0))))

  (dom-sync! [this ^not-native next]
    ;; = performs a deep comparison, so for changes nested deeply this ends
    ;; up comparing things over and over again
    ;; identical? here saves a bit of work, but in practice is not the bottleneck
    ;; = may also find the subtrees that have not changed, but are also not identical
    ;; thus ending up faster for those cases
    (when-not (= form next)
      (let [^TagInfo next-info (desugar next)

            attrs (.-attrs tag-info)
            next-attrs (.-attrs next-info)
            next-nodes (subvec next (.-child-offset next-info))]

        (when (not= (.-attr-class tag-info)
                    (.-attr-class next-info))
          (if (.-tag-class tag-info)
            (a/set-attr env node :class
              [(.-tag-class tag-info) (.-attr-class tag-info)]
              [(.-tag-class next-info) (.-attr-class next-info)])
            (a/set-attr env node :class
              (.-attr-class tag-info)
              (.-attr-class next-info))))

        (when (not= attrs next-attrs)
          (a/merge-attrs env node attrs next-attrs))

        (set! form next)
        (set! tag-info next-info)

        ;; optimization where single child is text-ish, much faster to just set -textContent
        ;; not checking multiple children since that ends up becoming more expensive
        (let [child (single-child next next-info)]

          ;; FIXME: cljs.core/and generates really ugly code for some reason
          (if ^boolean (js* "(~{} && ~{})" (string? text-content) (text? child))
            ;; can update single text
            (let [text (str child)]
              (when (not= text-content text)
                (set! text-content text)
                (set! node -textContent text)))

            ;; sync children normally
            (let [oc (-count children)
                  nc (-count next-nodes)]

              ;; could be syncing a text-only body with something that no longer is
              ;; wipe all text, replace with regular managed children
              (when text-content
                (set! node -textContent "")
                (set! text-content nil))

              ;; update previous children
              (let [next-children
                    (reduce-kv
                      (fn [c idx ^not-native child]
                        (if (>= idx nc)
                          (do (p/destroy! child true)
                              c)
                          (let [next (nth next-nodes idx)]
                            (if (p/supports? child next)
                              (do (p/dom-sync! child next)
                                  (conj! c child))
                              (let [first (p/dom-first child)
                                    new-managed (p/as-managed next env)]
                                (p/dom-insert new-managed node first)
                                (p/destroy! child true)
                                (when entered?
                                  (p/dom-entered! new-managed))
                                (conj! c new-managed))))))
                      (transient [])
                      children)

                    ;; append if there were more children
                    next-children
                    (if-not (> nc oc)
                      next-children
                      (reduce
                        (fn [c el]
                          (let [new-managed (p/as-managed el env)]
                            (p/dom-insert new-managed node nil)
                            (when entered?
                              (p/dom-entered! new-managed))
                            (conj! c new-managed)))
                        next-children
                        (subvec next-nodes oc)))]

                (set! children (persistent! next-children))))
            ))))
    js/undefined)

  (destroy! [this ^boolean dom-remove?]
    (when dom-remove?
      (ds/write!
        ;; remove causes a reflow, which we want to batch together
        ;; so that 3 removals don't cause 3 separate reflows
        ;; microtask still runs before next paint, so no visual difference
        (.remove node)))
    (run! #(p/destroy! % false) children)))

(deftype ManagedFragment
  [env
   ^:mutable children
   ^:mutable marker-start
   ^:mutable marker-end
   ^:mutable entered?]

  p/IManaged
  (dom-first [this]
    marker-start)

  (dom-insert [this parent anchor]
    (.insertBefore parent marker-end anchor)
    (.insertBefore parent marker-start marker-end)
    (run! (fn [^not-native child] (p/dom-insert child parent marker-end)) children))

  (dom-entered! [this]
    (run! (fn [^not-native child] (p/dom-entered! child)) children)
    (set! entered? true))

  (supports? [this next]
    (and (vector? next)
         (keyword-identical? (get next 0) :<>)))

  (dom-sync! [this next]
    (let [next-nodes (subvec next 1)

          oc (count children)
          nc (count next-nodes)

          node
          (.-parentNode marker-start)

          next-children
          (reduce-kv
            (fn [c idx ^not-native child]
              (if (>= idx nc)
                (do (p/destroy! child true)
                    c)
                (let [next (nth next-nodes idx)]
                  (if (p/supports? child next)
                    (do (p/dom-sync! child next)
                        (conj! c child))
                    (let [first (p/dom-first child)
                          new-managed (p/as-managed next env)]
                      (p/dom-insert new-managed node first)
                      (p/destroy! child true)
                      (when entered?
                        (p/dom-entered! new-managed))
                      (conj! c new-managed))))))
            (transient [])
            children)

          ;; append if there were more children
          next-children
          (if-not (> nc oc)
            next-children
            (reduce
              (fn [c el]
                (let [new-managed (p/as-managed el env)]
                  (p/dom-insert new-managed node marker-end)
                  (when entered?
                    (p/dom-entered! new-managed))
                  (conj! c new-managed)))
              next-children
              (subvec next-nodes oc)))]

      (set! children (persistent! next-children))))

  (destroy! [this ^boolean dom-remove?]
    (when dom-remove?
      (.remove marker-start)
      (.remove marker-end))

    (run! (fn [^not-native child] (p/destroy! child dom-remove?)) children)
    ))

(defn as-managed-fragment [vec env]
  (let [children (into [] (map #(p/as-managed % env)) (subvec vec 1))]
    (ManagedFragment.
      env
      children
      (common/dom-marker env)
      (common/dom-marker env)
      false)))

(attr/add-attr :dom/key
  ;; never needs to be added to the dom
  (fn [env node oval nval]))

(defn get-dom-key [hiccup]
  (let [attrs (get hiccup 1)]
    (when (map? attrs)
      (:dom/key attrs))))

(defn construct-hiccup-seq [env the-seq]
  (if (zero? (bounded-count 1 the-seq))
    (common/managed-text env nil)
    (let [head (first the-seq)]
      (if-not (vector? head)
        (throw (ex-info "cannot construct non-hiccup lazy seq" {:seq the-seq}))
        (if (get-dom-key head)
          ;; first item has key, assume all do
          (coll/construct-keyed-seq env (vec the-seq) get-dom-key identity)
          ;; no key, assume simple-seq
          (coll/construct-simple-seq env (vec the-seq) identity)
          )))))

(extend-protocol p/IConstruct
  cljs.core/PersistentVector
  (as-managed [this env]
    (let [tag-kw (nth this 0)]
      (cond
        (keyword-identical? tag-kw :<>)
        (as-managed-fragment this env)

        (simple-keyword? tag-kw)
        (ManagedVector. env this)

        :else
        (throw (ex-info "invalid hiccup form" {:form this})))))

  cljs.core/LazySeq
  (as-managed [this env]
    (construct-hiccup-seq env this)))