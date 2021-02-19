(ns shadow.experiments.arborist.interpreted
  (:require
    [shadow.experiments.arborist.fragments :as frag]
    [shadow.experiments.arborist.attributes :as a]
    [shadow.experiments.arborist.protocols :as p]
    [clojure.string :as str]
    [shadow.experiments.arborist.common :as common]))

(deftype ManagedVector
  [env
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
    ;; FIXME: could be optimized
    (let [[next-attrs next-nodes]
          (if (map? next-attrs)
            [attrs (subvec next 2)]
            [nil (subvec next 1)])]

      (a/merge-attrs env node attrs next-attrs)

      (let [oc (count children)
            nc (count next-nodes)

            ;; FIXME: transient?
            ;; update previous children
            next-children
            (reduce-kv
              (fn [c idx ^not-native child]
                (if (>= idx nc)
                  (do (p/destroy! child)
                      c)
                  (let [next (nth next-nodes idx)]
                    (if (p/supports? child next)
                      (do (p/dom-sync! child next)
                          (conj! c child))
                      (let [first (p/dom-first child)
                            new-managed (p/as-managed next env)]
                        (p/dom-insert new-managed node first)
                        (p/destroy! child)
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

        (set! children (persistent! next-children))
        (set! attrs next-attrs))))

  (destroy! [this]
    (.remove node)
    (run! #(p/destroy! %) children)))

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

(defn as-managed-vector [tag-kw attrs this env]
  (let [[attrs children]
        (if (map? attrs)
          [attrs (subvec this 2)]
          [nil (subvec this 1)])

        [tag html-id html-class]
        (parse-tag tag-kw)

        attrs
        (-> attrs
            (cond->
              html-id
              (assoc :id html-id)

              html-class
              (maybe-css-join html-class)))

        node
        (js/document.createElement tag)

        children
        (into [] (map #(p/as-managed % env)) children)]

    (reduce-kv
      (fn [_ key val]
        (frag/set-attr env node key nil val))
      nil
      attrs)

    (ManagedVector. env node tag-kw attrs children false)))

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
    (run! #(p/dom-insert % parent marker-end) children))

  (dom-entered! [this]
    (run! #(p/dom-entered! %) children)
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
                (do (p/destroy! child)
                    c)
                (let [next (nth next-nodes idx)]
                  (if (p/supports? child next)
                    (do (p/dom-sync! child next)
                        (conj! c child))
                    (let [first (p/dom-first child)
                          new-managed (p/as-managed next env)]
                      (p/dom-insert new-managed node first)
                      (p/destroy! child)
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

  (destroy! [this]
    (.remove marker-start)
    (run! #(p/destroy! %) children)
    (.remove marker-end)))

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

  (destroy! [this]
    (p/destroy! delegate)))

(extend-type cljs.core/PersistentVector
  p/IConstruct
  (as-managed [[tag-kw attrs :as this] env]
    (cond
      (keyword-identical? tag-kw :<>)
      (as-managed-fragment this env)

      (keyword? tag-kw)
      (as-managed-vector tag-kw attrs this env)

      :else ;; FIXME: don't blindly assume callable, check
      (let [res (apply tag-kw (subvec this 1))
            ;; FIXME: variant 2, res could be a function which we need to call
            man (p/as-managed res env)]
        (ManagedComponent. env tag-kw man)))))
