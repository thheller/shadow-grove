(ns shadow.experiments.grove.react.dump
  (:require
    [shadow.experiments.grove.react :as r :refer (<<)]))

;; FIXME: make these cool ...
(def map-renderer
  {:component-id ::map-renderer
   :render
   (fn [{:keys [value] :as props}]
     (let [entries (sort-by first value)]
       (<< [:div.edn-dump.text-left
            [:table.edn-map
             [:caption.font-bold
              (pr-str (type value))
              " size: "
              (count value)]
             [:tbody
              (r/render-seq entries first
                (fn [[key val]]
                  (<< [:tr
                       [:td.edn-mkey (r/as-react-element key)]
                       [:td.edn-mval (r/as-react-element val)]])))]]])))})

(def vec-renderer
  {:component-id ::map-renderer
   :render
   (fn [{:keys [value] :as props}]
     (<< [:div "DUMP: " (pr-str value)]))})

(extend-protocol r/IConvertToReact
  cljs.core/PersistentArrayMap
  (as-react-element [m]
    (r/render map-renderer {:value m}))

  cljs.core/PersistentHashMap
  (as-react-element [m]
    (r/render map-renderer {:value m}))

  cljs.core/PersistentVector
  (as-react-element [m]
    (r/render vec-renderer {:value m}))

  cljs.core/Keyword
  (as-react-element [m]
    (<< [:span.edn-keyword (str m)]))

  number
  (as-react-element [m]
    (<< [:span.edn-number (str m)]))

  string
  (as-react-element [m]
    (<< [:span.edn-string m]))

  nil
  (as-react-element [m]
    (<< "nil"))

  default
  (as-react-element [m]
    (<< (pr-str m)))
  )

