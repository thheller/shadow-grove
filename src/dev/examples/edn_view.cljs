(ns examples.edn-view
  "`shadow.grove.ui.edn` renders browsable EDN, useful for debug panels and admin views.
   Its name clashes with `shadow.grove.edn` (the reader for [http-fx](/http-fx/)), so alias
   one of them differently."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.ui.edn :as ui-edn]))

(defonce app (sg/get-runtime :app))

;; ## render-edn with a value

(def sample
  {:id 1
   :name "example"
   :tags #{:a :b}
   :items [{:x 1 :y 2} {:x 3 :y 4}]
   :nested {:deeper {:deepest "value"}}
   :long-string (apply str (repeat 30 "long text "))})

(defn edn-value []
  (<< [:div#edn-value (ui-edn/render-edn sample)]))

;; ## render-edn-str parses a string first
;;
;; Edit the text, the view follows.

(defn edn-str [text]
  (<< [:div#edn-str (css :mt-2) (ui-edn/render-edn-str text)]))

;; ## edn-diff
;;
;; Two values side by side, with what changed marked.

(defn diff []
  (<< [:div#edn-diff (ui-edn/edn-diff {:a 1 :b 2 :c [1 2]} {:a 1 :b 3 :c [1 2 3]})]))

;; # page

(defc ui-root []
  (ref state (atom {:text "{:a 1 :b [1 2 3] :c #{:x}}"}))
  (bind text (sg/watch state [:text]))

  (event ::input! [env ev e]
    (swap! state assoc :text (.. e -target -value)))

  (render
    (c/page "edn view"
      {"render-edn with a value"
       (edn-value)

       "render-edn-str parses a string first"
       (<< [:textarea {:class [c/$input (css :w-full :font-mono)] :rows 3 :value text :on-input ::input!}]
           (edn-str text))

       "edn-diff"
       (diff)})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
