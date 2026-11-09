(ns shadow.grove.runtime
  (:require
    [shadow.grove :as-alias sg]
    [goog.async.nextTick]
    [shadow.grove.protocols :as gp]))

(defonce known-runtimes-ref
  (atom {}))


(defn ref? [x]
  (and (instance? cljs.core/Atom x)
       (::sg/runtime @x)))

(defonce id-seq (volatile! 0))

(defn next-id []
  (vswap! id-seq inc))

(defonce ticker (js/Promise.resolve nil))

(defn next-tick [callback]
  (js/goog.async.nextTick callback))

(defn microtask [callback]
  (.then ticker callback))

(def ^:dynamic ^not-native *env* nil)
(def ^:dynamic ^gp/IProvideSlot *slot-provider* nil)
(def ^:dynamic ^numeric *slot-idx* nil)
(def ^:dynamic *slot-value* ::pending)
(def ^:dynamic *claimed* nil)
(def ^:dynamic *ready* true)

(deftype SlotRef
  [provider
   idx
   ^:mutable state
   ^:mutable cleanup]

  gp/IInvalidateSlot
  (invalidate! [this]
    (gp/-invalidate-slot! provider idx))

  cljs.core/IDeref
  (-deref [this]
    state)

  cljs.core/IReset
  (-reset! [this nval]
    (let [oval state]
      (when (not= oval nval)
        (set! state nval)

        ;; don't invalidate when slot-fn itself is modifying ref
        (when-not (identical? *slot-provider* provider)
          (when-not (identical? idx *slot-idx*)
            (gp/invalidate! this)))
        ))

    nval)

  cljs.core/ISwap
  (-swap! [this f]
    (-reset! this (f state)))
  (-swap! [this f a]
    (-reset! this (f state a)))
  (-swap! [this f a b]
    (-reset! this (f state a b)))
  (-swap! [this f a b xs]
    (-reset! this (apply f state a b xs))))

(defn claim-slot! [claim-id]
  (when-not *slot-provider*
    (throw (ex-info "can only be used in component bind" {})))

  (if-not *claimed*
    (set! *claimed* claim-id)
    (throw (ex-info "slot already claimed" {:idx *slot-idx* :claimed *claimed* :attempt claim-id})))

  (gp/-init-slot-ref *slot-provider* *slot-idx*))

;; for slot providers other than components (custom managed nodes running user code)

(defn slot-ref
  "creates the ref a slot provider returns from gp/-init-slot-ref for slot idx"
  [provider idx]
  (SlotRef. provider idx nil nil))

(defn run-slot
  "calls (f) as slot idx of provider, the way a component runs a bind, so claiming
   helpers (sg/kv-lookup, sg/query, ...) work inside it. prev-value is what sg/suspend!
   returns. returns the result of (f), or ::suspended if it called sg/suspend!"
  [provider idx env prev-value f]
  (binding [*slot-provider* provider
            *env* env
            *slot-idx* idx
            *slot-value* prev-value
            *claimed* false
            *ready* true]
    (let [result (f)]
      ;; read after the call, sg/suspend! sets it to false
      (if *ready* result ::suspended))))

(defn cleanup-slot-ref!
  "runs the cleanup registered for ref (comp/set-cleanup!) with its value, once.
   slot providers call it when they are destroyed, so claims release what they hold"
  [^SlotRef ref]
  (when-some [cleanup (.-cleanup ref)]
    (set! (.-cleanup ref) nil)
    (cleanup (.-state ref))))
