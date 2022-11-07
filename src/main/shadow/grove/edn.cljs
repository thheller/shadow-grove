(ns shadow.grove.edn
  (:require
    [cljs.reader :as reader]
    [shadow.grove.runtime :as rt]
    [shadow.grove.db :as db]))

(defn init! [rt-ref opts]
  (let [edn-read
        (fn edn-read [data]
          (reader/read-string opts data))]

    (reader/register-tag-parser!
      'gdb/ident
      (fn [[key val]]
        (db/make-ident key val)))

    (swap! rt-ref assoc
      ::rt/edn-read edn-read
      ::rt/edn-str pr-str)))


