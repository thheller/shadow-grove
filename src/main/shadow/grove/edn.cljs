(ns shadow.grove.edn
  (:require
    [cljs.reader :as reader]
    [shadow.grove :as sg]
    [shadow.grove.runtime :as rt]
    [shadow.grove.db :as db]))

(defn init! [rt-ref opts]
  (let [edn-read
        (fn edn-read [data]
          (reader/read-string opts data))]

    (swap! rt-ref assoc
      ::sg/edn-read edn-read
      ::sg/edn-str pr-str)))


