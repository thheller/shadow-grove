(ns shadow.experiments.archetype.website
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [reitit.core :as rc]
    [shadow.experiments.system.runtime :as rt]
    [shadow.undertow :as undertow]
    ))

(defonce runtime-ref (atom nil))

(defn find-loaded-namespaces [ns-root]
  (let [ns-root-str (name ns-root)]
    (->> (all-ns)
         (filter #(str/starts-with? (name (ns-name %)) ns-root-str))
         )))

(defn find-web-routes [ns-root]
  (for [ns (find-loaded-namespaces ns-root)
        var (vals (ns-publics ns))
        :when (contains? (meta var) :http/handle)]
    var))

(defn build-routing-data [vars]
  (reduce
    (fn [tbl var]
      (let [{:http/keys [handle] :as m} (meta var)
            [method pattern] handle]

        (assoc-in tbl [pattern method]
          {:handler var
           :fqn (symbol (str (:ns m)) (str (:name m)))})))
    {}
    vars))

(comment
  (-> (find-web-routes 'dummy.website)
      (build-routing-data)
      (rc/router)
      (rc/match-by-path "/product/1")
      ))

(def resp-404
  {:status 404
   :headers {"content-type" "text/plain"}
   :body "Not found."})

;; old habits die hard, I prefer upper case
(def uc-method
  {:get :GET
   :head :HEAD
   :post :POST
   :put :PUT
   :delete :DELETE
   :connect :CONNECT
   :options :OPTIONS
   :trace :TRACE
   :patch :PATCH})

(defn -main [path-to-config & args]
  (let [time-start
        (System/currentTimeMillis)

        config
        (-> (io/file path-to-config)
            (slurp)
            (edn/read-string))

        app-ns
        (get config :app/ns)

        ns-count-before
        (count (all-ns))]

    (require app-ns)

    (let [ns-count-after
          (count (all-ns))

          time-after-load
          (System/currentTimeMillis)

          app-root
          (-> (get config :app/root ".")
              (io/file)
              (.getAbsoluteFile))

          adjust-config
          (ns-resolve app-ns 'adjust-config)

          config
          (-> config
              (cond->
                adjust-config
                (adjust-config)))


          services-fn
          (ns-resolve app-ns 'services)

          router
          (-> (find-web-routes app-ns)
              (build-routing-data)
              (rc/router))

          base-app
          {::time-start time-start
           ::time-after-load time-after-load
           ::ns-count-before ns-count-before
           ::ns-count-after ns-count-after
           ::shutdown! false
           :config config
           ::router router
           :fs-root app-root}

          services
          (services-fn config)

          runtime
          (-> base-app
              (rt/init services)
              (rt/start-all))


          ring-fn
          (fn [{:keys [request-method uri] :as req}]
            (let [match (rc/match-by-path router uri)]
              (if-not match
                resp-404
                (let [data (:data match)

                      req-config
                      (or (get data (get uc-method request-method))
                          (get data request-method)
                          (get data :ANY))]

                  ;; FIXME: handle exceptions
                  (if-not req-config
                    resp-404
                    (let [handler (:handler req-config)
                          path-params (:path-params match)
                          req-env (assoc @runtime-ref :http/request req)]
                      (if-not (seq path-params)
                        (handler req-env)
                        ;; FIXME: handle other params, use reitit stuff more
                        ;; FIXME: handle /path/{id:int} notation
                        (handler req-env path-params)
                        )))))))

          ;; basic inner handler setup
          http-handler
          [::undertow/ws-upgrade
           [::undertow/ws-ring {:handler-fn ring-fn}]
           [::undertow/blocking
            [::undertow/ring {:handler-fn ring-fn}]]]

          ;; static from classpath or files
          http-handler
          (reduce
            (fn [http-handler path]
              (if (str/starts-with? path "classpath:")
                [::undertow/classpath {:root (subs path 10)} http-handler]
                [::undertow/file {:root-dir (io/file app-root path)} http-handler]))
            http-handler
            (:http/roots config))

          ;; final handler, compress
          http-handler
          [::undertow/compress {} http-handler]

          http-server
          (undertow/start
            {:host "0.0.0.0"
             :port 8090}
            http-handler)]

      (prn :started)
      (prn runtime)

      (reset! runtime-ref (assoc runtime :http/server http-server
                                         :http/handler http-handler))

      (while (not @(::shutdown! @runtime-ref))
        (Thread/sleep 2500))

      (undertow/stop http-server)

      (rt/stop-all @runtime-ref)
      (prn :done))))