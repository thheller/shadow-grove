(ns shadow.experiments.grove.transit
  (:require
    [cognitect.transit :as transit]
    [shadow.experiments.grove.runtime :as rt]))

;; FIXME: custom handler config options

(defn init [rt-env]
  (let [tr (transit/reader :json)
        tw (transit/writer :json)

        transit-read
        (fn transit-read [data]
          (transit/read tr data))

        transit-str
        (fn transit-str [obj]
          (transit/write tw obj))]

    (assoc rt-env
      ::rt/transit-read transit-read
      ::rt/transit-str transit-str)))


