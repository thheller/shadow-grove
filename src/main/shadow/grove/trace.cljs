(ns shadow.grove.trace)

;; these are all noop so that :advanced removes them entirely
;; dev preload will override them and handle the actual tracing
;; safer than mucking with (when TRACE ...)

(defn component-create [component])

(defn component-dom-sync [component dirty-from-args dirty-slots])
(defn component-dom-sync-done [component t])

(defn component-slot-cleanup [component slot-idx])
(defn component-slot-cleanup-done [component slot-idx t])

(defn component-run-slot [component slot-idx])

(defn component-run-slot-done [component slot-idx t])

(defn component-after-render-cleanup [component])
(defn component-after-render-cleanup-done [component t])

(defn component-destroy [component])
(defn component-destroy-done [component t])

(defn component-work [component dirty-slots])
(defn component-work-done [component t])

(defn component-render [component updated-slots])
(defn component-render-done [component t])

(defn run-work [scheduler trigger])
(defn run-work-done [scheduler trigger t])

(defn run-microtask [scheduler trigger])
(defn run-microtask-done [scheduler trigger t])

(defn render-root [])
(defn render-root-done [t])

(defn render-direct [])
(defn render-direct-done [t])