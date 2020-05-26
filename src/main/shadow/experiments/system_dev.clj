(ns shadow.experiments.system-dev
  (:require
    [clojure.java.io :as io]
    [shadow.experiments.system.runtime :as rt]))

(defonce instance-ref (atom nil))

(defn start [{:system/keys [main] :as config}]
  {:pre [(simple-symbol? main)]}
  (assert (nil? @instance-ref) "already started?")

  (require main)

  (let [services-sym (symbol (str main) "services")
        services-var (resolve services-sym)]
    (assert services-var "services var not found")

    (let [app
          (-> {:config config}
              (rt/init @services-var)
              (rt/start-all))]

      (reset! instance-ref app))))

(defn -main [config-path & args]
  (let [config-file (io/file config-path)
        config (read-string (slurp config-file))]
    (start config)))
