(ns shadow.arborist.dom-scheduler
  (:require-macros [shadow.arborist.dom-scheduler]))

;; microtask based queue, maybe rAf?
(def task-queue (js/Promise.resolve))

(def scheduled? false)
(def flushing? false)

(def read-tasks #js [])
(def write-tasks #js [])
(def update-tasks #js [])

(defn run-tasks! [^js arr]
  ;; (js/console.log "run-tasks!" (into [] arr))
  (loop []
    (when (pos? (alength arr))
      (let [task (.pop arr)]
        (task)
        (recur)))))

(defn run-all! []
  (when-not flushing?
    (set! scheduled? false)
    (set! flushing? true)
    (run-tasks! read-tasks)
    (run-tasks! write-tasks)
    (run-tasks! update-tasks)
    (set! flushing? false)))

(defn maybe-schedule! []
  (when-not scheduled?
    (set! scheduled? true)
    (.then task-queue run-all!))

  ;; return task-queue so callers can .then additional stuff after their task?
  ;; not sure this will see too much use?
  task-queue)

(defn read!! [cb]
  (.push read-tasks cb)
  (maybe-schedule!))

(defn write!! [cb]
  (.push write-tasks cb)
  (maybe-schedule!))

(defn after!! [cb]
  (.push update-tasks cb)
  (maybe-schedule!))


