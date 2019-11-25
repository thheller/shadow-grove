(ns shadow.experiments.arborist.interpreted
  (:require [shadow.experiments.arborist.fragments :as frag]
            [shadow.experiments.arborist.protocols :as p]
            [clojure.string :as str]))

(deftype ManagedVector
  [env
   ^:mutable node
   ^:mutable tag-kw
   ^:mutable attrs
   ^:mutable children
   ^:mutable src]

  p/IManageNodes
  (dom-first [this] node)
  (dom-insert [this parent anchor]
    (run! #(p/dom-insert % node nil) children)
    (.insertBefore parent node anchor))

  p/IUpdatable
  (supports? [this next]
    (and (vector? next)
         (keyword-identical? tag-kw (get next 0))))

  (dom-sync! [this [_ next-attrs :as next]]
    ;; FIXME: could be optimized
    (let [[next-attrs next-nodes]
          (if (map? next-attrs)
            [attrs (subvec next 2)]
            [nil (subvec next 1)])]

      (reduce-kv
        (fn [_ key nval]
          (let [oval (get attrs key)]
            (when (not= nval oval)
              (frag/set-attr env node key oval nval))))
        nil
        next-attrs)

      ;; {:a 1 :x 1} vs {:a 1}
      ;; {:a 1} vs {:b 1}
      ;; should be uncommon but need to unset props that are no longer used
      (reduce-kv
        (fn [_ key oval]
          (when-not (contains? next-attrs key)
            (frag/set-attr env node key oval nil)))
        nil
        attrs)

      (let [oc (count children)
            nc (count next-nodes)

            ;; FIXME: transient?
            ;; update previous children
            next-children
            (reduce-kv
              (fn [c idx child]
                (if (>= idx nc)
                  (do (p/destroy! child)
                      c)
                  (let [next (nth next-nodes idx)]
                    (if (p/supports? child next)
                      (do (p/dom-sync! child next)
                          (conj c child))
                      (let [first (p/dom-first child)
                            new-managed (p/as-managed next env)]
                        (p/dom-insert new-managed node first)
                        (p/destroy! child)
                        (conj c new-managed))))))
              []
              children)

            ;; append if there were more children
            next-children
            (if-not (> nc oc)
              next-children
              (reduce
                (fn [c el]
                  (let [new-managed (p/as-managed el env)]
                    (p/dom-insert new-managed node nil)
                    (conj c new-managed)))
                next-children
                (subvec next-nodes oc)))]

        (set! children next-children)
        (set! attrs next-attrs)
        (set! src next))))

  p/IDestructible
  (destroy! [this]
    (.remove node)
    (run! #(p/destroy! %) children)))

(defn just-tag [kw]
  (let [s (name kw)]
    (if-let [idx (str/index-of s "#")]
      (subs s 0 idx)
      (if-let [idx (str/index-of s ".")]
        (subs s 0 idx)
        s))))

(extend-type cljs.core/PersistentVector
  p/IConstruct
  (as-managed [[tag-kw attrs :as this] env]
    (let [[attrs children]
          (if (map? attrs)
            [attrs (subvec this 2)]
            [nil (subvec this 1)])

          node
          (js/document.createElement (just-tag tag-kw))

          ;; FIXME: support #id.class properly

          children
          (into [] (map #(p/as-managed % env)) children)]

      (reduce-kv
        (fn [_ key val]
          (frag/set-attr env node key nil val))
        nil
        attrs)

      (ManagedVector. env node tag-kw attrs children this))))
