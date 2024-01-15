(ns shadow.arborist.fragments
  (:require-macros [shadow.arborist.fragments])
  (:require
    [shadow.cljs.modern :refer (defclass)]
    [shadow.arborist.protocols :as p]
    [shadow.arborist.attributes :as a]
    [shadow.arborist.common :as common]))

(defn fragment-id
  ;; https://github.com/google/closure-compiler/wiki/Id-Generator-Annotations
  {:jsdoc ["@idGenerator {consistent}"]}
  [s]
  s)

(defn array-equiv [a b]
  (let [al (alength a)
        bl (alength b)]
    ;; FIXME: identical? wouldn't work in CLJ, but = is slower in CLJS
    (when (identical? al bl)
      (loop [i 0]
        (if (identical? i al)
          true
          (when (= (aget a i) (aget b i))
            (recur (inc i))))))))

(def svg-ns "http://www.w3.org/2000/svg")

;; FIXME: maybe take document from env, easier to mock out later
(defn svg-element-fn [^Keyword type]
  (js/document.createElementNS svg-ns (.-name type)))

(defn dom-element-fn [^Keyword type]
  (js/document.createElement (.-name type)))

(defn get-element-fn [env element-ns]
  (if (identical? element-ns svg-ns)
    svg-element-fn
    dom-element-fn))

(deftype FragmentCode [create-fn mount-fn update-fn destroy-fn])

(declare ^{:arglists '([thing])} fragment-init?)

(defclass ManagedFragment
  (field env)
  (field ^FragmentCode code)
  (field vals)
  (field marker)
  (field exports)
  (field ^boolean dom-entered?)

  (constructor [this init-env ^FragmentCode init-code init-vals element-ns]
    (let [element-fn (if (nil? element-ns) (:dom/element-fn init-env) (get-element-fn init-env element-ns))
          init-env (cond-> init-env (some? element-ns) (assoc :dom/element-fn element-fn :dom/svg true))]

      (set! env (assoc init-env ::fragment this))
      (set! code init-code)
      (set! vals init-vals)
      (set! marker (common/dom-marker env))

      ;; create-fn creates all necessary nodes but only exports those that will be accessed later in an array
      ;; this might be faster if create-fn just closed over locals and returns the callbacks to be used later
      ;; svelte does this but CLJS doesn't allow to set! locals so it would require ugly js* code to make it work
      ;; didn't benchmark but the array variant shouldn't be that much slower. maybe even faster since
      ;; the functions don't need to be recreated for each fragment instance
      (set! exports (.. code (create-fn env vals element-fn)))))

  p/IManaged
  (dom-first [this] marker)

  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor)
    (. code (mount-fn exports parent anchor)))

  (dom-entered! [this]
    ;; FIXME: maybe create fn in macro that saves traversing exports
    ;; exports may contain many regular dom nodes and those don't need this
    ;; but this is called once in the entire lifecycle so this should be fine
    (set! dom-entered? true)
    (.forEach exports
      (fn [item]
        (when (implements? p/IManaged item)
          (p/dom-entered! item)
          ))))

  (supports? [this ^FragmentInit next]
    (and (fragment-init? next)
         (identical? code (.-code next))))

  (dom-sync! [this ^FragmentInit next]
    (let [nvals (.-vals next)]
      (.. code (update-fn this env exports vals nvals))
      (set! vals nvals))
    :synced)

  (destroy! [this ^boolean dom-remove?]
    (when dom-remove?
      (.remove marker))

    (. code (destroy-fn env exports vals dom-remove?))))

(deftype FragmentInit [vals element-ns ^FragmentCode code]
  p/IConstruct
  (as-managed [_ env]
    (ManagedFragment. env code vals element-ns))

  IEquiv
  (-equiv [this ^FragmentInit other]
    (and (instance? FragmentInit other)
         (identical? code (. other -code))
         (array-equiv vals (.-vals other)))))

(defn fragment-init? [thing]
  (instance? FragmentInit thing))

(defn has-no-lazy-seqs? [vals]
  (every? #(not (instance? cljs.core/LazySeq %)) vals))

(defn fragment-init [vals element-ns code]
  (assert (has-no-lazy-seqs? vals)  "no lazy seqs allowed in fragments")
  (FragmentInit. vals element-ns code))

;; for fallback code, relying on registry
(def ^{:jsdoc ["@dict"]} known-fragments #js {})

(defn reset-known-fragments! []
  (set! known-fragments #js {}))

(defn create-text
  ;; {:jsdoc ["@noinline"]}
  [env text]
  (js/document.createTextNode text))

(defn set-attr [env node key oval nval]
  (a/set-attr env node key oval nval))


(defn append-child
  ;; {:jsdoc ["@noinline"]}
  [parent child]
  (.appendChild parent child))

(defn managed-create [env other]
  ;; FIXME: validate that return value implements the proper protocols
  (p/as-managed other env))

;; called by macro generated code
(defn managed-append [parent other]
  (when-not (satisfies? p/IManaged other)
    (throw (ex-info "cannot append-managed" {:parent parent :other other})))
  (p/dom-insert other parent nil))

(defn managed-insert [component parent anchor]
  (p/dom-insert component parent anchor))

(defn managed-remove [component dom-remove?]
  (p/destroy! component dom-remove?))

;; called by macro generated code
(defn update-managed [^ManagedFragment fragment env nodes idx oval nval]
  ;; not comparing oval/nval because impls can do that if needed
  (let [^not-native el (aget nodes idx)]
    (if ^boolean (p/supports? el nval)
      (p/dom-sync! el nval)
      (let [next (common/replace-managed env el nval)]
        (aset nodes idx next)
        (when ^boolean (.-dom-entered? fragment)
          (p/dom-entered! next))))))

;; called by macro generated code
(defn update-attr [env nodes idx ^not-native attr oval nval]
  ;; FIXME: should maybe move the comparisons to the actual impls?
  (when (not= oval nval)
    (let [el (aget nodes idx)]
      (set-attr env el attr oval nval))))

(defn clear-attr [env nodes idx attr oval]
  (let [node (aget nodes idx)]
    (a/set-attr env node attr oval nil)))

;; just so the macro doesn't have to use dot interop
;; will likely be inlined by closure anyways
(defn dom-insert-before [^js parent node anchor]
  (.insertBefore parent node anchor))

(defn dom-remove [^js node]
  (.remove node))

(defn css-join [from-el from-attrs]
  [from-el from-attrs])

