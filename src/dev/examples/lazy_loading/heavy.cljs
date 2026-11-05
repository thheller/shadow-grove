(ns examples.lazy-loading.heavy
  "lives in the :lazy-loading-heavy module of the :examples build (shadow-cljs.edn),
   loaded on first use.
   examples.lazy-loading never requires this namespace."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (<< css)]))

(c/log! "heavy module loaded")

;; the name passed to lazy/use. the fn gets the args given to lazy/use
;; and returns any renderable
(defn ^{:lazy-loadable "bar-chart"} bar-chart [{:keys [values]}]
  (let [w 24 h 100 top (max 1 (apply max values))]
    (<< [:svg#bar-chart {:width (* w (count values)) :height h :aria-label "bar chart"}
         (sg/simple-seq values
           (fn [v idx]
             (let [bh (* h (/ v top))]
               (<< [:rect {:x (* idx w) :y (- h bh) :width (- w 4) :height bh :fill "#3b82f6"}]))))])))
