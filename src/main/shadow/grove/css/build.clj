(ns shadow.grove.css.build
  (:require
    [shadow.build :as sb]
    [shadow.build.data :as data]
    [shadow.grove.css :as css]
    [shadow.grove.css.generate :as gen]
    [clojure.string :as str]
    [clojure.java.io :as io]))


(def output-id
  [:shadow.build.classpath/resource "shadow/grove/css/defs.cljs"])

(defn hook
  {::sb/stage :compile-finish}
  [{:keys [build-sources] :as state}]

  (let [namespaces
        (->> build-sources
             (map #(get-in state [:sources %]))
             (filter #(= :cljs (:type %)))
             (map :ns))

        all
        (into [] (for [ns namespaces
                       :let [ns-defs (get-in state [:compiler-env :cljs.analyzer/namespaces ns ::css/classes])]
                       class (vals ns-defs)]
                   class))

        {:keys [warnings css]}
        (gen/generate-css
          (gen/start)
          all)]

    (doseq [w warnings]
      (prn [:css-warning w]))

    (spit
      (doto (data/output-file state "grove.css")
        (io/make-parents))
      css)

    (-> state
        ;; we don't need to worry about modules here since closure is smart enough to move these around
        (update-in [:output output-id]
          (fn [output]
            (-> output
                (dissoc :eval-js)
                (assoc :compiled-at (System/currentTimeMillis)
                       :js
                       (str "goog.provide(\"shadow.grove.css.defs\");\n"
                            (->> all
                                 (map (fn [{:keys [css-id]}]
                                        ;; for development we just use the selector classname
                                        ;; for production it should collapse rules and may end up using
                                        ;; multiple classes to represent one css call
                                        (str "shadow.grove.css.defs." css-id " = \"" css-id "\";")))
                                 (str/join "\n"))
                            )))))
        ;; tell client to reload the output we generated
        ;; FIXME: only do this if we actually changed something
        (update-in [:shadow.build/build-info :compiled] conj output-id))))
