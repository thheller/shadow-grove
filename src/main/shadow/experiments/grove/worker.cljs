(ns shadow.experiments.grove.worker
  "grove - a small wood or forested area (ie. trees)
   a mini re-frame/fulcro hybrid. re-frame event styles + somewhat normalized db"
  (:require
    [clojure.set :as set]
    [cognitect.transit :as transit]
    [shadow.experiments.grove.db :as db])
  (:import [goog.structs CircularBuffer]))

(defn set-conj [x y]
  (if (nil? x) #{y} (conj x y)))

(defn vec-conj [x y]
  (if (nil? x) [y] (conj x y)))

(defn send-to-main [{::keys [transit-str] :as env} msg]
  ;; (js/console.log "main-write" env msg)
  (js/self.postMessage (transit-str msg)))

;; FIXME: very inefficient, maybe worth maintaining an index
(defn invalidate-queries! [active-queries keys-to-invalidate]
  ;; (js/console.log "invalidating queries" keys-to-invalidate)
  (reduce-kv
    (fn [_ query-id ^ActiveQuery query]
      ;; don't need to check if already pending
      (when-not (.-pending? query)
        (run!
          (fn [key]
            (when (.affected-by-key? query key)
              ;; FIXME: should this instead collect all the queries that need updating
              ;; and then process them in batch as well so we just send one message to main?
              ;; might be easier to schedule?
              (.refresh! query)))
          keys-to-invalidate))
      nil)
    nil
    active-queries))

(defn invalidate-keys! [{::keys [active-queries-ref invalidate-keys-ref] :as env}]
  (invalidate-queries! @active-queries-ref @invalidate-keys-ref)
  (reset! invalidate-keys-ref #{}))

(defn tx*
  [{::keys [event-config fx-config data-ref invalidate-keys-ref] :as env}
   [ev-id & params :as tx]]
  {:pre [(vector? tx)
         (keyword? ev-id)]}
  ;; (js/console.log ::db-tx ev-id tx env)

  (let [{:keys [interceptors ^function handler-fn] :as event}
        (get event-config ev-id)]

    (if-not event
      (js/console.warn "no event handler for" ev-id params env)

      (let [before @data-ref

            tx-db
            (db/transacted before)

            tx-env
            (assoc env ::event-id ev-id
                       ::tx tx
                       :db tx-db)

            {^clj tx-after :db :as result}
            (apply handler-fn tx-env params)]

        ;; FIXME: move all of this out to interceptor chain. including DB stuff

        (reduce-kv
          (fn [_ fx-key value]
            (let [fx-fn (get fx-config fx-key)]
              (if-not fx-fn
                (js/console.warn "invalid fx" fx-key value)
                (let [transact-fn
                      (fn [fx-tx]
                        ;; FIXME: should probably track the fx causing this transaction and the original tx
                        ;; FIXME: should probably prohibit calling this while tx is still processing?
                        ;; just meant for async events triggered by fx
                        (tx* env fx-tx))
                      fx-env
                      (assoc env :transact! transact-fn)]
                  (fx-fn fx-env value)))))
          nil
          (dissoc result :db))

        (when (seq interceptors)
          (js/console.warn "TBD, ignored interceptors for event" ev-id))

        (when tx-after
          (let [{:keys [data keys-new keys-removed keys-updated] :as result}
                (.commit! tx-after)

                keys-to-invalidate
                (set/union keys-new keys-removed keys-updated)]

            (when-not (identical? @data-ref before)
              (throw (ex-info "someone messed with app-state while in tx" {})))

            (reset! data-ref data)

            (when-not (identical? before data)
              ;; instead of invalidating queries for every processed tx
              ;; they are batched to update at most once in a certain interval
              ;; FIXME: make this configurable, benchmark
              ;; FIXME: maybe debounce again per query?
              (when (empty? @invalidate-keys-ref)
                (js/setTimeout #(invalidate-keys! env) 10))

              (swap! invalidate-keys-ref set/union keys-to-invalidate))

            data))))))

(defn run-tx [env tx]
  {:pre [(vector? tx)
         (keyword? (first tx))]}
  (tx* env tx))

(defn reg-event-fx [app-ref ev-id interceptors handler-fn]
  {:pre [(keyword? ev-id)
         (vector? interceptors)
         (fn? handler-fn)]}
  (swap! app-ref assoc-in [::event-config ev-id]
    {:handler-fn handler-fn
     :interceptors interceptors})
  app-ref)

(defn reg-fx [app-ref fx-id handler-fn]
  (swap! app-ref assoc-in [::fx-config fx-id] handler-fn)
  app-ref)

(defonce query-queue (js/Promise.resolve))

(deftype ActiveQuery
  [env
   query-id
   query
   ^:mutable read-keys
   ^:mutable read-result
   ^:mutable pending?
   ^:mutable destroyed?]

  Object
  (do-read! [this]
    (set! pending? false)

    (let [{::keys [data-ref]} env
          observed-data (db/observed @data-ref)
          result (db/query env observed-data query)]

      ;; remember this even is query is still loading
      (set! read-keys @observed-data)

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

  (actually-refresh! [this]
    ;; query might have been destroyed while being queued
    (when (and pending? (not destroyed?))
      (.do-read! this)))

  (refresh! [this]
    ;; this may be called multiple times during one invalidation cycle right now
    ;; so we queue to ensure its only sent out once
    ;; FIXME: fix invalidate-queries! so this isn't necessary
    (when (and (not pending?) (not destroyed?))
      (set! pending? true)
      (.then query-queue #(.actually-refresh! this))))

  (affected-by-key? [this key]
    (contains? read-keys key)))

(defmulti worker-message (fn [env msg] (first msg)) :default ::default)

(defmethod worker-message ::default [env msg]
  (js/console.warn "unhandled worker msg" msg))

(defmethod worker-message :query-init
  [{::keys [active-queries-ref] :as env} [_ query-id query opts]]
  (let [q (ActiveQuery. env query-id query nil nil true false)]
    (swap! active-queries-ref assoc query-id q)
    (.do-read! q)))

(defmethod worker-message :query-destroy
  [{::keys [active-queries-ref] :as env} [_ query-id]]
  (when-some [query (get @active-queries-ref query-id)]
    (set! (.-destroyed? query) true)
    (swap! active-queries-ref dissoc query-id)))

(defmethod worker-message :tx [env [_ tx]]
  (tx* env tx))

(defmethod worker-message :stream-init
  [{::keys [active-streams-ref] :as env} [_ stream-id stream-key opts :as msg]]
  ;; (js/console.log "stream-init" env stream-key msg)
  (let [{:keys [^CircularBuffer buffer] :as stream-info} (get @active-streams-ref stream-key)]
    (if-not stream-info
      (js/console.warn "stream not found, can't init" msg)
      (do (swap! active-streams-ref update-in [stream-key :subs] set-conj stream-id)
          (send-to-main env
            [:stream-msg stream-id {:op :init
                                    :item-count (.getCount buffer)
                                    :items (.getNewestValues buffer
                                             (js/Math.min 10 (.getCount buffer)))}]
            )))))

;; FIXME: this shouldn't be worker dependent
(defonce known-envs-ref (atom {}))

(defn prepare [init-env data-ref app-id]
  (let [env-ref
        (atom
          (assoc init-env
            ::app-id app-id
            ::event-config {}
            ::fx-config {}
            ::data-ref data-ref))]

    (swap! known-envs-ref assoc app-id env-ref)
    env-ref))

;; FIXME: only this should be worker specific
(defn init! [app-ref]
  (let [tr (transit/reader :json)
        tw (transit/writer :json)

        transit-read
        (fn transit-read [data]
          (transit/read tr data))

        transit-str
        (fn transit-str [obj]
          (transit/write tw obj))

        active-queries-ref
        (atom {})

        active-streams-ref
        (atom {})

        env
        {::active-queries-ref active-queries-ref
         ::active-streams-ref active-streams-ref
         ::invalidate-keys-ref (atom #{})
         ::transit-read transit-read
         ::transit-str transit-str}]

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

    (reg-fx app-ref :stream-merge
      (fn [env m]
        (reduce-kv
          (fn [env stream-key items]
            (when (seq items)
              (let [stream (get @active-streams-ref stream-key)]
                (if-not stream
                  (js/console.warn "stream not found, can't merge" stream-key items)
                  (let [{:keys [subs ^CircularBuffer buffer opts]} stream
                        new-items (->> (.getValues buffer)
                                       (into items)
                                       (sort-by :added-at)
                                       (reverse))

                        buffer (CircularBuffer. (:capacity opts 1000))]

                    (doseq [item new-items]
                      (.add buffer item))

                    (swap! active-streams-ref assoc-in [stream-key :buffer] buffer)

                    (let [new-head (into [] (take 10) new-items)]
                      (reduce
                        (fn [_ sub-id]
                          (send-to-main env [:stream-msg
                                             sub-id
                                             {:op :reset
                                              :item-count (.getCount buffer)
                                              :items new-head}]))
                        nil
                        subs))))))
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

(defn stream-setup [app-ref  stream-key opts]
  (let [{::keys [active-streams-ref]} @app-ref

        stream
        {:stream-key stream-key
         :opts opts
         :subs #{}
         :buffer (CircularBuffer. (:capacity opts 1000))}]

    (swap! active-streams-ref assoc stream-key stream)))

(defn refresh-all-queries! [app-ref]
  (let [{::keys [active-queries-ref]} @app-ref]
    (doseq [^ActiveQuery query (vals @active-queries-ref)]
      ;; recomputes and updates main if data changed
      (.actually-refresh! query))))
