(ns shadow.experiments.arborist.fragments
  (:require
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.arborist.attributes :as a]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.components :as comp]))


(defn fragment-id
  ;; https://github.com/google/closure-compiler/wiki/Id-Generator-Annotations
  {:jsdoc ["@idGenerator {consistent}"]}
  [s]
  s)

(defn array-equiv [a b]
  (let [al (alength a)
        bl (alength b)]
    (when (identical? al bl)
      (loop [i 0]
        (when (< i al)
          (when (= (aget a i) (aget b i))
            (recur (inc i))))))))

;; sometimes need to keep roots and elements in sync
;; FIXME: do 2 array really make sense?
;; could just be one array with indexes
;; and one actual array with elements?
;; roots needs to be kept in sync otherwise it prevents GC of detroyed elements
(defn array-swap [a old swap]
  (let [idx (.indexOf a old)]
    (when-not (neg? idx)
      (aset a idx swap))))

(deftype FragmentCode [^function create-fn ^function update-fn])

(declare ^{:arglists '([thing])} fragment-node?)

(deftype ManagedFragment
  [env
   ^FragmentCode code
   ^:mutable vals
   marker
   roots
   nodes]

  p/IManageNodes
  (dom-first [this] marker)

  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor)
    (dotimes [idx (alength roots)]
      (let [root (aget roots idx)]
        (if (satisfies? p/IManageNodes root)
          (p/dom-insert root parent anchor)
          (.insertBefore parent root anchor)))))

  p/IUpdatable
  (supports? [this ^FragmentNode next]
    (and (fragment-node? next)
         (identical? code (.-code next))))

  (dom-sync! [this ^FragmentNode next]
    (let [nvals (.-vals next)]
      (.. code (update-fn env roots nodes vals nvals))
      (set! vals nvals))
    :synced)

  p/IDestructible
  (destroy! [this]
    (.remove marker)
    (dotimes [x (alength nodes)]
      (let [el (aget nodes x)]
        (if (satisfies? p/IDestructible el)
          (p/destroy! el)
          (.remove el))))

    ;; FIXME: only necessary because of top level text nodes which aren't in nodes
    ;; might be better to just add them into nodes instead of leaving them out in the first place
    (dotimes [x (alength roots)]
      (let [el (aget roots x)]
        (when-not (satisfies? p/IDestructible el)
          (.remove el))))

    (set! (.-length roots) 0)
    (set! (.-length nodes) 0)))

(deftype FragmentNode [vals element-ns ^FragmentCode code]
  p/IConstruct
  (as-managed [_ env]
    (let [env (cond-> env element-ns (assoc ::element-ns element-ns))
          state (.. code (create-fn env vals))]
      (ManagedFragment. env code vals (common/dom-marker env) (aget state 0) (aget state 1))))

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

(defn create-managed [env other]
  ;; FIXME: validate that return value implements the proper protocols
  (p/as-managed other env))

;; called by macro generated code
(defn append-managed [parent other]
  (when-not (satisfies? p/IUpdatable other)
    (throw (ex-info "cannot append-managed" {:parent parent :other other})))
  (p/dom-insert other parent nil))

;; called by macro generated code
(defn update-managed [env roots nodes idx oval nval]
  ;; FIXME: should this even compare oval/nval?
  ;; comparing the array in fragment handles (which may contain other handles) might become expensive?
  ;; comparing 100 items to find the 99th didn't match
  ;; will compare 99 items again individually later in the code?
  ;; if everything is equal however a bunch of code can be skipped?

  ;; FIXME: actually benchmark in an actual app
  (when (not= oval nval)
    (let [^not-native el (aget nodes idx)]
      (if (p/supports? el nval)
        (p/dom-sync! el nval)
        (let [next (common/replace-managed env el nval)]
          (array-swap roots el next)
          (aset nodes idx next)
          )))))

;; called by macro generated code
(defn update-attr [env nodes idx ^not-native attr oval nval]
  (when (not= oval nval)
    (let [el (aget nodes idx)]
      (set-attr env el attr oval nval))))

(defn component-create [env component attrs]
  {:pre [(map? attrs)]}
  (comp/component-create env component attrs))

;; FIXME: does this ever need the old attrs oa?
(defn component-update [env roots nodes idx oc nc oa na]
  {:pre [(map? na)]}
  (let [^not-native comp (aget nodes idx)
        tmp (comp/->ComponentNode nc na)]
    (if (p/supports? comp tmp)
      (p/dom-sync! comp tmp)
      (let [new (common/replace-managed env oc tmp)]
        ;; FIXME: rework roots logic, can't be traversing that array all the time
        (array-swap roots comp new)
        (aset nodes idx new)
        ))))

(defn component-append [component child]
  ;; FIXME: support more slots?
  ;; FIXME: with hooks this should be cleaner ... feels too hacky
  (let [slot (p/dom-first (comp/get-slot component :default))]
    (if-not (satisfies? p/IManageNodes child)
      (.insertBefore (.-parentNode slot) child slot)
      (p/dom-insert child (.-parentNode slot) slot))))

(defn css-join [from-el from-attrs]
  [from-el from-attrs])