(ns shadow.grove.ui.util)

(def now
  (if (exists? js/performance)
    #(js/performance.now)
    #(js/Date.now)))

