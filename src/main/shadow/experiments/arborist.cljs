(ns shadow.experiments.arborist
  {:doc "Arborists generally focus on the health and safety of individual plants and trees."
   :definition "https://en.wikipedia.org/wiki/Arborist"}
  (:require-macros
    [shadow.experiments.arborist]
    [shadow.experiments.arborist.fragments])
  (:require
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.arborist.fragments :as frag]
    [shadow.experiments.arborist.attributes :as attr]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.collections :as coll]
    [goog.async.nextTick]))

(deftype TreeRoot [container ^:mutable env ^:mutable root]
  p/IDirectUpdate
  (update! [this next]
    (if root
      (p/update! root next)
      (let [new-root (common/managed-root env)]
        (set! root new-root)
        (p/update! root next)
        (p/dom-insert root container nil)
        (p/dom-entered! root)
        )))

  Object
  (destroy! [this]
    (when root
      (p/destroy! root))))

(defn dom-root
  ([container env]
   {:pre [(common/in-document? container)]}
   (let [root (TreeRoot. container nil nil)
         root-env (assoc env ::root root)]
     (set! (.-env root) root-env)
     root))
  ([container env init]
   (doto (dom-root container env)
     (p/update! init))))

(defn << [& body]
  (throw (ex-info "<< can only be used a macro" {})))

(defn <> [& body]
  (throw (ex-info "<> can only be used a macro" {})))

(defn fragment [& body]
  (throw (ex-info "fragment can only be used a macro" {})))

(defn render-seq [coll key-fn render-fn]
  (when (some? coll)
    (coll/node coll key-fn render-fn)))

(defn update! [x next]
  (p/update! x next))

(defn destroy! [root]
  (p/destroy! root))
