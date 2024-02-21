(ns shadow.grove.operator
  (:refer-clojure :exclude (use))
  (:require
    [shadow.grove.components :as comp]
    [shadow.grove.runtime :as rt]
    ))

(def ^{:tag boolean
       :jsdoc ["@define {boolean}"]}
  DEBUG
  (js/goog.define "shadow.grove.operator.DEBUG" js/goog.DEBUG))

(def ^:dynamic *current* nil)

(defonce ops-ref (atom {}))

(defprotocol IOperate
  (-op-key [this]))

(declare Operator)

(defn operator? [x]
  (instance? Operator x))

(defn perform-gc! []
  (swap! ops-ref
    (fn [state]
      (reduce-kv
        (fn [m op-def instances]
          (assoc m
            op-def
            (reduce-kv
              (fn [instances op-key weak-ref]
                (if (.deref weak-ref)
                  instances
                  (dissoc instances op-key)))
              instances
              instances)))
        state
        state))))

;; super low priority
;; only cleaning up dead weakrefs, where the op has been gc'd
;; will accumulate a lot of {op-def {op-key dead-reference}} entries otherwise
;; which is probably never an issue, but also kinda waste bunch of a potential large op-keys
(defonce gc-interval
  (js/setInterval #(perform-gc!) 60000))

(deftype Operator
  [op-def
   op-key
   rt-ref
   ^:mutable ^not-native attrs
   ^:mutable state
   ^:mutable call-handlers
   ;; gc action
   ^:mutable cleanup
   ;; watch listeners
   ^:mutable listeners]

  IOperate
  (-op-key [this]
    op-key)

  cljs.core/IHash
  (-hash [this]
    (goog/getUid this))

  cljs.core/IDeref
  (-deref [this]
    state)

  cljs.core/ILookup
  (-lookup [this k]
    (-lookup attrs k))
  (-lookup [this k not-found]
    (-lookup attrs k not-found))

  cljs.core/IReset
  (-reset! [this nval]
    (let [oval state]
      (set! state nval)

      ;; don't notify about updates when in internal stuff, e.g. init
      (when-not (identical? this *current*)
        ;; FIXME: should batch these together intelligently
        ;; changes may trigger other changes
        (.trigger-change-listeners! this oval nval)))

    ;; reset! supposed to return new value
    nval)

  cljs.core/IFn
  (-invoke [this call-id]
    (let [callback (.get-callback this call-id)]
      (callback)))
  (-invoke [this call-id a1]
    (let [callback (.get-callback this call-id)]
      (callback a1)))
  (-invoke [this call-id a1 a2]
    (let [callback (.get-callback this call-id)]
      (callback a1 a2)))
  (-invoke [this call-id a1 a2 a3]
    (let [callback (.get-callback this call-id)]
      (callback a1 a2 a3)))
  (-invoke [this call-id a1 a2 a3 a4]
    (let [callback (.get-callback this call-id)]
      (callback a1 a2 a3 a4)))
  ;; FIXME: add more

  cljs.core/ISwap
  (-swap! [this f]
    (-reset! this (f state)))
  (-swap! [this f a]
    (-reset! this (f state a)))
  (-swap! [this f a b]
    (-reset! this (f state a b)))
  (-swap! [this f a b xs]
    (-reset! this (apply f state a b xs)))

  cljs.core/IWatchable
  (-notify-watches [this oldval newval]
    (throw (ex-info "why is this here?" {})))
  (-add-watch [this key f]
    (.add-change-listener this key
      (fn [old-val new-val]
        (f this key old-val new-val))))
  (-remove-watch [this key]
    (.remove-change-listener this key)
    key)

  Object
  (get-callback [this call-id]
    (let [callback (get call-handlers call-id)]
      (when-not callback
        (throw (ex-info (str "operator does not handle call " call-id) {:op this :call-id call-id})))
      callback))

  (set-attr [this attr val]
    (set! attrs (-assoc attrs attr val))
    this)

  (trigger-change-listeners! [this oval nval]
    (reduce-kv
      (fn [_ key callback]
        (callback oval nval)
        ;; just to prevent any callback returning a reduced and stopping this
        nil)
      nil
      listeners)

    this)

  (add-call-handler [this key callback]
    (set! call-handlers (-assoc call-handlers key callback))
    this)

  (add-change-listener [this key callback]
    (set! listeners (-assoc listeners key callback))
    this)

  (remove-change-listener [this key]
    (set! listeners (-dissoc listeners key))
    this)

  (set-cleanup! [this callback]
    (set! cleanup callback)
    this))

(defn op-key [x]
  (-op-key x))

(defn operator-definition? [x]
  (or (fn? x)
      ;; FIXME: I think a map could be useful for extra stuff
      ;; is it? could put a spec in there to valid the op-key?
      ;; a spec/schema to validate the state?
      (and (map? x) (fn? (:init x)))))

;; this must not be used externally
;; creating an operator must link it to something
;; otherwise nothing will ever clean it up until GC is implemented
(defn get-or-create [rt-ref op-def op-key]
  {:pre [rt-ref
         (operator-definition? op-def)]}
  (let [op-path
        [op-def op-key]

        weak-ref
        (get-in @ops-ref op-path)

        op
        (when weak-ref
          (.deref weak-ref))]

    (or op
        (let [^function init-fn
              (if (fn? op-def)
                op-def
                (:init op-def))

              new-op
              (->Operator
                op-def
                op-key
                rt-ref
                {} ;; attrs
                nil ;; init state, should be nil
                {} ;; action-handlers
                nil ;; cleanup
                {} ;; listeners
                )

              weak-ref
              (js/WeakRef. new-op)]

          ;; store as weak reference, so it can get cleaned up when no longer referenced
          (swap! ops-ref assoc-in op-path weak-ref)

          (binding [*current* new-op]
            (init-fn new-op op-key))

          new-op
          ))))

(defn init-only! [op]
  ;; for now restricting some functions to only work during init
  ;; not sure if this is actually necessary, but might leak otherwise
  (when DEBUG
    (when-not (identical? *current* op)
      (throw (ex-info "can only called in op/init!" {:op op :current *current*}))))
  js/undefined)

(defn cleanup! [^Operator op callback]
  {:pre [(operator? op)
         (fn? callback)]}
  (init-only! op)
  (.set-cleanup! op callback)
  op)

(defn init-state [^Operator op init-state]
  (init-only! op)
  (when (nil? @op)

    (reset! op init-state)))

(defn set-attr [^Operator op attr val]
  {:pre [(operator? op)
         (keyword? attr)]}
  (.set-attr op attr val))

;; things to be called at runtime
(defn get-other
  ([origin-op op-def]
   (get-other origin-op op-def ::default))
  ([^Operator origin-op op-def op-key]
   {:pre [(operator? origin-op)
          (operator-definition? op-def)]}
   (get-or-create (.-rt-ref origin-op) op-def op-key)))

(defn handle [^Operator op action-id callback]
  {:pre [(operator? op)
         (keyword? action-id)
         (fn? callback)]}
  (.add-call-handler op action-id callback)
  op)

(defonce timeouts-ref (atom {}))

(defn timeout [^Operator op time callback]
  (when-some [timeout (get @timeouts-ref op)]
    (js/clearTimeout timeout))

  (let [timeout
        (js/setTimeout
          (fn []
            (swap! timeouts-ref dissoc op)
            (callback))
          time)]
    (swap! timeouts-ref assoc op timeout)
    timeout))

(defn use
  ([op-def]
   (use op-def ::default))
  ([op-def op-key]
   {:pre [(operator-definition? op-def)]}
   (let [ref (comp/claim-bind! ::link)

         {prev-op-def :op-def
          prev-op-key :op-key
          ^Operator prev-op :op} @ref]

     (when-not (and (identical? op-def prev-op-def)
                    (= op-key prev-op-key))

       (let [{::rt/keys [runtime-ref]} comp/*env*
             ^Operator op (get-or-create runtime-ref op-def op-key)]

         (reset! ref {:updates 0 :op-def op-def :op-key op-key :op op})))

     (:op @ref))))

(defn use-call
  "trigger a call to an operator, do that again if the operator changes
   useful for getting \"views\" of the operators data, without getting all of it

   (bind products (use-op &products))
   (bind product-count (use-query products :count))

   exposes less data to the component, which may be desirable"
  ([op action]
   (use-call op action {}))
  ([op action action-data]
   {:pre [(operator? op)
          (keyword? action)]}
   (let [ref (comp/claim-bind! ::use-call)]

     (when-not @ref
       (comp/set-cleanup! ref
         (fn [{:keys [op]}]
           (remove-watch op ref)))

       (add-watch op ref
         (fn [_ _ _ _]
           ;; schedules up to eventually run the call again
           (swap! ref update :updates inc)))

       (reset! ref {:op op :ref ref}))

     ;; just always run the call again, let the operator worry about memoizing
     (op action action-data)
     )))
