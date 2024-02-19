(ns shadow.grove.operator
  (:refer-clojure :exclude (use))
  (:require
    [shadow.grove.components :as comp]
    [shadow.grove.impl :as impl]
    [shadow.grove.runtime :as rt]
    ))

(def ^{:tag boolean
       :jsdoc ["@define {boolean}"]}
  DEBUG
  (js/goog.define "shadow.grove.operator.DEBUG" js/goog.DEBUG))

(def ^:dynamic *current* nil)

(defonce ops-ref (atom {}))
(defonce ops-set (js/Set.))

(defprotocol IOperate
  (-op-key [this]))

(deftype Operator
  [op-def
   op-key
   rt-ref
   ^:mutable ^not-native attrs
   ^:mutable state
   ^:mutable call-handlers
   ;; time for GC purposes
   ^:mutable last-action
   ;; gc action
   ^:mutable cleanup
   ;; for db-link
   ^:mutable link-path
   ;; watch listeners
   ^:mutable listeners
   ;; other operators this is linked to
   linked
   ;; others may have a live reference to this
   ;; cannot clean until they remove themselves
   dependents

   ;; DEBUG-only array, to log what happens in the life of an operator
   log]

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

      (when DEBUG (.push log [:changed oval state]))

      ;; don't notify about updates when in internal stuff, e.g. init
      (when-not (identical? this *current*)
        ;; FIXME: should batch these together intelligently
        ;; changes may trigger other changes
        (.trigger-change-listeners! this oval nval))

      (when-not (nil? link-path)
        (let [data-ref (::rt/data-ref @rt-ref)
              sentinel (js/Object.)
              prev-val (get-in @data-ref link-path sentinel)
              db-key (first link-path)]

          (binding [*current* this]
            (swap! data-ref assoc-in link-path nval))

          (rt/microtask
            (fn []
              ;; added or updated
              (if (identical? prev-val sentinel)
                (impl/invalidate-keys! @rt-ref #{db-key} #{} #{})
                (impl/invalidate-keys! @rt-ref #{} #{} #{db-key})
                ))))))

    ;; reset! supposed to return new value
    nval)

  cljs.core/IFn
  (-invoke [this call-id]
    (let [callback (.get-callback this call-id)]
      (when DEBUG (.push log [:call call-id]))
      (callback)))
  (-invoke [this call-id a1]
    (let [callback (.get-callback this call-id)]
      (when DEBUG (.push log [:call call-id a1]))
      (callback a1)))
  (-invoke [this call-id a1 a2]
    (let [callback (.get-callback this call-id)]
      (when DEBUG (.push log [:call call-id a1 a2]))
      (callback a1 a2)))
  (-invoke [this call-id a1 a2 a3]
    (let [callback (.get-callback this call-id)]
      (when DEBUG (.push log [:call call-id a1 a2 a3]))
      (callback a1 a2 a3)))
  (-invoke [this call-id a1 a2 a3 a4]
    (let [callback (.get-callback this call-id)]
      (when DEBUG (.push log [:call call-id a1 a2 a3 a4]))
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
    (.remove-change-listener this key))

  Object
  (touch [this]
    (set! last-action (js/Date.now))
    this)

  (get-callback [this call-id]
    (let [callback (get call-handlers call-id)]
      (when-not callback
        (throw (ex-info (str "operator does not handle call " call-id) {:op this :call-id call-id})))
      callback))

  (set-attr [this attr val]
    (when DEBUG (.push log [:set attr val]))

    (set! attrs (assoc attrs attr val))
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

  (db-link [this path]
    (when link-path
      (throw (ex-info "can only be linked once" {:path path :link-path link-path :op this})))

    (let [data-ref (::rt/data-ref @rt-ref)]

      ;; get initial state from db
      (set! state (get-in @data-ref path))

      (set! link-path path)

      (when DEBUG (.push log [:db-link path state]))

      (add-watch data-ref [this path]
        (fn [_ _ old new]
          ;; don't trigger if we did that update
          (when-not (identical? *current* this)
            (let [oldv state
                  newv (get-in new path)]
              (when (not= oldv newv)
                (when DEBUG (.push log [:db-change oldv newv]))

                (set! state newv)
                (.trigger-change-listeners! this oldv newv))
              )))))

    this)

  (add-call-handler [this key callback]
    (set! call-handlers (assoc call-handlers key callback))
    this)

  (add-change-listener [this key callback]
    (set! listeners (assoc listeners key callback))
    this)

  (remove-change-listener [this key]
    (set! listeners (dissoc listeners key))
    this)

  (add-dependent [this other]
    (when DEBUG
      (.push log [:add-dependent other]))
    (.add dependents other)
    this)

  (remove-dependent [this other]
    (when DEBUG
      (.push log [:remove-dependent other]))
    (.delete dependents other)

    ;; FIXME: auto cleanup once last dependent is removed?
    this)

  (add-linked [this other]
    (when DEBUG
      (.push log [:add-linked other]))

    (.add linked other)
    this)

  (remove-linked [this other]
    (when DEBUG
      (.push log [:remove-linked other]))

    (.delete linked other)
    this)

  (set-cleanup! [this callback]
    (set! cleanup callback)
    this))

(defn operator? [x]
  (instance? Operator x))

(defn op-key [x]
  (-op-key x))

(defn perform-gc! [rt-ref]
  ;; FIXME: actually clean up unused operators
  ;; as in operators that have no dependents
  ;; (js/console.log "ops gc" @ops-ref)
  )

(defn operator-definition? [x]
  (or (fn? x)
      ;; FIXME: I think a map could be useful for extra stuff
      ;; is it? could put a spec in there to valid the op-key?
      ;; a spec/schema to validate the state?
      (and (map? x) (fn? (:init x)))))

(defn get-or-create [rt-ref op-def op-key]
  {:pre [rt-ref
         (operator-definition? op-def)]}
  (let [op-path [op-def op-key]]

    (or (get-in @ops-ref op-path)
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
                nil ;; action-handlers
                (js/Date.now) ;; last-action
                nil ;; cleanup
                nil ;; link-path
                nil ;; listeners
                (js/Set.) ;; linked
                (js/Set.) ;; dependents
                (when DEBUG
                  (js/Array.))
                )]

          ;; immediately register, dunno about cyclic links yet, but they seem necessary
          (swap! ops-ref assoc-in op-path new-op)

          (binding [*current* new-op]
            (init-fn new-op op-key))

          ;; js/Set maintains insertion order, and we add this after the fact
          ;; so that links established during init are added first
          ;; so that at least as best effort operators with no dependents
          ;; are listed after those with
          (.add ops-set new-op)

          new-op
          ))))

(defn init-only! [op]
  ;; for now restricting some functions to only work during init
  ;; not sure if this is actually necessary, but might leak otherwise
  (when DEBUG
    (when-not (identical? *current* op)
      (throw (ex-info "can only called in op/init!" {:op op :current *current*}))))
  js/undefined)

;; for compatibility until I figure out if this is actually any better than the normalized db
;; hard links the value of this operator to a place in the data-ref db
;; reset!-ing the op will also set the value in the db
;; if the db changes from elsewhere the op value is updated accordingly
(defn db-link [^Operator op path]
  {:pre [(instance? Operator op)
         (vector? path)]}
  (init-only! op)
  (.db-link op path)
  (.touch op)
  op)

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
   (get-or-create (.-rt-ref origin-op) op-def op-key)))

(defn link-other
  ([origin-op op-def]
   (link-other origin-op op-def ::default))
  ([^Operator origin-op op-def op-key]
   {:pre [(operator? origin-op)
          (operator-definition? op-def)]}
   (let [^Operator other-op (get-other origin-op op-def op-key)]

     ;; need to remember who we are linked to, so we can clean up later
     (.add-dependent other-op origin-op)
     (.add-linked origin-op other-op)

     (.touch origin-op)
     (.touch other-op)

     other-op)))

(defn unlink-other [^Operator origin-op ^Operator other-op]
  (.touch origin-op)
  (.touch other-op)

  (.remove-dependent other-op origin-op)
  (.remove-linked origin-op other-op)
  origin-op)

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

       (when prev-op
         (.remove-dependent prev-op ref)
         (.touch prev-op))

       (comp/set-cleanup! ref
         (fn [{:keys [^Operator op]}]
           (.remove-dependent op ref)
           (.touch op)
           ))

       (let [{::rt/keys [runtime-ref]} comp/*env*
             ^Operator op (get-or-create runtime-ref op-def op-key)]

         (.add-dependent op ref)
         (.touch op)

         (reset! ref {:updates 0 :op-def op-def :op-key op-key :op op})))

     (:op @ref))))

(defn use-query
  "trigger a call to an operator, do that again if the operator changes
   useful for getting \"views\" of the operators data, without getting all of it

   (bind products (use-op &products))
   (bind product-count (use-query products :count))

   exposes less data to the component, which may be desirable"
  ([op action]
   (use-query op action {}))
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
