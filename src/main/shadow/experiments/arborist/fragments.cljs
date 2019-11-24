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

(deftype FragmentCode [frag-id ^function create-fn ^function update-fn]
  Object
  (^array create [this env vals]
    (create-fn env vals))
  (^array update [this env roots nodes vals nvals]
    (update-fn env roots nodes vals nvals)))

(declare fragment-node?)

(deftype ManagedFragment
  [env
   ^not-native code
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

  p/ITraverseNodes
  (managed-nodes [this]
    (into [] (filter #(satisfies? p/IManageNodes %)) nodes))

  p/ITreeNode
  (sync! [this]
    (dotimes [idx (alength nodes)]
      (let [node (aget nodes idx)]
        (when (satisfies? p/ITreeNode node)
          (p/sync! node)))))

  p/IUpdatable
  (supports? [this ^FragmentNode next]
    (and (fragment-node? next)
         ;; FIXME: teach compiler inference some new tricks so
         ;; (.. next -code -frag-id) doesn't complain
         (identical? (.-frag-id code) (. ^FragmentCode (. next -code) -frag-id))))

  (dom-sync! [this ^FragmentNode next]
    (let [nvals (.-vals next)]
      (.update code env roots nodes vals nvals)


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

(deftype FragmentNode [vals ^FragmentCode code]
  p/IConstruct
  (as-managed [_ env]
    (let [state (.create code env vals)]
      (ManagedFragment. env code vals (common/marker env) (aget state 0) (aget state 1))))

  IEquiv
  (-equiv [this ^FragmentNode other]
    (and (instance? FragmentNode other)
         (identical? (.-frag-id code) (. ^FragmentCode (. other -code) -frag-id))
         (array-equiv vals (.-vals other)))))

(defn fragment-node? [thing]
  (instance? FragmentNode thing))

;;
;; called from macro
;;

;; optimized variant
;; allocates fragment-code once, uses it multiple times
(defn fragment-create [frag-id create-fn update-fn]
  (FragmentCode. frag-id create-fn update-fn))

(defn fragment-node [^FragmentCode code vals]
  (FragmentNode. vals code))

;; fallback, re-allocating the functions each time
(defn fragment-new [frag-id vals create-fn update-fn]
  (FragmentNode. vals
    (FragmentCode. frag-id create-fn update-fn)))

;; FIXME: should maybe take ::document from env
;; not sure under which circumstance this would ever need a different document instance though
(defn create-element
  ;; inlined version is longer than the none inline version
  ;; {:jsdoc ["@noinline"]}
  [env ^Keyword type] ;; kw
  (js/document.createElement (.-name type)))

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
    (let [el (aget nodes idx)]
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
  (let [comp (aget nodes idx)
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