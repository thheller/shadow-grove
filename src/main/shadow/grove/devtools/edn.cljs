(ns shadow.grove.devtools.edn
  (:require
    [cljs.tools.reader.edn :as reader]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.remote.runtime.obj-support :as obj-support]))

(declare render-edn)

(defn edn-empty-coll [label]
  (<< [:div {:class (css :pl-1)} label [:span {:class (css :text-gray-400)} " empty"]]))

(defc edn-map [val]
  (bind keys
    (obj-support/attempt-to-sort (keys val)))

  (render
    (if (empty? val)
      (edn-empty-coll "{}")
      (<< [:div {:class (css :flex {:background-color "purple"})}
           ;; FIXME: "toolbar"
           [:div {:class (css {:width "10px"})}]
           [:table {:class (css :bg-white :flex-1 :border-collapse)}
            (sg/simple-seq keys
              (fn [k]
                (let [v (get val k)

                      $map-key
                      (css :pr-2)

                      $map-val
                      (css :whitespace-nowrap :truncate :w-full)]
                  (<< [:tr {:class (css :border-b)}
                       [:td {:class $map-key}
                        (render-edn k)]
                       [:td {:class $map-val}
                        (render-edn v)]])
                  )))]]))))

(defc edn-vec [val]
  (render
    (if (empty? val)
      (edn-empty-coll "[]")
      (<< [:div {:class (css :flex {:background-color "green"})}
           ;; FIXME: "toolbar"
           [:div {:class (css {:width "10px"})}]
           [:table {:class (css :bg-white :flex-1 :border-collapse :whitespace-nowrap)}
            [:tbody
             (sg/simple-seq
               val
               (fn [item idx]
                 (let [$seq-row
                       (css :border-b)

                       $seq-idx
                       (css :pl-4 :px-2 :border-r :text-right :text-gray-400)

                       $seq-val
                       (css :w-full :pl-2 :flex-1 :truncate)]

                   (<< [:tr {:class $seq-row}
                        [:td {:class $seq-idx} idx]
                        [:td {:class $seq-val} (render-edn item)]
                        ]))))]]]))
    ))

(defc edn-set [val]
  (bind items
    (vec (obj-support/attempt-to-sort val)))

  (render
    (if (empty? val)
      (edn-empty-coll "#{}")
      (<< [:div {:class (css :flex {:background-color "blue"})}
           ;; FIXME: "toolbar"
           [:div {:class (css {:width "10px"})}]
           [:div {:class (css :bg-white :flex-1)}
            (sg/simple-seq
              items
              (fn [item idx]
                (let [$seq-row
                      (css :border-b :flex)

                      $seq-val
                      (css :px-2 :flex-1 :truncate)]

                  (<< [:div {:class $seq-row}
                       [:div {:class $seq-val} (render-edn item)]]))))]
           ]))))

(defn render-edn [val]
  (cond
    (map? val)
    (edn-map val)

    (vector? val)
    (edn-vec val)

    (set? val)
    (edn-set val)

    (boolean? val)
    (<< [:div {:class (css :truncate :pl-1 {:color "#0000ff"})} (str val)])

    (number? val)
    (<< [:div {:class (css :truncate :pl-1 {:color "#0000ff"})} (str val)])

    (string? val)
    (<< [:div {:class (css :truncate :pl-1 {:color "#008000"})} (pr-str val)])

    (keyword? val)
    (<< [:div {:class (css :truncate :pl-1 {:color "#660e7a"})} (pr-str val)])

    :else
    (<< [:div {:class (css :truncate :pl-1)} (pr-str val)])
    ))

(defc render-edn-str [edn]
  (bind parsed (reader/read-string {:default tagged-literal} edn))
  (render
    (render-edn parsed)))
