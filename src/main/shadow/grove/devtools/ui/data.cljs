(ns shadow.grove.devtools.ui.data
  (:require
    [clojure.string :as str]
    [shadow.grove.devtools :as-alias m]
    [shadow.grove.devtools.relay-ws :as relay-ws]
    [shadow.grove.events :as ev]
    [shadow.grove.runtime :as rt]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.ui.edn :as edn]))

(defc ui-panel [target-ident]
  (render
    "tbd"))

