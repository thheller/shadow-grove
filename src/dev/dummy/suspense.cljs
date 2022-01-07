(ns dummy.suspense
  (:require
    [shadow.experiments.grove :as sg :refer (defc <<)]
    [shadow.experiments.grove.ui.testing :as t]
    [shadow.experiments.grove.runtime :as rt]))

(defonce root-el (js/document.getElementById "app"))

(defc nested [x]
  (bind _ (t/rand-delay 250))

  (render
    (<< [:div.border.shadow.my-4.p-4 "nested:" x])))

(defn content []
  (<< (nested 1)
      (nested 2)
      (nested 3)
      (nested 4)
      (nested 5)))

(defn page-a []
  (<< [:div "page a"]
      (content)))

(defn page-b []
  (<< [:div "page b"]
      (content)))

(defn page-c []
  (<< [:div "page c"]
      (content)))

(defc ui-root []
  (bind {:keys [page]}
    (sg/env-watch :data-ref))

  (event ::switch-page! [{:keys [data-ref] :as env} {:keys [page]} e]
    (swap! data-ref assoc :page page))

  (render
    (let [body
          (case page
            :a (page-a)
            :b (page-b)
            :c (page-c))]

      (<< [:div.p-4
           [:a.underline {:href "#" :on-click {:e ::switch-page! :page :a}} "page a"]
           " "
           [:a.underline {:href "#" :on-click {:e ::switch-page! :page :b}} "page b"]
           " "
           [:a.underline {:href "#" :on-click {:e ::switch-page! :page :c}} "page c"]]

          [:div.flex {:style "width: 500px;"}
           [:div.flex-1.p-4
            [:div "with suspense"]
            (sg/suspense
              {:fallback (<< [:div.pl-4 "Loading ..."])
               :timeout 500}
              body)]

           [:div.flex-1.p-4
            [:div "without"]
            body]]))))

;; grove runtime
(defonce rt-ref
  (rt/prepare {}
    (atom {}) ;; grove state which we don't use here
    ::ui))

(defn ^:dev/after-load start []
  (sg/render rt-ref root-el (ui-root)))

;; adding this to the env under :data-ref, env makes it available to the component tree
;; never using this directly otherwise to avoid global state
(defonce data-ref (atom {:page :a}))

(defn init []
  ;; functions in env-init are called once on first render for a new root
  ;; we use this to provide data-ref access via component env
  (swap! rt-ref update ::rt/env-init conj #(assoc % :data-ref data-ref))
  (start))
