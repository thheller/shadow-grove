(ns shadow.grove.edn
  (:require
    [cljs.reader :as reader]
    [shadow.grove :as sg]))

(defn init! [rt-ref opts]
  (let [edn-read
        (fn edn-read [data]
          (try
            (reader/read-string opts data)
            (catch :default e
              (throw (ex-info "failed to read string" {:data data} e)))))]

    (swap! rt-ref assoc
      ::sg/edn-read edn-read
      ::sg/edn-str pr-str)))


