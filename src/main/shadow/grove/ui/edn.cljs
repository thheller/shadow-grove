(ns shadow.grove.ui.edn
  (:require
    [cljs.tools.reader.edn :as reader]
    [shadow.grove :as sg :refer (defc << css)]))

(def rank-predicates
  [nil?
   boolean?
   number?
   string?
   keyword?
   symbol?
   vector?
   map?
   list?])

(defn rank-val [val]
  (reduce-kv
    (fn [res idx pred]
      (if (pred val)
        (reduced idx)
        res))
    -1
    rank-predicates))

(defn smart-comp [a b]
  (try
    (compare a b)
    (catch :default e
      (let [ar (rank-val a)
            br (rank-val b)]
        (compare ar br)))))

(defn attempt-to-sort [coll]
  (vec
    (try
      (sort smart-comp coll)
      (catch :default e
        coll))))

(declare render-edn)

(defn measure
  ([val]
   (measure 0 val))
  ([result val]
   (cond
     (map? val)
     (if (empty? val)
       ;; going to display at least one row to show empty map
       (inc result)
       ;; key or val, which ever takes more height is used
       ;; key can also be vectors and stuff, so cannot just assume 1 for keywords
       (reduce-kv
         (fn [result k v]
           (max (measure result k) (measure result v)))
         result
         val))

     (coll? val)
     (if (empty? val)
       (inc result)
       (reduce measure result val))

     :else
     (inc result))))

(defn edn-empty-coll [label]
  (<< [:div {:class (css :pl-1)} label [:span {:class (css :text-gray-400)} " empty"]]))

;; very wide but feels very clean
(defc edn-map [val]
  (bind keys
    (attempt-to-sort (keys val)))

  ;; FIXME: figure out good way to limit size of initially shown elements
  ;; each edn entry takes at least one height unit
  ;; nested maps/vectors/maps can yield trees
  ;; which aren't exactly human friendly to view
  ;; at some point should default to the usual expand toggle style
  ;; but I want to avoid having to toggle 5 times to see a somewhat simple structure
  ;; (bind height (measure val))

  (render
    (if (empty? val)
      (edn-empty-coll "{}")
      (<< [:div {:class (css {:border-left "6px solid purple"})}
           [:table {:class (css :border-collapse)}
            (sg/simple-seq keys
              (fn [k]
                (let [v (get val k)

                      $map-key
                      (css :align-top {:padding "1px 0.5rem 1px 0"})

                      $map-val
                      (css :p-0 :w-full)]
                  (<< [:tr {:class (css :border-b
                                     ["&:last-child" {:border "none"}])}
                       [:td {:class $map-key}
                        [:div {:class (css {:position "sticky" :top "0px"})}
                         (render-edn k)]]
                       [:td {:class $map-val}
                        (render-edn v)]])
                  )))]]))))

;; not super wide, but kinda unreadable
(defc edn-map-not-so-wide [val]
  (bind keys
    (attempt-to-sort (keys val)))

  ;; FIXME: figure out good way to limit size of initially shown elements
  ;; each edn entry takes at least one height unit
  ;; nested maps/vectors/maps can yield trees
  ;; which aren't exactly human friendly to view
  ;; at some point should default to the usual expand toggle style
  ;; but I want to avoid having to toggle 5 times to see a somewhat simple structure
  ;; (bind height (measure val))

  (render
    (if (empty? val)
      (edn-empty-coll "{}")
      (<< [:div {:class (css {:border-left "6px solid purple"
                              ;; :display "grid"
                              ;; :grid-template-columns "min-content 1fr"
                              })}
           (sg/simple-seq keys
             (fn [k]
               (let [v (get val k)

                     $map-key
                     (css :align-top {:padding "1px 0.5rem 1px 0"})

                     $map-val
                     (css :p-0 :pl-2 :w-full :border-b #_{:border-left "6px solid #eee"})]
                 (<< [:div {:class (css :py-1 #_ {:position "sticky" :top "0px"})}
                      (render-edn k)]
                     [:div {:class $map-val}
                      (render-edn v)])
                 )))]))))

(defc edn-vec [val]
  (render
    (if (empty? val)
      (edn-empty-coll "[]")
      (<< [:div {:class (css {:border-left "6px solid green"
                              :display "grid"
                              :grid-template-columns "min-content 1fr"})}
           (sg/simple-seq
             val
             (fn [item idx]
               (let [$seq-val (css :pl-1 :border-b
                                ["&:last-child" {:border "none"}])]
                 (<< [:div {:class (css :border-b)}
                      [:div {:class (css :px-1 :text-right :text-gray-500 {:position "sticky" :top "0px" :width "30px"})}
                       idx]]
                     [:div {:class $seq-val} (render-edn item)]))
               ))]))))

(defc edn-set [val]
  (bind items
    (vec (attempt-to-sort val)))

  (render
    (if (empty? val)
      (edn-empty-coll "#{}")
      (<< [:div {:class (css {:border-left "6px solid blue"})}
           (sg/simple-seq
             items
             (fn [item idx]
               (let [$seq-val (css :pl-1 :border-b
                                ["&:last-child" {:border "none"}])]
                 (<< [:div {:class $seq-val} (render-edn item)]))))
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
    (<< [:div {:class (css :pl-1 :whitespace-nowrap {:color "#0000ff"})} (str val)])

    (number? val)
    (<< [:div {:class (css :pl-1 :whitespace-nowrap {:color "#0000ff"})} (str val)])

    (string? val)
    (<< [:div {:class (css :pl-1 :whitespace-nowrap {:color "#008000"})} (pr-str val)])

    (keyword? val)
    (<< [:div {:class (css :pl-1 :whitespace-nowrap {:color "#660e7a"})}
         ;; FIXME: would be nice if keywords didn't take so much space with long namespaces
         ;; should maybe truncate namespaces in some way and only show full on hover/title?
         (str val)])

    :else
    (<< [:div {:class (css :pl-1 :whitespace-nowrap)} (pr-str val)])
    ))

(defc render-edn-str [edn]
  (bind parsed (reader/read-string {:default tagged-literal} edn))
  (render
    (render-edn parsed)))
