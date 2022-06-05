(ns shadow.grove.edn
  (:require
    [cljs.reader :as reader]
    [shadow.grove.runtime :as rt]))

(defn init! [rt-ref opts]
  (let [edn-read
        (fn edn-read [data]
          (reader/read-string opts data))]

    (swap! rt-ref assoc
      ::rt/edn-read edn-read
      ::rt/edn-str pr-str)))


