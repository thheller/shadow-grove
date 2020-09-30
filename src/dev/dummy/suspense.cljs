(ns dummy.suspense
  (:require
    [shadow.experiments.grove :as sg :refer (defc <<)]
    [shadow.experiments.grove.ui.testing :as t]))

(defonce root-el (js/document.getElementById "app"))

(defc nested [x]
  (bind _ (t/rand-delay 1000))

  (render
    (<< [:div.border.shadow.m-4.p-4 "nested:" x])))

(defn content []
  (<< (nested 1)
      (nested 2)
      (nested 3)
      (nested 4)
      (nested 5)))

(defc ui-root []
  (render
    (<< [:div.flex {:style "width: 500px;"}
         [:div.flex-1
          [:div.pl-4 "with suspense"]
          (sg/suspense
            {:fallback (<< [:div.pl-4 "Loading ..."])
             :timeout 500}
            (content))]

         [:div.flex-1
          [:div.pl-4 "without"]
          (content)]])))

(defn ^:dev/after-load start []
  (sg/start ::ui root-el (ui-root)))

(defn init []
  (sg/init ::ui {} [])
  (start))