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
    [shadow.experiments.arborist.components :as comp]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.collections :as coll])
  (:import [goog.structs AvlTree]))


;; FIXME: this is not a good strategy at all.
;; it should completely process one branch of a tree before starting a new one
;; AvlTree is nice but not suited for this, should just make something custom

;; FIXME: is there a better way to compare these?
;; goal is to have high priority updates always first
;; then work higher in the dom before children (in case the work causes the removal of children)
;; then ideally based on DOM index so visible updates happen first
;; now cheating and just sorting by some.component/foo-bar@1
;; that is creation order which is kinda non-sense but easy to track as position in
;; DOM is dynamic
;; FIXME: this is called lots and should probably be smarter
(defn work-comparator [^not-native a ^not-native b]
  (if (identical? a b)
    0
    (let [c1 (js/goog.array.inverseDefaultCompare (p/work-priority a) (p/work-priority b))]
      (if-not (zero? c1)
        c1
        (let [c2 (js/goog.array.defaultCompare (p/work-depth a) (p/work-depth b))]
          (if-not (zero? c2)
            c2
            (let [c3 (js/goog.array.defaultCompare (p/work-id a) (p/work-id b))]
              (if-not (zero? c3)
                c3
                (throw (ex-info "work comparator shouldn't reach here?" {:a a :b b}))
                ))))))))

(comment
  (defrecord DummyCompare [prio depth id]
    p/IWork
    (work-priority [this] prio)
    (work-depth [this] depth)
    (work-id [this] id))

  (let [t (AvlTree. work-comparator)]
    (let [x (DummyCompare. 10 1 "test.b#3")]
      (.add t x)
      (.add t x)
      (.add t x))
    (.add t (DummyCompare. 10 0 "test.a#1"))
    (.add t (DummyCompare. 10 0 "test.a#2"))
    ;; should be first
    (.add t (DummyCompare. 100 5 "some.event"))
    (.inOrderTraverse t prn)))

(deftype TreeScheduler [root ^AvlTree work-tree ^:mutable update-pending?]
  p/IScheduleUpdates
  (schedule-update! [this component]
    (.add work-tree component)

    ;; schedule was added in some async work
    (when-not update-pending?
      (js/console.warn "async schedule?" this component)))

  (unschedule! [this component]
    (.remove work-tree component))

  (run-now! [this callback]
    (set! update-pending? true)
    (callback)
    (.process-pending! this))

  Object
  (process-pending! [this]
    ;; FIXME: rewrite this entirely .. this keeps working until all work is done
    ;; handling updates directly after event completes
    ;; but it should break work into chunks
    ;; should also support async/suspend at some point
    ;; need to figure out how to do that in a smart way

    ;; keep working on the first task only
    ;; any work may cause changes in the work-tree
    (loop []
      (when (pos? (.getCount work-tree))
        (let [next (.getMinimum work-tree)]
          (p/work! next)
          (recur))))

    (set! update-pending? false)

    ;; FIXME: actually schedule things. don't run everything all the time
    ;; FIXME: dom effects
    ))

(deftype TreeRoot [container ^:mutable env ^:mutable root]
  p/ITraverseNodes
  (managed-nodes [this]
    (p/managed-nodes root))

  p/IDirectUpdate
  (update! [this next]
    (if root
      (p/update! root next)
      (let [new-root (common/ManagedRoot. env nil nil)]
        (set! root new-root)
        (p/update! root next)
        (p/dom-insert root container nil)
        )))

  p/ITreeNode
  (sync! [this]
    (p/sync! root))

  p/IDestructible
  (destroy! [this]
    (when root
      (p/destroy! root))))

(defn dom-root
  ([container env]
   (let [root (TreeRoot. container nil nil)
         scheduler (TreeScheduler. root (AvlTree. work-comparator) false)
         root-env (assoc env ::root root ::comp/scheduler scheduler)]
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

(deftype AtomWatch [the-atom ^:mutable val component idx]
  p/IBuildHook
  (hook-build [this c i]
    (AtomWatch. the-atom nil c i))

  p/IHook
  (hook-init! [this]
    (set! val @the-atom)
    (add-watch the-atom this
      (fn [_ _ _ _]
        ;; don't take new-val just yet, it may change again in the time before
        ;; we actually get to an update. deref'ing when the actual update occurs
        ;; which will also reset the dirty flag
        (comp/invalidate! component idx))))

  (hook-ready? [this] true) ;; born ready
  (hook-value [this] val)
  (hook-update! [this]
    ;; time to actually deref, any change after this will invalidate and trigger
    ;; an update again. this doesn't mean the value will actually get to render.
    (set! val @the-atom)
    true)
  (hook-deps-update! [this new-val]
    (throw (ex-info "shouldn't have changing deps?" {})))
  (hook-destroy! [this]
    (remove-watch the-atom this)))

(defn watch [the-atom]
  (AtomWatch. the-atom nil nil nil))