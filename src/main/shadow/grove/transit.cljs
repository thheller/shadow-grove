(ns shadow.grove.transit
  (:require
    [cognitect.transit :as transit]
    [shadow.grove.runtime :as rt]
    [shadow.grove.db.ident :as ident]
    [shadow.grove.db :as db]))

(defn init!
  ([rt-ref]
   (init! rt-ref {}))
  ([rt-ref config]
   ;; FIXME: take more handlers from config somehow
   ;; maybe make tag used for idents configurable?
   (let [tr
         (transit/reader :json
           {:handlers
            {"gdb/ident"
             (fn [[key val]]
               (db/make-ident key val))}})

         tw
         (transit/writer :json
           {:handlers
            {ident/Ident
             (transit/write-handler
               (constantly "gdb/ident")
               db/ident-as-vec
               nil
               nil)}})

         transit-read
         (fn transit-read [data]
           (transit/read tr data))

         transit-str
         (fn transit-str [obj]
           (transit/write tw obj))]

     (swap! rt-ref assoc
       ::rt/transit-read transit-read
       ::rt/transit-str transit-str))))


