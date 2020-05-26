(ns shadow.experiments.system.runtime
  (:require [clojure.string :as str]))

(defn- rt-state? [x]
  (and (map? x) (map? (::app x))))

(defn topo-sort-services
  [{:keys [services deps visited] :as state}
   service-key]
  (let [{:keys [depends-on] :as svc-def}
        (get services service-key)

        dep-keys
        (->> (vals depends-on)
             (map (fn [dep-spec]
                    (cond
                      (vector? dep-spec)
                      (first dep-spec)
                      (keyword? dep-spec)
                      dep-spec
                      :else
                      (throw (ex-info "invalid :depends-on value" {:dep service-key :entry dep-spec :all svc-def})))))
             (vec))]

    (cond
      ;; undefined service dependency is ok, assuming it is provided
      (nil? svc-def)
      state

      (contains? deps service-key)
      (throw (ex-info "service circular dependeny" {:deps deps :name service-key}))

      (contains? visited service-key)
      state

      :else
      (-> state
          (update :visited conj service-key)
          (update :deps conj service-key)
          (as-> state
            (reduce topo-sort-services* state dep-keys))
          (update :deps disj service-key)
          (update :order conj service-key)))))

(defn setup [services]
  (reduce
    topo-sort-services
    {:deps #{}
     :visited #{}
     :order []
     :services services}
    (keys services)))

(defn init [state services]
  {:pre [(map? state)
         (map? services)]}
  (assoc state ::app (setup services)))

;; stopping

(defn- stop-service
  [{::keys [app] :as state} service-id]
  (if-let [service (get state service-id)]
    (if-let [{:keys [stop] :as service-def}
             (get-in app [:services service-id])]
      (do (when stop
            (stop service))
          (dissoc state service-id))
      ;; not defined, do nothing
      state)
    ;; not present, do nothing
    state))

(defn stop-all
  [{::keys [app] :as state}]
  {:pre [(rt-state? state)]}
  (let [stop-order (reverse (:order app))]
    (reduce stop-service state stop-order)
    ))

(defn stop-single
  [state service]
  {:pre [(rt-state? state)]}
  (stop-service state service))

(defn stop-many
  [state services]
  (reduce stop-single state services))

;; starting

(defn- start-one
  [{::keys [app] :as state} service-id]
  ;; already present, assume its started
  (if (contains? state service-id)
    state
    ;; lookup up definition, get deps (assumes deps are already started), start
    (if-let [{:keys [depends-on start] :as service-def}
             (get-in app [:services service-id])]
      (let [deps
            (reduce-kv
              (fn [deps dep-key dep-spec]
                (cond
                  (keyword? dep-spec)
                  (assoc deps dep-key (get state dep-spec))

                  (vector? dep-spec)
                  (assoc deps dep-key (get-in state dep-spec))

                  :else
                  (throw (ex-info "how did this get here?" {}))
                  ))
              {}
              depends-on)

            service-instance
            (if (empty? deps)
              (start)
              (start deps))]

        (assoc state service-id service-instance))
      ;; not defined
      (throw (ex-info (format "cannot start/find undefined service %s (%s)" service-id (str/join "," (keys state))) {:service service-id :provided (keys state)}))
      )))

(defn- start-many
  "start services and return updated state
   will attempt to stop all if one startup fails"
  [state services]
  {:keys [(rt-state? state)]}
  (loop [state
         state

         start
         services

         started
         []]

    (let [service-id (first start)]
      (if (nil? service-id)
        ;; nothing left to start
        state

        ;; start next service
        (let [state
              (try
                (start-one state service-id)
                (catch Exception e
                  ;; FIXME: ignores an exception if a rollback fails
                  (try
                    (stop-many state started)
                    (catch Exception x
                      (prn [:failed-to-rollback started x])))

                  (throw (ex-info "failed to start service" {:id service-id} e))))]
          (recur state (rest start) (conj started service-id)))
        ))))

(defn start-all
  "start all services in dependency order, will attempt to properly shutdown if once service fails to start"
  [{::keys [app] :as state}]
  {:pre [(rt-state? state)]}
  (start-many state (:order app)))

(defn start-single
  "start a single service (and its deps)"
  [{::keys [app] :as state} service]
  {:pre [(rt-state? state)]}
  (let [start-order (topo-sort-services app [service])]
    (start-many state start-order)))

(defn start-services
  "start a multiple services (and their deps)"
  [state services]
  {:pre [(rt-state? state)]}
  (reduce start-single state services))
