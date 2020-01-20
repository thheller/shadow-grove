(ns shadow.experiments.arborist.fragments
  (:require
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.arborist.attributes :as a]
    [shadow.experiments.arborist.common :as common]))

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

(deftype FragmentCode
  [create-fn
   mount-fn
   update-fn
   destroy-fn])

(declare ^{:arglists '([thing])} fragment-node?)

(deftype ManagedFragment
  [env
   ^FragmentCode code
   ^:mutable vals
   marker
   exports
   ^boolean ^:mutable dom-entered?]

  p/IManageNodes
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
        (when (implements? p/IManageNodes item)
          (p/dom-entered! item)
          ))))

  p/IUpdatable
  (supports? [this ^FragmentNode next]
    (and (fragment-node? next)
         (identical? code (.-code next))))

  (dom-sync! [this ^FragmentNode next]
    (let [nvals (.-vals next)]
      (.. code (update-fn this env exports vals nvals))
      (set! vals nvals))
    :synced)

  p/IDestructible
  (destroy! [this]
    (.remove marker)
    (. code (destroy-fn exports))
    (set! (.-length exports) 0)))

(deftype FragmentNode [vals element-ns ^FragmentCode code]
  p/IConstruct
  (as-managed [_ env]
    (let [env (cond-> env element-ns (assoc ::element-ns element-ns))
          ;; create-fn creates all necessary nodes but only exports those that will be accessed later in an array
          ;; this might be faster if create-fn just closed over locals and returns the callbacks to be used later
          ;; svelte does this but CLJS doesn't allow to set! locals so it would require ugly js* code to make it work
          ;; didn't benchmark but the array variant shouldn't be that much slower. maybe even faster since
          ;; the functions don't need to be recreated for each fragment instance
          exports (.. code (create-fn env vals))]
      (ManagedFragment. env code vals (common/dom-marker env) exports false)))

  IEquiv
  (-equiv [this ^FragmentNode other]
    (and (instance? FragmentNode other)
         (identical? code (. other -code))
         (array-equiv vals (.-vals other)))))

(defn fragment-node? [thing]
  (instance? FragmentNode thing))

(defn has-no-lazy-seqs? [vals]
  (every? #(not (instance? cljs.core/LazySeq %)) vals))

(defn fragment-node [vals element-ns code]
  (assert (has-no-lazy-seqs? vals)  "no lazy seqs allowed in fragments")
  (FragmentNode. vals element-ns code))

;; for fallback code, relying on registry
(def ^{:jsdoc ["@dict"]} known-fragments #js {})

;; accessed by macro, do not remove
;; FIXME: what about mathml?
(def svg-ns "http://www.w3.org/2000/svg")

;; FIXME: should maybe take ::document from env
;; not sure under which circumstance this would ever need a different document instance though
(defn create-element
  ;; inlined version is longer than the none inline version
  ;; {:jsdoc ["@noinline"]}
  {:jsdoc ["@return {Element}"]}
  [env ^string element-ns ^Keyword type] ;; kw
  (if (nil? element-ns)
    (js/document.createElement (.-name type))
    (js/document.createElementNS element-ns (.-name type))
    ))

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
  (when-not (satisfies? p/IUpdatable other)
    (throw (ex-info "cannot append-managed" {:parent parent :other other})))
  (p/dom-insert other parent nil))

(defn managed-insert [component parent anchor]
  (p/dom-insert component parent anchor))

(defn managed-remove [component]
  (p/destroy! component))

;; called by macro generated code
(defn update-managed [^ManagedFragment fragment env nodes idx oval nval]
  ;; not comparing oval/nval because impls can do that if needed
  (let [^not-native el (aget nodes idx)]
    (if (p/supports? el nval)
      (p/dom-sync! el nval)
      (let [next (common/replace-managed env el nval)]
        (aset nodes idx next)
        (when (.-dom-entered? fragment)
          (p/dom-entered! next))))))

;; called by macro generated code
(defn update-attr [env nodes idx ^not-native attr oval nval]
  ;; FIXME: should maybe move the comparisons to the actual impls?
  (when (not= oval nval)
    (let [el (aget nodes idx)]
      (set-attr env el attr oval nval))))

;; just so the macro doesn't have to use dot interop
;; will likely be inlined by closure anyways
(defn dom-insert-before [^js parent node anchor]
  (.insertBefore parent node anchor))

(defn dom-remove [^js node]
  (.remove node))

(defn css-join [from-el from-attrs]
  [from-el from-attrs])