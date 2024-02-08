(ns shadow.arborist.interpreted
  (:require
    [clojure.string :as str]
    [shadow.arborist.fragments :as frag]
    [shadow.arborist.attributes :as a]
    [shadow.arborist.protocols :as p]
    [shadow.arborist.dom-scheduler :as ds]
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

(defn text? [x]
  ;; FIXME: should (nil? x) be in here?
  ;; a probable case is nested hiccup, which leads to a non ideal path
  ;; [:div "foo" (when some-condition [:div "other-nested-hiccup])]
  ;; since on mount it might set textContent
  ;; but on update it may reset that textContent and created the new children
  ;; probably doesn't matter so keeping it for now
  (or (string? x) (number? x) (nil? x)))

;; not using (every? text? form) because it uses seq
;; this is used in the hot path, so trying to keep allocations to a minimum
;; I want the fastest way possible to just check if all items in a hiccup vector are text-ish
;; knowing that we have a vector can speed this up a bit by going direct to -count and -nth
(defn all-text? [offset ^not-native form]
  (let [c (-count form)]
    ;; no children, means no text
    (when (> c offset)
      (loop [idx offset]
        (when (text? (-nth form idx))
          (let [next (inc idx)]
            (if (= next c)
              true
              (recur next))))))))

(comment
  (all-text? 1 [:div])
  (all-text? 1 [:div "foo" nil])
  (all-text? 2 [:div {:id "yo"}])
  (all-text? 2 [:div {:id "yo"} "foo" :bar])
  (all-text? 2 [:div {:id "yo"} :bar "foo"])
  (all-text? 2 [:div {:id "yo"} "foo" "bar"])

  ;; ~13ms
  (time
    (let [v [:div "foo" "bar"]]
      (dotimes [x 1e5]
        (all-text? 1 v)
        )))

  ;; ~36ms
  (time
    ;; using subvec since that is what children will be later
    (let [v (subvec [:div "foo" "bar"] 1)]
      (dotimes [x 1e5]
        (every? text? v)
        )))

  ;; ~17ms
  (time
    ;; FIXME: turns out subvec makes this slow
    ;; maybe get rid of subvec?
    (let [v ["foo" "bar"]]
      (dotimes [x 1e5]
        (every? text? v)
        )))
  )

(deftype ManagedVector
  [env
   ^:mutable form
   node
   ^:mutable attrs
   ^:mutable text-content
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
         ;; nth on form because we know it is a non-empty vector
         ;; get on next because it might be empty
         (keyword-identical? (nth form 0) (get next 0))))

  (dom-sync! [this next]
    ;; only compare identical? to allow skipping some diffs
    ;; must not call = since something is likely different
    ;; but that might be deeply nested leading to a lot of wasted comparisons
    (when-not (identical? form next)
      ;; FIXME: this can be sped up a bit by not using desugar
      ;; class/id from tag kw are not changing, so no need to diff those
      ;; but desugar puts them into a map
      (let [[_ next-attrs next-nodes] (desugar next)]

        (a/merge-attrs env node attrs next-attrs)

        (set! form next)
        (set! attrs next-attrs)

        ;; optimization where all children are text-ish, much faster to just set -textContent
        (if (and text-content (all-text? (if (nil? next-attrs) 1 2) next-nodes))
          (let [text (str/join next-nodes)]
            (when (not= text-content text)
              (set! text-content text)
              (set! node -textContent text)))

          (let [oc (count children)
                nc (count next-nodes)]

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

              (set! children (persistent! next-children)))
            )))))

  (destroy! [this ^boolean dom-remove?]
    (when dom-remove?
      (ds/write!
        ;; remove causes a reflow, which we want to batch together
        ;; so that 3 removals don't cause 3 separate reflows
        ;; microtask still runs before next paint, so no visual difference
        (.remove node)))
    (run! #(p/destroy! % false) children)))

(defn as-managed-vector [this env]
  (let [[tag-kw attrs children]
        (desugar this)

        node
        (js/document.createElement tag-kw)]

    (reduce-kv
      (fn [_ key val]
        (frag/set-attr env node key nil val))
      nil
      attrs)

    (if (all-text? (if (nil? attrs) 1 2) children)
      (let [text (str/join children)]
        (set! node -textContent text)
        (ManagedVector. env this node attrs text [] false))

      (ManagedVector. env this node attrs
        nil
        (into [] (map #(p/as-managed % env)) children)
        false))))

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

(extend-type cljs.core/PersistentVector
  p/IConstruct
  (as-managed [this env]
    (let [tag-kw (nth this 0)]
      (cond
        (keyword-identical? tag-kw :<>)
        (as-managed-fragment this env)

        (simple-keyword? tag-kw)
        (as-managed-vector this env)

        :else
        (throw (ex-info "invalid hiccup form" {:form this}))))))
