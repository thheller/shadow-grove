(ns shadow.grove.transit
  (:require
    [cognitect.transit :as transit]
    [shadow.grove.runtime :as rt]))

;; FIXME: custom handler config options

(defn init! [rt-ref]
  (let [tr (transit/reader :json)
        tw (transit/writer :json)

        transit-read
        (fn transit-read [data]
          (transit/read tr data))

        transit-str
        (fn transit-str [obj]
          (transit/write tw obj))]

    (swap! rt-ref assoc
      ::rt/transit-read transit-read
      ::rt/transit-str transit-str)))


