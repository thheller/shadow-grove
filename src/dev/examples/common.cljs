(ns examples.common
  "shared chrome for the examples: the page built from an example's own source
   (see common.clj), a few classes, render counters and a log panel.
   nothing here is a pattern worth copying, the examples themselves are."
  (:require-macros [examples.common])
  (:require
    [clojure.string :as str]
    [shadow.arborist.common :as ac]
    [shadow.grove :as sg :refer (<< css)]))

(def $page
  (css :mx-auto :px-4 :font-sans :text-gray-900
    {:max-width "56rem" :line-height "1.55"}))

(def $btn
  (css :inline-flex :items-center :gap-1 :px-3 :py-1 :border :border-gray-300 :rounded :bg-white :text-sm :cursor-pointer
    [:hover :bg-gray-100]
    [:disabled :opacity-50 :cursor-default]))

(def $btn-primary
  (css :bg-blue-600 :text-white :border-blue-600
    [:hover :bg-blue-700]))

(def $btn-danger
  (css :text-red-700 :border-red-300
    [:hover :bg-red-50]))

(def $input
  (css :px-2 :py-1 :border :border-gray-300 :rounded :text-sm
    [:focus :outline-none :border-blue-500]))

(def $card
  (css :border :border-gray-200 :rounded-lg :p-4 :mb-4 :bg-white))

(def $muted
  (css :text-gray-500 :text-sm))

(def $badge
  (css :inline-block :px-2 :rounded-full :bg-gray-100 :text-gray-700 :text-xs :font-mono {:line-height "1.6"}))

(def $row
  (css :flex :items-center :gap-2 :flex-wrap))

(def $code
  (css :font-mono :text-sm :bg-gray-100 :px-1 :rounded))

(def $link
  (css :text-blue-700 :underline :cursor-pointer))

(defn render!
  "renders root into #root, again on hot-reload"
  [rt-ref root]
  (sg/render rt-ref (js/document.getElementById "root") root))

(defn note [text]
  (<< [:p (css :text-gray-600 :text-sm :mb-2) text]))

;; --- the page. (c/page "title" {"chapter" demo}) is a macro in common.clj,
;; it reads the example's source at compile time and calls page* and chapter*

(def $prose
  (css :text-gray-800
    ["& p" :mb-3]
    ["& ul" :mb-3 :pl-6 {:list-style "disc"}]
    ["& li" :mb-1]
    ["& h3" :font-semibold :mt-5 :mb-2]
    ["& code" :font-mono :bg-gray-100 :rounded {:font-size "0.85em" :padding "0.1em 0.3em"}]
    ["& pre" :mb-4 :p-3 :bg-gray-50 :border :border-gray-200 :rounded :overflow-x-auto {:font-size "13px" :line-height "1.45"}]
    ["& pre code" {:background "none" :padding "0" :font-size "inherit"}]
    ["& a" :text-blue-700 :underline]
    ["& table" :mb-4 :text-sm {:border-collapse "collapse"}]
    ["& th" :border :border-gray-200 :px-2 :py-1 :text-left :bg-gray-50 {:vertical-align "top"}]
    ["& td" :border :border-gray-200 :px-2 :py-1 :text-left {:vertical-align "top"}]
    ["& .tc" {:color "#6b7280"}]
    ["& .ts" {:color "#0f766e"}]
    ["& .tk" {:color "#7c3aed"}]))

;; the prose and code are html built at compile time, inserted as one element each.
;; cached so a re-render of the page finds the identical node and does nothing
(defonce html-cache (js/Map.))

(defn doc-html [html]
  (when (seq html)
    (ac/native-el
      (or (.get html-cache html)
          (let [el (js/document.createElement "div")]
            (set! (.-innerHTML el) html)
            (.setAttribute el "class" $prose)
            ;; the scenarios hide [data-doc], so their text checks only see the demos
            (.setAttribute el "data-doc" "")
            (.set html-cache html el)
            el)))))

;; plain fn handlers, anchors would trigger the hash-routing example
;; no URL change either, the hash is the route in hash-routing. target keeps history/init!
;; from treating them as in-app links
(defn scroll-to [e env]
  (.preventDefault e)
  (let [id (subs (.. e -currentTarget -hash) 1)]
    (when-some [el (js/document.getElementById id)]
      (.scrollIntoView el))))

(defn chapter* [id title before demo after]
  (<< [:section {:id id :class (css :pt-6 :mb-6 :border-t :border-gray-200)}
       [:h2 (css :text-lg :font-semibold :mb-3)
        [:a {:href (str "#" id) :target "_self" :on-click scroll-to :class (css :text-gray-900)} title]]
       (doc-html before)
       (when (some? demo)
         (<< [:div {:class [$card (css :mb-4 :shadow-sm)] :data-demo ""}
              demo]))
       (doc-html after)]))

(def source-base "https://github.com/thheller/shadow-grove/blob/master/")

(defn toc-item [[id title]]
  (<< [:li [:a {:href (str "#" id) :target "_self" :on-click scroll-to :class $link} title]]))

(declare log-panel)

(def counts-note
  (str "<p>The <code>× n</code> badges count how often a render ran. They come from "
       "<code>c/render-count</code>, a testing aid of these examples. Components don't need "
       "anything like it.</p>"))

(defn page* [title intro source log? counts? toc chapters]
  (<< [:div {:class $page}
       [:header (css :flex :items-baseline :gap-4 :flex-wrap :py-4 :mb-2 :border-b :border-gray-200)
        [:a {:class $link :href "/" :target "_self"} "← examples"]
        [:h1 (css :text-2xl :font-semibold) title]
        [:a {:class [$muted (css :font-mono)] :href (str source-base source) :target "_blank"} source]]
       (doc-html intro)
       (when counts?
         (doc-html counts-note))
       (when (> (count toc) 1)
         (<< [:nav {:data-doc "" :class (css :mb-2 :text-sm)}
              [:ol (css :pl-6 {:list-style "decimal"})
               (sg/simple-seq toc toc-item)]]))
       chapters
       (when log? (log-panel))]))

;; render counters, a testing aid: (c/render-count key) in a render counts how often it
;; ran and shows that as a "× n" badge. one atom for the page, keyed per call site (plus
;; the id where a component has several instances). nothing watches it, so updating it
;; during render causes nothing

(defonce render-counts (atom {}))

(defn render-count [key]
  (let [n (get (swap! render-counts update key (fnil inc 0)) key)]
    (<< [:span {:class $badge :title "how often this ran"} "× " n])))

;; log panel, plain DOM outside of grove so logging from render/bind/effects
;; never causes another update. (log! "something" value)

;; nil without a DOM, so handlers that log also run in node tests
(defonce log-el
  (when (exists? js/document)
    (doto (js/document.createElement "pre")
      (.setAttribute "class" "examples-log")
      (set! -style "margin:0;font-size:12px;line-height:1.4;max-height:9rem;overflow:auto;white-space:pre-wrap;"))))

(defn time-str []
  (let [d (js/Date.)]
    (str (.padStart (str (.getMinutes d)) 2 "0") ":"
         (.padStart (str (.getSeconds d)) 2 "0") "."
         (.padStart (str (.getMilliseconds d)) 3 "0"))))

(defn log! [& args]
  (when log-el
    (let [line (js/document.createElement "div")]
      (set! (.-textContent line)
        (str (time-str) "  " (str/join " " (map #(if (string? %) % (pr-str %)) args))))
      (.append log-el line)
      (set! (.-scrollTop log-el) (.-scrollHeight log-el))))
  ;; also on the console, which survives the panel being cleared
  (apply js/console.log "[log]" args)
  nil)

(defn clear-log! []
  (set! (.-textContent log-el) ""))

(defn clear-handler [e env]
  (clear-log!))

;; sticks to the bottom of the window while the page scrolls under it
(defn log-panel []
  (<< [:aside (css :sticky :border :border-gray-300 :rounded-t-lg :bg-gray-50 :p-2 :mt-6 :shadow-lg {:bottom "0"})
       [:div (css :flex :justify-between :items-center :mb-1)
        [:span (css :text-sm :font-semibold) "log"]
        ;; plain fn handler, only for the chrome. the examples use event maps
        [:button {:class $btn :on-click clear-handler} "clear"]]
       (ac/native-el log-el)]))

