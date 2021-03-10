(ns shadow.experiments.grove.worker
  "grove - a small wood or forested area (ie. trees)
   a mini re-frame/fulcro hybrid. re-frame event styles + somewhat normalized db"
  (:require
    [shadow.experiments.grove.transit :as transit]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove.events :as ev]
    [shadow.experiments.grove.eql-query :as eql])
  (:import [goog.structs CircularBuffer]))

(set! *warn-on-infer* false)

(defn send-to-main [{::rt/keys [transit-str] :as env} msg]
  ;; (js/console.log "main-write" env msg)
  (js/self.postMessage (transit-str msg)))

(defn run-tx [env tx]
  (ev/run-tx env tx))

(defn reg-event [app-ref ev-id handler-fn]
  (ev/reg-event app-ref ev-id handler-fn))

(defn reg-fx [app-ref fx-id handler-fn]
  (ev/reg-fx app-ref fx-id handler-fn))

(defonce query-queue (js/Promise.resolve))

(deftype ActiveQuery
  [env
   query-id
   query
   ^:mutable read-keys
   ^:mutable read-result
   ^:mutable destroyed?]

  ev/IQuery
  (query-refresh! [this]
    (when-not destroyed?
      (.do-read! this)))

  Object
  (do-read! [this]
    (let [observed-data (db/observed @(::rt/data-ref env))
          result (eql/query env observed-data query)
          new-keys (db/observed-keys observed-data)]

      ;; remember this even if query is still loading
      (ev/index-query* env query-id read-keys new-keys)
      (set! read-keys new-keys)

      ;; if query is still loading don't send to main
      (when (and (not (keyword-identical? result :db/loading))
                 ;; empty result likely means the query is no longer valid
                 ;; eg. deleted ident. don't send update, will likely be destroyed
                 ;; when other query updates
                 (some? result)
                 (not (empty? result))
                 ;; compare here so main doesn't need to compare again
                 (not= result read-result))

        (set! read-result result)
        ;; (js/console.log "did-read" read-keys read-result @data-ref)
        ;; FIXME: we know which data the client already had. could skip over those parts
        ;; but computing that might be more expensive than just replacing it?
        ;; might speed things up with basic merge logic
        (send-to-main env [:query-result query-id result]))))

  (destroy! [this]
    (set! destroyed? true)
    (ev/unindex-query* env query-id read-keys)))

(defmulti worker-message (fn [env msg] (first msg)) :default ::default)

(defmethod worker-message ::default [env msg]
  (js/console.warn "unhandled worker msg" msg))

(defmethod worker-message :query-init
  [{::rt/keys [active-queries-map] :as env} [_ query-id query opts]]
  (let [q (ActiveQuery. env query-id query nil nil false)]
    (.set active-queries-map query-id q)
    (.do-read! q)))

(defmethod worker-message :query-destroy
  [{::rt/keys [active-queries-map] :as env} [_ query-id]]
  (when-some [query (.get active-queries-map query-id)]
    (.delete active-queries-map query-id)
    (.destroy! query)))

(defmethod worker-message :tx [env [_ tx]]
  (ev/tx* env tx))

(defmethod worker-message :tx-return [env [_ tx-id tx]]
  (let [result (ev/tx* env tx)]
    (send-to-main env [:tx-result tx-id result])
    ))

(defmethod worker-message :stream-sub-init
  [{::keys [active-streams-ref] :as env} [_ stream-id stream-key opts :as msg]]
  ;; (js/console.log "stream-init" env stream-key msg)
  (let [{:keys [^CircularBuffer buffer] :as stream-info} (get @active-streams-ref stream-key)]
    (if-not stream-info
      (js/console.warn "stream not found, can't init" msg)
      (do (swap! active-streams-ref update-in [stream-key :subs] ev/set-conj stream-id)
          (send-to-main env
            [:stream-msg stream-id {:op :init
                                    :item-count (.getCount buffer)
                                    :items (.getNewestValues buffer
                                             (js/Math.min 50 (.getCount buffer)))}]
            )))))

(defmethod worker-message :stream-sub-destroy
  [{::keys [active-streams-ref] :as env} [_ stream-id stream-key :as msg]]
  ;; (js/console.log "stream-init" env stream-key msg)
  (let [{:keys [^CircularBuffer buffer] :as stream-info} (get @active-streams-ref stream-key)]
    (if-not stream-info
      (js/console.warn "stream not found, can't destroy" msg)
      (swap! active-streams-ref update-in [stream-key :subs] disj stream-id)
      )))

(defmethod worker-message :stream-clear
  [{::keys [active-streams-ref] :as env} [_ stream-key :as msg]]
  ;; (js/console.log "stream-init" env stream-key msg)
  (let [{:keys [^CircularBuffer buffer opts] :as stream-info} (get @active-streams-ref stream-key)]
    (swap! active-streams-ref assoc-in [stream-key :buffer] (CircularBuffer. (:capacity opts 1000)))
    ))



;; FIXME: only this should be worker specific
(defn init! [app-ref]
  (let [active-streams-ref
        (atom {})

        {::rt/keys [transit-str transit-read]}
        @app-ref

        env
        {::active-streams-ref active-streams-ref}]

    (when-not (and transit-str transit-read)
      (throw (ex-info "shadow.experiments.grove.transit not initialized!" {})))

    (swap! app-ref merge env)

    #_(set! js/self -onerror
        (fn [e]
          ;; FIXME: tell main?
          (js/console.warn "unhandled error" e)))

    (reg-fx app-ref :stream-add
      (fn [{:keys [] :as env} items]
        (reduce
          (fn [_ [stream-key stream-item]]
            (let [stream (get @active-streams-ref stream-key)]
              (if-not stream
                (js/console.warn "stream not found, can't add" stream-key stream-item)
                (let [{:keys [subs ^CircularBuffer buffer]} stream
                      stream-item (assoc stream-item :added-at (js/Date.now))]

                  (.add buffer stream-item)

                  (reduce
                    (fn [_ sub-id]
                      (send-to-main env [:stream-msg
                                         sub-id
                                         {:op :add
                                          :item stream-item}]))
                    nil
                    subs)))))
          nil
          items)))

    (reg-fx app-ref :stream-init
      (fn [{::keys [active-streams-ref] :as env} m]
        (reduce-kv
          (fn [env stream-key opts]
            (when-not (contains? @active-streams-ref stream-key)
              (swap! active-streams-ref assoc stream-key
                {:stream-key stream-key
                 :opts opts
                 :subs #{}
                 :buffer (CircularBuffer. (:capacity opts 1000))}))
            env)
          env
          m)))

    (reg-fx app-ref :ui/redirect!
      (fn [env token]
        (send-to-main env [:ui/redirect! token])))

    (js/self.addEventListener "message"
      (fn [e]
        (let [msg (transit-read (.-data e))]
          ;; (js/console.log "worker-msg" (first msg) msg)
          (worker-message @app-ref msg))))

    (js/postMessage (transit-str [:worker-ready]))
    ))

(defn stream-setup [app-ref stream-key opts]
  (let [{::keys [active-streams-ref]} @app-ref

        stream
        {:stream-key stream-key
         :opts opts
         :subs #{}
         :buffer (CircularBuffer. (:capacity opts 1000))}]

    (swap! active-streams-ref assoc stream-key stream)))

(defn refresh-all-queries! [app-ref]
  (let [{::rt/keys [active-queries-map]} @app-ref]
    (.forEach active-queries-map
      (fn [query query-id]
        (.do-read! query)))))
