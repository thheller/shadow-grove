(ns shadow.experiments.grove-main
  "grove - a small wood or forested area (ie. trees)
   a mini re-frame/fulcro hybrid. re-frame event styles + somewhat normalized db"
  (:require
    [shadow.experiments.arborist.protocols :as p]
    [shadow.experiments.arborist.components :as comp]
    [shadow.experiments.arborist :as sa]
    [goog.async.nextTick]
    [cognitect.transit :as transit]))

(defonce active-roots-ref (atom {}))

(defn send-to-worker [{::keys [worker transit-str] :as env} msg]
  ;; (js/console.log "worker-write" env msg)
  (js/goog.async.nextTick #(.postMessage worker (transit-str msg))))

(defonce query-id-seq (atom 0))

(defn make-query-id []
  (swap! query-id-seq inc))

(deftype QueryHook
  [ident
   query
   component
   idx
   env
   query-id
   ^:mutable ready?
   ^:mutable read-result]

  p/IBuildHook
  (hook-build [this c i]
    (QueryHook. ident query c i (comp/get-env c) (make-query-id) false nil))

  p/IHook
  (hook-init! [this]
    (let [{::keys [active-queries-ref]} env]
      ;; FIXME: clear out some fields? probably not necessary, this will be GC'd anyways
      (swap! active-queries-ref assoc query-id this))
    (send-to-worker env [:query-init query-id ident query]))

  ;; FIXME: async queries
  (hook-ready? [this] ready?)
  (hook-value [this] read-result)

  ;; node deps changed, node may have too
  (hook-deps-update! [this val]
    true)

  ;; node was invalidated and needs update, but its dependencies didn't change
  (hook-update! [this]
    true)

  (hook-destroy! [this]
    (send-to-worker env [:query-destroy query-id])
    (let [{::keys [active-queries-ref]} env]
      (swap! active-queries-ref dissoc query-id this)))

  Object
  (set-data! [this data]
    (set! ready? true)
    (set! read-result data)
    (comp/hook-ready! component idx)))

(defn query
  ([query]
   {:pre [(vector? query)]}
   (QueryHook. nil query nil nil nil nil false nil))
  ([ident query]
   {:pre [ ;; (db/ident? ident) FIXME: can't access db namespace in main, move to protocols?
          (vector? query)]}
   (QueryHook. ident query nil nil nil nil false nil)))

(defn tx*
  [{::keys [config-ref data-ref active-queries-ref]
    ::comp/keys [ev-id]
    :as env}
   params]
  (js/console.log ::tx env params))

(defn tx [env e params]
  (tx* env params))

(defn run-tx [env other params]
  (tx* (assoc env ::comp/ev-id other) params))

(defn form [defaults])

(defn form-values [form])

(defn form-reset! [form])


;; not using an atom as env to make it clearer that it is to be treated as immutable
;; can put mutable atoms in it but env once created cannot be changed.
;; a node in the tree can modify it for its children but only on create.

(defn init [{::comp/keys [scheduler] :as env} app-id worker tr tw]
  (let [transit-read
        (fn transit-read [data]
          (transit/read tr data))

        transit-str
        (fn transit-str [obj]
          (transit/write tw obj))

        active-queries-ref
        (atom {})

        env
        (assoc env
          ::app-id app-id
          ::worker worker
          ::transit-read transit-read
          ::transit-str transit-str
          ::active-queries-ref active-queries-ref)]

    (.addEventListener worker "message"
      (fn [e]
        (let [msg (transit-read (.-data e))]
          ;; everything was already async so just keep delaying
          ;; msg read could have taken a long time so just give the browser a chance
          (js/goog.async.nextTick
            (fn []
              (let [[op & args] msg]

                ;; (js/console.log "main read took" (- t start))
                (case op
                  :worker-ready
                  (js/console.log "worker is ready")

                  :query-result
                  (let [[query-id result] args
                        ^QueryHook q (get @active-queries-ref query-id)]
                    (when q
                      ;; FIXME: actually schedule, shouldn't run now thats just the only one implemented currently
                      (p/run-now! scheduler #(.set-data! q result))))

                  (js/console.warn "unhandled main msg" op msg))))))))
    env))

(defn start [env root-el root-node]
  (let [active (get @active-roots-ref root-el)]
    (if-not active
      (let [root (sa/dom-root root-el env)]
        (sa/update! root root-node)
        (swap! active-roots-ref assoc root-el {:env env :root root :root-el root-el})
        ::started)

      (let [{:keys [root]} active]
        (assert (identical? env (:env active)) "can't change env between restarts")
        (sa/update! root root-node)
        ::updated
        ))))

(defn stop [root-el]
  (when-let [{::keys [app-root] :as env} (get @active-roots-ref root-el)]
    (swap! active-roots-ref dissoc root-el)
    (p/destroy! app-root)
    (dissoc env ::app-root ::root-el)))