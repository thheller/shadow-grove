(ns dummy.system
  (:require [dummy.services.foo :as foo]))

(defn foo-start [{:keys [bar]}]
  [:foo bar])

(defn foo-stop [x])

(defn bar-start []
  :bar)

(defn bar-stop [x])

(def services
  {:foo
   {:depends-on {:bar [:bar]}
    :start foo-start
    :stop foo-stop}
   :bar
   {:start bar-start
    :stop bar-stop}})

(comment
  (require '[shadow.experiments.system.runtime :as rt])
  (set! *print-namespace-maps* false)
  (let [started
        (-> {}
            (rt/init services)
            (rt/start-all))

        stopped
        (rt/stop-all started)]

    (tap> {:started started
           :stopped stopped})
    ))
