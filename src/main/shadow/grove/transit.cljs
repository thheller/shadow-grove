(ns shadow.grove.transit
  (:require
    [cognitect.transit :as transit]
    [shadow.grove.runtime :as rt]))

(defn init!
  ([rt-ref]
   (init! rt-ref {}))
  ([rt-ref {:keys [reader-opts writer-opts]}]
   (let [tr
         (transit/reader :json reader-opts)

         tw
         (transit/writer :json writer-opts)

         transit-read
         (fn transit-read [data]
           (transit/read tr data))

         transit-str
         (fn transit-str [obj]
           (transit/write tw obj))]

     (swap! rt-ref assoc
       ::rt/transit-read transit-read
       ::rt/transit-str transit-str))))


