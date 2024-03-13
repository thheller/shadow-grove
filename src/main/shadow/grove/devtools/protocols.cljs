(ns shadow.grove.devtools.protocols)

(defprotocol ISnapshot
  (snapshot [this ctx]))
