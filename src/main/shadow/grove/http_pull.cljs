(ns shadow.grove.http-pull
  (:require
    [shadow.cljs.modern :refer (defclass)]
    [shadow.grove :as sg]
    [shadow.grove.components :as comp]
    [shadow.grove.runtime :as rt]
    [shadow.grove.http-fx :as http-fx]))

(defclass Pull
  (field ^PullRoot pull-root)
  (field arg)

  (field refs #{})

  (field result nil)
  (field timeout nil)

  (constructor [this pull-root arg]
    (set! this -pull-root pull-root)
    (set! this -arg arg))

  Object
  (add-ref [this ref]
    (set! refs (conj refs ref)))

  (remove-ref [this ref]
    (set! refs (disj refs ref))

    (when (empty? refs)
      ;; FIXME: what to do exactly?
      ;; remove instance? remove result?
      ;; :keepalive to keep result for certain time? maybe fn to check?
      (set! result nil)))

  (get-result [this]
    (when (not result)
      (.do-request this))

    result)

  (refresh! [this]
    (.do-request this))

  (do-request [this]
    (let [rt-ref
          (.-rt-ref pull-root)

          env
          {::sg/runtime-ref rt-ref}

          {:keys [request on-error on-success] :as config}
          (.-config pull-root)

          request-def
          (if (fn? request)
            ;; FIXME: does this really need env?
            (request env arg)
            request)]

      ;; (set! request-started (js/Date.now))

      ;; in case of refresh! result may already be set, keep that while request is running
      ;; FIXME: there should be option, :stale-ok or something
      (when-not result
        (set! result
          {:state :pending
           :last-update (js/Date.now)
           ;; :abort! (fn abort! [] ...)
           :refresh!
           (fn refresh! []
             (.refresh! this))
           }))

      (http-fx/do-request config env request-def
        (fn [xhr-result status sent-request xhr-req]
          (if (http-fx/request-error? status)
            (do (set! result
                  {:state :error
                   :status status
                   :result xhr-result ;; do not use :value, that should only exist for successful responses
                   :last-update (js/Date.now)})

                ;; even if there is no :on-error run as tx so it shows up in devtools
                (sg/run-tx! rt-ref
                  (with-meta
                    {:e (.-pull-id pull-root)
                     :status status
                     :result xhr-result
                     :request sent-request}
                    {::sg/tx
                     (fn [env ev]
                       (if-not on-error
                         (do (js/console.warn "request result in error response without handler" ev)
                             env)
                         (on-error env ev)))})))

            (let [value (if-some [transform (:transform config)]
                          ;; FIXME: should transform receive info about the request? 3rd arg?
                          (transform env xhr-result)
                          xhr-result)]

              (set! result
                {:state :success
                 :value value
                 :last-update (js/Date.now)
                 :refresh!
                 (fn refresh! []
                   (.refresh! this))})

              ;; even through most of these will be unused, provide them for devtools
              ;; makes it easier to see what request was sent and what the result was for debugging purposes
              ;; this is cheap so no reason to skip it, even if there is no :on-success in config
              (sg/run-tx! rt-ref
                (with-meta
                  ;; FIXME: remix :e keyword so error/success is immediately obvious in devtools?
                  {:e (.-pull-id pull-root)
                   :status status
                   :result xhr-result
                   :value value
                   :request sent-request}
                  {::sg/tx
                   (fn [env ev]
                     ;; this is optional, so no need to warn
                     (if-not on-success
                       env
                       (on-success env ev)
                       ))}))))

          (when-some [refresh-every (:refresh-every (.-config pull-root))]
            (when timeout
              (js/clearTimeout timeout))

            (set! timeout (js/setTimeout #(.refresh! this) refresh-every)))

          (.invalidate-refs this))))

    js/undefined)

  (invalidate! [this]
    (if-not (seq refs)
      ;; if nobody is watching just remove result
      (set! result nil)
      ;; if active run request immediately
      ;; FIXME: maybe delay a bit to actually fit fx model?
      (.do-request this)))

  (invalidate-refs [this]
    (let [now (js/Date.now)]
      ;; invalidate every component currently using this
      (doseq [ref refs]
        (swap! ref assoc :last-update now)))))

(defclass PullRoot
  (field rt-ref)
  (field pull-id)
  (field config)

  (field instances {})

  (constructor [this rt-ref pull-id config]
    (set! this -rt-ref rt-ref)
    (set! this -pull-id pull-id)
    (set! this -config config))

  cljs.core/IFn
  (-invoke
    ([this]
     (.run-in-slot! this nil))
    ([this arg]
     (.run-in-slot! this arg)))

  Object
  (run-in-slot! [this arg]
    (let [local-ref
          (::sg/runtime-ref rt/*env*)

          ref
          (rt/claim-slot! pull-id)

          {^Pull prev-instance :instance}
          @ref

          ^Pull instance
          (.get-instance this arg)]

      (when-not (identical? rt-ref local-ref)
        (throw (ex-info "component part of different rt-ref" {:rt-ref rt-ref :local-ref local-ref})))

      ;; request-key changing implies data is stale and needs to be refreshed
      (when-not (identical? prev-instance instance)

        ;; no longer interested in result for old query
        ;; FIXME: should it be? might be better to show stale data instead of no data at all?
        (when prev-instance
          (.remove-ref prev-instance ref))

        (swap! ref assoc :instance instance)

        (comp/set-cleanup! ref
          (fn []
            (.remove-ref instance ref)))

        (.add-ref instance ref))

      ;; FIXME: show result of prev-instance when new request was just started? :stale-ok option?

      ;; return the :result map unmodified, so that it stays identical and is not computed when the slot runs
      ;; all modifications are handled async in instance

      (let [result (.get-result instance)]
        ;; auto suspend
        (when (and (= :pending (:state result))
                   (not (false? (:suspend config))))
          (sg/suspend!))

        result)))

  (get-instance [this arg]
    (or (get instances arg)
        (let [instance (Pull. this arg)]
          (set! instances (assoc instances arg instance))
          instance
          )))

  (invalidate-all! [this]
    (doseq [^Pull instance (vals instances)]
      (.invalidate! instance)))

  (invalidate-one! [this arg]
    (when-some [^Pull instance (get instances arg)]
      (.invalidate! instance)))
  )

(defn setup
  ([pull-id config]
   (setup (sg/get-runtime :app) pull-id config))
  ([rt-ref pull-id config]
   ;; forcing this to the declared outside components
   ;; so that no other component local state leaks into this definition
   ;; which would cause things to be called repeatedly in slots
   ;; creating all sorts of extra headaches trying to figure out what changed
   (when rt/*env*
     (throw (ex-info "http-pull can only be created outside components and should be a top level def!" {})))

   (let [config
         (update config :response-formats http-fx/merge-right http-fx/default-response-formats)

         pull-root
         (PullRoot. rt-ref pull-id config)]

     ;; FIXME: storing this somewhere might make sense for devtools
     ;; for now can just access the returned value via REPL or so
     ;; (swap! rt-ref assoc-in [::defined pull-id] pull-root)

     ;; implements IFn, intended to be called in component bind
     ;; (bind request-info (the-thing maybe-param))

     ;; FIXME: implement protocol so events can prefetch and stuff
     pull-root)))

;; FIXME: should this be fx?
(defn invalidate!
  ([^PullRoot pull-root]
   (.invalidate-all! pull-root))
  ([^PullRoot pull-root arg]
   (.invalidate-one! pull-root arg)))

(comment
  (def example-pull
    (setup
      ::template-files
      {:suspend false ;; default true, auto suspend slot when data not yet loaded

       ;; with arg received from slot call, construct the request description
       :request-format :edn
       :request
       (fn [env arg]
         {:uri "/foo/bar"
          :method :POST
          :body arg})

       ;; by default returns remote data as result value, optionally transform it here
       ;; component specific transforms should be done in dedicated slot
       :transform
       (fn [env data arg]
         data)

       ;; optional handler to write data to grove kv or do other stuff a regular tx could do
       ;; slot will always just receive the data or transform result
       :on-success
       (fn [env data arg]
         (kg/add env :some-table data))

       ;; optionally clean up when components using this all unmount?
       :cleanup-timeout 60000 ;; wait 60sec before cleanup
       :cleanup (fn [env arg])

       :on-error
       (fn [env data arg]
         env)
       })))

