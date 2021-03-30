(ns shadow.experiments.grove.ui.data
  (:require
    [shadow.experiments.grove :as sg :refer (defc <<)]))

(defn render* [obj]
  (cond
    (nil? obj)
    (<< [:div.text-gray-300 "nil"])

    (boolean? obj)
    (<< [:div (str obj)])

    (number? obj)
    (<< [:div (str obj)])

    (keyword? obj)
    (<< [:div.text-indigo-600.whitespace-nowrap (str obj)])

    (symbol? obj)
    (<< [:div (str obj)])

    (string? obj)
    (<< [:div (pr-str obj)])

    (vector? obj)
    (<< [:div.flex
         [:div "["]
         [:div
          (sg/simple-seq obj
            (fn [val idx]
              (render* val)))]
         [:div.self-end "]"]])

    (map? obj)
    (<< [:div.flex
         [:div "{"]
         [:div.flex-1
          (sg/simple-seq (try (sort (keys obj)) (catch :default e (keys obj)))
            (fn [key idx]
              (<< [:div {:class (when (pos? idx) "pt-2")} (render* key)]
                  [:div (render* (get obj key))])
              ))]
         [:div.self-end "}"]])

    :else
    (<< [:div (pr-str obj)])))

(defn render [obj]
  (<< [:div.font-mono.overflow-auto.text-sm
       (render* obj)]))

