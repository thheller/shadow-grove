(ns shadow.experiments.grove.ui.util
  )

(defn assert-not-in-worker! []
  (assert js/goog.global.document "this can only be used inside the main parts of your app, not in a worker"))

;; FIXME: build should enforce this too
(assert-not-in-worker!)

(defonce id-seq (atom 0))

(defn next-id []
  (swap! id-seq inc))


(def now
  (if (exists? js/performance)
    #(js/performance.now)
    #(js/Date.now)))

