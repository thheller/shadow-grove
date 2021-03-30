(ns shadow.experiments.grove.event-fsm
  "simple state machine helpers meant to directly integrate into the events structure
   loosely based on Erlang gen_fsm / gen_statem"
  (:refer-clojure :exclude [test])
  (:require
    [shadow.experiments.grove.events :as ev]))

(defn on
  ([machine event handler]
   (on machine ::ALL event handler))
  ([machine state event handler]
   {:pre [(keyword? state)
          (some? event)
          (ifn? handler)
          ;; not allowed to use name of machine as event
          ;; since that is used to send internal events
          (not= (:machine-id machine) event)]}
   (assoc-in machine [:states state event] handler)))

(defn on-enter [machine state handler]
  (on machine state ::enter handler))

(defn on-exit [machine state handler]
  (on machine state ::exit handler))

(defn on-timeout [machine state handler]
  (on machine state ::timeout handler))

(defn on-init [machine handler]
  (assoc machine :init-fn handler))

(defn next-state
  ([env ns]
   (assoc env ::next-state ns))
  ([env ns timeout]
   (assoc env ::next-state ns ::timeout timeout)))

;; FIXME: defrecord generates a bunch of code we don't need
;; consider writing deftype variant
;; the goal here is having something that implements the event protocol
;; so it can be used just like any other event fn but manages the state variable
;; and state transitions automatically

(defn- noop [env ev]
  env)

(defn- handle-machine-internal [env ev]
  (js/console.log "handle-machine-internal" env ev))

(defn- guard-check! [result machine guard op]
  (when-not (identical? guard (::tx-guard result))
    (throw
      (ex-info
        (str "fsm " (:machine-id machine) " event " op " did not return proper result")
        {:result result}))))

(def internal-keys [::next-state ::state ::timeout ::data ::machine ::state-path ::tx-guard])

(defn- clear-internal-keys
  "clears or restores internal keys, restoring for nested machine calls"
  [next-env prev-env]
  (reduce
    (fn [next-env key]
      (if (contains? prev-env key)
        (assoc next-env key (get prev-env key))
        (dissoc next-env key)))
    next-env
    internal-keys))

(defprotocol IMachineActions
  (-init [this env ev]))

(defn- queue-timeout
  [{::keys [machine timeout] :as env} timeout-state]
  (ev/queue-fx env :timeout/set
    ;; event sent to fsm internal
    {:timeout/id (:machine-id machine)
     :timeout/after timeout
     :e (:machine-id machine)
     ::state timeout-state
     ::trigger ::timeout}))

(defn- clear-timeout
  [env]
  (ev/queue-fx env :timeout/clear
    {:timeout/id (get-in env [::machine :machine-id])}))

(defn- store-machine-state
  [{::keys [state-path state data timeout] :as env}]
  (-> env
      (update :db
        (fn [db]
          (assoc-in db state-path {::state state
                                   ::data data
                                   ::timeout timeout})))))

(defrecord Machine
  [machine-id ;; kw
   init-fn ;; (fn [env ev])
   state-path-fn ;; (fn [env ev])
   states] ;; {<state-kw> {<event> <handler>}}

  IMachineActions
  (-init [this env ev]
    ;; FIXME: should this throw if init is called when already initialized? or just overwrite? or do nothing?
    (let [state-path
          (state-path-fn ev)

          init-guard
          (js/Object.)

          init-env
          (assoc env ::machine this ::state-path state-path ::tx-guard init-guard ::data {})

          {::keys [next-state data timeout] :or {next-state :INITIAL} :as init-result}
          (init-fn init-env ev)]

      (when-not (identical? init-guard (::tx-guard init-result))
        (throw
          (ex-info
            (str "fsm " machine-id " init did not return proper result")
            {:env env
             :ev ev
             :result init-result})))

      (-> init-result
          (update :db
            (fn [db]
              (assoc-in db state-path {::state next-state
                                       ::data data
                                       ::timeout timeout})))

          (cond->
            timeout
            (queue-timeout next-state))
          (clear-internal-keys env))))

  cljs.core/IFn
  (-invoke [this env {:keys [e] :as ev}]
    ;; FIXME: move internal state tracking out of tx-env during transition
    ;; should only ever write to state-path so it doesn't conflict
    ;; cleaning up internal keys after transition prohibits running nested fsm currently
    ;; since the inner will kill the keys of the outer
    (let [state-path
          (state-path-fn env ev)

          tx-guard
          (js/Object.)

          machine-state
          (get-in (:db env) state-path)

          tx-env
          (if machine-state
            env
            (-init this env ev))

          machine-state
          (get-in (:db tx-env) state-path)

          ;; _ (js/console.log "fsm result after init" tx-env machine-state)

          tx-env
          (assoc tx-env ::state-path state-path ::machine this)

          STATE
          (::state machine-state)

          DATA
          (::data machine-state)

          handler-fn
          (or (get-in states [STATE e])
              ;; FIXME: should ::ALL events run always in addition to state specific events?
              (get-in states [::ALL e])
              (throw (ex-info
                       (str "State Machine did not handle event"
                            "\n  machine " machine-id
                            "\n  state " STATE
                            "\n  event " e)
                       {:machine machine-id
                        :state STATE
                        :event ev})))

          tx-env
          (assoc tx-env
            ::data DATA
            ::state STATE
            ::tx-guard tx-guard
            ;; in case a parent fsm has in tx and has this set
            ;; it'll be restored later by clear-internal-keys
            ::next-state nil)

          ;; using or since handler should be allowed to return nil for convenience
          ;; signaling that no change should occur
          ;; (when some-condition? (do-stuff env))
          {NEXT-STATE ::next-state :as result}
          (or (handler-fn tx-env ev) tx-env)]

      ;; (js/console.log "fsm result after handler" result STATE NEXT-STATE)

      (guard-check! result this tx-guard [STATE e])

      (let [state-transition? (and NEXT-STATE (not= NEXT-STATE STATE))]

        ;; result didn't set ::fsm/next-state OR remained in same state
        ;; means we remain in same state, data or timeout may change independently

        (if-not state-transition?
          (-> result
              (cond->
                ;; if timeout is currently remove that
                (and (::timeout result) (::timeout machine-state))
                (clear-timeout)

                ;; set new timeout if requested
                (::timeout result)
                (queue-timeout STATE))
              (store-machine-state)
              (clear-internal-keys env))

          (let [exit-handler-fn
                (get-in states [STATE ::exit] noop)

                enter-handler-fn
                (get-in states [NEXT-STATE ::enter] noop)

                result
                (or (exit-handler-fn result ev) result)]

            ;; (js/console.log "fsm result after exit" result)

            ;; FIXME: must handle exit result properly
            ;; should it be able to abort transition?
            ;; currently if it sets any of our keys they bleed over into enter
            ;; since we don't handle or unset them?

            (guard-check! result this (::tx-guard tx-env) [STATE :exit])

            (let [result (or (enter-handler-fn result ev) result)]

              ;; (js/console.log "fsm result after enter" result)

              (guard-check! result this (::tx-guard tx-env) [NEXT-STATE :enter])

              (-> result
                  (assoc ::state NEXT-STATE)
                  (cond->
                    ;; if timeout is currently remove that
                    (and (::timeout result) (::timeout machine-state))
                    (clear-timeout)

                    ;; set new timeout if requested
                    (::timeout result)
                    (queue-timeout STATE))
                  (store-machine-state)
                  (clear-internal-keys env)))
            ))))))

(defn default-init-fn [env ev]
  (assoc env ::next-state :INITIAL))

(defn create [machine-id]
  (->Machine
    machine-id
    default-init-fn
    (fn [env ev]
      [machine-id])
    {::ALL
     {machine-id handle-machine-internal}}))

(defn register
  "registers all events declared as handled in machine in runtime"
  [machine rt-ref]
  (run!
    (fn [ev-id]
      ;; FIXME: some kind of guard for conflicting actions
      ;; ev-id needs to be unique per runtime
      ;; qualified keywords should be ok for this but might not be
      (ev/reg-event rt-ref ev-id machine))
    (->> (:states machine)
         (vals)
         (mapcat keys)
         (set)))

  machine)

(defn init
  ;; using env first to be -> friendly
  ([env machine]
   (init env machine {}))
  ([env machine init-event]
   (-init machine env init-event)))

