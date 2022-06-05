(ns shadow.arborist.interpreted
  "EXTREMELY SLOW! DO NOT USE!"
  (:require
    [shadow.arborist.fragments :as frag]
    [shadow.arborist.attributes :as a]
    [shadow.arborist.protocols :as p]
    [clojure.string :as str]
    [shadow.arborist.common :as common]))

(defn parse-tag* [spec]
  (let [spec (name spec)
        fdot (.indexOf spec ".")
        fhash (.indexOf spec "#")]
    (cond
      (and (= -1 fdot) (= -1 fhash))
      [spec nil nil]

      (= -1 fhash)
      [(subs spec 0 fdot)
       nil
       (str/replace (subs spec (inc fdot)) #"\." " ")]

      (= -1 fdot)
      [(subs spec 0 fhash)
       (subs spec (inc fhash))
       nil]

      (> fhash fdot)
      (throw (str "cant have id after class?" spec))

      :else
      [(subs spec 0 fhash)
       (subs spec (inc fhash) fdot)
       (str/replace (.substring spec (inc fdot)) #"\." " ")])))

(defn maybe-css-join [{:keys [class] :as attrs} html-class]
  (if-not class
    (assoc attrs :class html-class)
    (assoc attrs :class (frag/css-join html-class class))))

(def tag-cache #js {})

(defn parse-tag [^keyword spec]
  (js* "(~{} || ~{})"
    (unchecked-get tag-cache (.-fqn spec))
    (unchecked-set tag-cache (.-fqn spec) (parse-tag* spec))))

(defn merge-tag-attrs [tag-kw attrs]
  (let [[tag html-id html-class]
        (parse-tag tag-kw)]

    [tag
     (-> attrs
         (cond->
           html-id
           (assoc :id html-id)

           html-class
           (maybe-css-join html-class)))]))

(defn desugar [[tag-kw attrs :as form]]
  (let [[attrs children]
        (if (map? attrs)
          [attrs (subvec form 2)]
          [nil (subvec form 1)])

        [tag attrs]
        (merge-tag-attrs tag-kw attrs)]

    [tag attrs children]))

(deftype ManagedVector
  [env
   ^:mutable form
   ^:mutable node
   ^:mutable tag-kw
   ^:mutable attrs
   ^:mutable children
   ^:mutable entered?]

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
         (keyword-identical? tag-kw (get next 0))))

  (dom-sync! [this [_ next-attrs :as next]]
    ;; only compare identical? to allow skipping some diffs
    ;; must not call = since something is likely different
    ;; but that might be deeply nested leading to a lot of wasted comparisons

    (when-not (identical? form next)
      (let [[_ next-attrs next-nodes] (desugar next)]

        (a/merge-attrs env node attrs next-attrs)

        (let [oc (count children)
              nc (count next-nodes)

              ;; update previous children
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
                      (p/dom-insert new-managed node nil)
                      (when entered?
                        (p/dom-entered! new-managed))
                      (conj! c new-managed)))
                  next-children
                  (subvec next-nodes oc)))]

          (set! form next)
          (set! children (persistent! next-children))
          (set! attrs next-attrs)))))

  (destroy! [this ^boolean dom-remove?]
    (when dom-remove?
      (.remove node))
    (run! #(p/destroy! % dom-remove?) children)))

(defn as-managed-vector [this env]
  (let [[tag-kw attrs children]
        (desugar this)

        node
        (js/document.createElement (name tag-kw))

        children
        (into [] (map #(p/as-managed % env)) children)]

    (reduce-kv
      (fn [_ key val]
        (frag/set-attr env node key nil val))
      nil
      attrs)

    (ManagedVector. env this node tag-kw attrs children false)))

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

(deftype ManagedComponent
  [env
   ^:mutable type
   ^not-native ^:mutable delegate]

  p/IManaged
  (dom-first [this]
    (p/dom-first delegate))

  (dom-insert [this parent anchor]
    (p/dom-insert delegate parent anchor))

  (dom-entered! [this]
    (p/dom-entered! delegate))

  (supports? [this next]
    (identical? type (nth next 0)))

  (dom-sync! [this next]
    (let [res (apply type (subvec next 1))]
      (p/dom-sync! delegate res)))

  (destroy! [this ^boolean dom-remove?]
    (p/destroy! delegate dom-remove?)))

(extend-type cljs.core/PersistentVector
  p/IConstruct
  (as-managed [this env]
    (let [tag-kw (nth this 0)]
      (cond
        (keyword-identical? tag-kw :<>)
        (as-managed-fragment this env)

        (keyword? tag-kw)
        (as-managed-vector this env)

        :else ;; FIXME: don't blindly assume callable, check
        (let [res (apply tag-kw (subvec this 1))
              ;; FIXME: variant 2, res could be a function which we need to call
              man (p/as-managed res env)]
          (ManagedComponent. env tag-kw man))))))
