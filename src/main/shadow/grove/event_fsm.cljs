(ns shadow.grove.event-fsm
  "simple state machine helpers meant to directly integrate into the events structure
   loosely based on Erlang gen_fsm / gen_statem"
  (:refer-clojure :exclude (use))
  (:require
    [shadow.grove :as sg]
    [shadow.grove.components :as comp]
    [shadow.grove.runtime :as rt]))

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
  (on machine state ::timeout! handler))

(defn on-init [machine handler]
  (assoc machine :init-fn handler))

(defn next-state
  ([env ns]
   (when-not (get-in env [::machine :states ns])
     (throw (js/Error.
              (str "fsm tried to switch to invalid state " ns))))

   (assoc env ::next-state ns))
  ([env ns timeout]
   (when-not (get-in env [::machine :states ns])
     (throw (js/Error.
              (str "fsm tried to switch to invalid state " ns))))

   (assoc env ::next-state ns ::timeout timeout)))

(defn data-reset
  [env new-data]
  (assoc env ::data new-data))

(defn data-update
  [env update-fn & args]
  (update env ::data
    (fn [data]
      (apply update-fn data args))))

(defn- noop [env ev]
  env)

(defn- guard-check! [result machine guard op]
  (when-not (identical? guard (::tx-guard result))
    (throw
      (ex-info
        (str "fsm " (:machine-id machine) " event " op " did not return proper result")
        {:result result}))))

(def internal-keys [::machine ::next-state ::state ::timeout ::data])

(defn- clear-internal-keys
  [env]
  (reduce dissoc env internal-keys))

(defrecord Machine
  [machine-id ;; kw
   ;; (fn [env init-arg])
   init-fn
   ;; {<state-kw> {<event> <handler>}}
   states])

(defonce active-ref
  (atom {}))

(defn store-machine-state [{::keys [next-state data timeout] :as env} state-key]
  (doseq [ref (get @active-ref state-key)]
    ;; notify components using this
    (swap! ref update :i inc))

  (assoc-in env [::fsm state-key]
    {:state next-state
     :data data
     :timeout timeout}))


(defn run-machine [^MachineInstance machine-instance state-key env {:keys [e] :as ev}]
  (let [tx-guard
        (js/Object.)

        machine-state
        (get-in env [::fsm state-key])

        STATE
        (:state machine-state)

        DATA
        (:data machine-state)

        machine
        (.-machine machine-instance)

        states
        (:states machine)

        handler-fn
        (or (get-in states [STATE e])
            ;; FIXME: should ::ALL events run always in addition to state specific events?
            (get-in states [::ALL e])
            (throw (js/Error.
                     (str "State Machine did not handle event"
                          "\n  machine " (:machine-id machine)
                          "\n  state " STATE
                          "\n  event " e))))

        tx-env
        (assoc env
          ::machine machine
          ::data DATA
          ::state STATE
          ::tx-guard tx-guard
          ;; in case a parent fsm has in tx and has this set
          ;; it'll be restored later by clear-internal-keys
          ::next-state STATE)

        ;; using or since handler should be allowed to return nil for convenience
        ;; signaling that no change should occur
        ;; (when some-condition? (do-stuff env))
        {NEXT-STATE ::next-state :as result}
        (or (handler-fn tx-env ev DATA) tx-env)]

    ;; (js/console.log "fsm result after handler" result STATE NEXT-STATE)

    (guard-check! result machine tx-guard [STATE e])

    (let [state-transition? (and NEXT-STATE (not= NEXT-STATE STATE))]

      ;; result didn't set ::fsm/next-state OR remained in same state
      ;; means we remain in same state, data or timeout may change independently

      (if-not state-transition?
        (-> result
            (cond->
              ;; if timeout is currently remove that
              (and (::timeout result) (:timeout machine-state))
              (sg/fx-timeout-clear state-key)

              ;; set new timeout if requested
              (::timeout result)
              (sg/fx-timeout state-key (::timeout result) (machine-instance ::timeout! {})))
            (store-machine-state state-key)
            (clear-internal-keys))

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

          (guard-check! result machine (::tx-guard tx-env) [STATE :exit])

          (let [result (or (enter-handler-fn result ev) result)]

            ;; (js/console.log "fsm result after enter" result)

            (guard-check! result machine (::tx-guard tx-env) [NEXT-STATE :enter])

            (-> result
                (assoc ::state NEXT-STATE)
                (cond->
                  ;; if timeout is currently remove that
                  (and (::timeout result) (:timeout machine-state))
                  (sg/fx-timeout-clear state-key)

                  ;; set new timeout if requested
                  (::timeout result)
                  (sg/fx-timeout state-key (::timeout result) (machine-instance ::timeout! {})))
                (store-machine-state state-key)
                (clear-internal-keys)))
          )))))

(defn create [machine-id]
  (->Machine
    machine-id
    noop
    {::ALL {}}))

(deftype MachineInstance [machine state-key snapshot]
  ;; want this to be read-only, so not defrecord
  ;; mostly to not make people think they can (assoc fsm :state :FOO) and that having any effect
  ;; only state change can come from events
  cljs.core/ILookup
  (-lookup [this kw not-found]
    (case kw
      :data (:data snapshot)
      :state (:state snapshot)
      :timeout (:timeout snapshot)
      not-found))

  (-lookup [this kw]
    (-lookup this kw nil))

  cljs.core/IFn
  (-invoke [this ev-id ev]
    (with-meta
      (assoc ev :e ev-id)
      {::sg/tx #(run-machine this state-key %1 %2)}
      )))

(defn- remove-ref [fsm state-key ref]
  ;; FIXME: what to do here? maybe on-shutdown event handler, so fsm can clean up?
  ;; (js/console.log "cleanup" state-key ref)
  (swap! active-ref update state-key disj ref))

(defn use [{:keys [machine-id] :as ^Machine fsm} init-arg]
  {:pre [(instance? Machine fsm)]}
  (let [ref
        (rt/claim-slot! ::use)

        {prev-fsm :fsm
         prev-init-arg :init-arg
         prev-key :state-key
         :as last-state}
        @ref

        rt-ref
        (::sg/runtime-ref rt/*env*)

        state-key
        [machine-id init-arg]]

    (when (or (not (identical? fsm prev-fsm))
              (not= init-arg prev-init-arg))
      (when prev-fsm
        (when (get @active-ref prev-key)
          (remove-ref prev-fsm prev-key ref)))

      (let [machine-instance (get @active-ref state-key)]

        (comp/set-cleanup! ref
          (fn []
            (when (get @active-ref state-key)
              (remove-ref fsm state-key ref))))

        ;; not initialized yet, run on-init
        (when-not machine-instance
          (sg/run-tx! rt-ref
            (with-meta
              ;; some kind of useful descriptor for devtools
              {:e ::init!
               :machine-id machine-id
               :init-arg init-arg}
              {::sg/tx
               (fn [env]
                 (let [guard
                       (js/Object.)

                       init-env
                       (assoc env ::machine fsm ::tx-guard guard ::data {} ::next-state :INITIAL)

                       {::keys [timeout] :as init-result}
                       (.init-fn fsm init-env init-arg)]

                   (when-not (identical? guard (::tx-guard init-result))
                     (throw
                       (ex-info
                         (str "fsm " machine-id " init did not return proper result")
                         {:env env
                          :init-arg init-arg
                          :result init-result})))

                   (swap! active-ref assoc state-key #{ref})

                   (-> init-result
                       (cond->
                         timeout
                         (sg/fx-timeout state-key timeout
                           ;; FIXME: kind of ugly to create this only to dispatch timeout event
                           (let [new-instance (MachineInstance. fsm state-key nil)]
                             (new-instance ::timeout! {}))))
                       (store-machine-state state-key)
                       (clear-internal-keys))))}))))

      (swap! ref assoc :init-arg init-arg :fsm fsm :state-key state-key :i 0))

    ;; FIXME: this sucks, have to create new instance of a thing here
    ;; returning an identical thing makes component think nothing changed and skip render
    ;; even though data may have actually changed. cannot just return the state data
    ;; since I want to maintain this combo for creating events
    ;;   (bind {:keys [state] :as fsm}
    ;;     (fsm/use ?fsm 1))
    ;;   :on-click (fsm :foo {})
    ;; vs having to
    ;;   :on-click (fsm/ev fsm :foo {})
    ;; not super important but this only gets called on fsm updates, so not too
    ;; bad to create this new instance each time. essentially just a small wrapper anyway.
    (MachineInstance.
      fsm
      state-key
      (get-in @rt-ref [::sg/kv ::fsm state-key]))))



(comment
  (defn do-input!
    [env {:keys [digit] :as ev} data]

    (let [new-input
          (conj (:input data) digit)

          ;; hard-coded for demo purposes, could be looked up from env
          combination
          [1 3 3 7]]

      (cond
        ;; check if code is correct
        (= new-input combination)
        (-> env
            (fsm/next-state :OPEN 5000)
            (fsm/data-update assoc :input [])
            ;; (sg/queue-fx :door-api :unlock)
            )

        ;; assuming fixed 4 digit input, fail when 4 wrong digits are entered
        (= (count new-input) (count combination))
        (-> env
            (fsm/next-state :FAILED 2000)
            (fsm/data-update assoc :input []))

        ;; remain closed
        :else
        (-> env
            (fsm/next-state :LOCKED 5000)
            (fsm/data-update assoc :input new-input)
            ))))

  (def ?lock-fsm
    (-> (fsm/create ::lock-fsm)
        (fsm/on-init
          (fn [env init-arg]
            (-> env
                (fsm/next-state :LOCKED)
                (fsm/data-reset
                  {:input []})
                ;; (sg/queue-fx :door-api :lock)
                )))

        (fsm/on :LOCKED ::input! do-input!)
        (fsm/on :FAILED ::input! do-input!)

        ;; reset input sequence after 5sec idle while locked
        (fsm/on-timeout :LOCKED
          (fn [env ev]
            (fsm/data-update env assoc :input [])))

        ;; just ignore further input while door is open
        (fsm/on :OPEN ::input!
          (fn [env ev]
            env))

        (fsm/on-timeout :OPEN
          (fn [env ev]
            (fsm/next-state env :LOCKED)))

        (fsm/on-timeout :FAILED
          (fn [env ev]
            (fsm/next-state env :LOCKED)))))


  ;; in component
  (bind {:keys [state data] :as fsm}
    (fsm/use ?lock-fsm 1))


  ;; in render
  [:div (pr-str state) " - " (pr-str data)
   [:div
    [:button {:on-click (fsm ::input! {:digit 1})} "1"]
    [:button {:on-click (fsm ::input! {:digit 3})} "3"]
    [:button {:on-click (fsm ::input! {:digit 7})} "7"]]]

  )

