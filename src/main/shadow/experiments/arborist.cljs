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

(deftype TreeScheduler [^:mutable work-set ^:mutable update-pending?]
  p/IScheduleUpdates
  (schedule-update! [this component]
    (set! work-set (conj work-set component))

    ;; schedule was added in some async work
    (when-not update-pending?
      (js/console.warn "async schedule?" this component)))

  (unschedule! [this component]
    (set! work-set (disj work-set component)))

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
      (when-some [next (first work-set)]
        (p/work! next)
        (recur)))

    (set! update-pending? false)

    ;; FIXME: actually schedule things. don't run everything all the time
    ;; FIXME: dom effects
    ))

(deftype TreeRoot [container ^:mutable env ^:mutable root]
  p/IDirectUpdate
  (update! [this next]
    (if root
      (p/update! root next)
      (let [new-root (common/managed-root env nil nil)]
        (set! root new-root)
        (p/update! root next)
        (p/dom-insert root container nil)
        )))

  p/IDestructible
  (destroy! [this]
    (when root
      (p/destroy! root))))

(defn init [env]
  (assoc env ::comp/scheduler (TreeScheduler. #{} false)))

(defn run-now! [env callback]
  (p/run-now! (::comp/scheduler env) callback))

(defn dom-root
  ([container env]
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
        (comp/hook-invalidate! component idx))))

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