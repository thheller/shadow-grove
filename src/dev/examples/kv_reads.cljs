(ns examples.kv-reads
  "The KV store is deliberately boring: tables are ordinary maps keyed by the table id
   (`:item`), and reads look like ordinary map access. The only magic is **observation**:
   reads through `sg/kv-lookup` and `sg/query` record which keys they touched, so a later
   transaction re-runs exactly the binds that read changed data. Defining tables and
   writing them: [kv-writes](/kv-writes/).

   Reads happen in `bind`, the only place that tracks them. Each read claims its bind,
   one per bind ([bind-helpers](/bind-helpers/)). Each `× n` below counts the renders of
   one reader. Rename an item and see which ones run."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; ## kv-lookup and kv-lookups
;;
;; ```
;; (bind item (sg/kv-lookup :item id))           ;; one entity, nil if missing
;; (bind label (sg/kv-lookup :item id :label))   ;; dig deeper, any number of keys
;; (bind items (sg/kv-lookups :item ids))        ;; several keys of one table
;; (bind table (sg/kv-lookup :item))             ;; the whole table
;; ```
;;
;; - A missing key returns nil. Components routinely `(when item (<< ...))` while data
;;   loads. When "not loaded yet" and "absent" must differ, see
;;   [loading-sentinel](/loading-sentinel/).
;; - Renaming item 2 reaches only the readers of item 2, and the whole-table read.
;; - Reading the whole table means every change to it re-renders. Fine for small tables,
;;   a query is better for derived data.

(defc ui-lookup-entity [id]
  (bind item (sg/kv-lookup :item id))
  (render
    (<< [:div {:class c/$row :data-read (str "entity-" id)}
         "(sg/kv-lookup :item " id ") → " (pr-str item) (c/render-count [::lookup-entity id])])))

(defc ui-lookup-path [id]
  (bind label (sg/kv-lookup :item id :label))
  (render
    (<< [:div {:class c/$row :data-read (str "path-" id)}
         "(sg/kv-lookup :item " id " :label) → " label (c/render-count [::lookup-path id])])))

(defc ui-lookups [ids]
  (bind items (sg/kv-lookups :item ids))
  (render
    (<< [:div {:class c/$row :data-read "lookups"}
         "(sg/kv-lookups :item " (pr-str ids) ") → " (count items) " items" (c/render-count ::lookups)])))

(defc ui-whole-table []
  (bind table (sg/kv-lookup :item))
  (render
    (<< [:div {:class c/$row :data-read "table"}
         "(sg/kv-lookup :item) → " (count table) " items" (c/render-count ::whole-table)])))

;; ## queries
;;
;; `(sg/query ?fn & params)` calls a **plain function** of `env` (the map of all tables)
;; plus params, and observes what it reads through `env`. Write it as ordinary data code
;; (`vals`, `filter`, `sort-by`, `group-by`) in a data namespace, so it is testable
;; without any UI ([transactions](/transactions/) has a node test calling one).
;;
;; - The `?` prefix is a convention, it means nothing to the runtime.
;; - The full `env` makes cross-table joins plain `get-in`.
;; - Return vectors (`mapv`, `vec`), never lazy seqs, fragments reject them.
;; - Sorting, filtering and aggregation belong in the query, not in `render`.
;; - The tables in a query's `env` are read-only, `assoc` on them throws. Writes only
;;   happen in tx handlers.
;; - Queries are shared: components calling the same query fn with `=` params use one
;;   computation.
;;
;; `?items-by-color` iterates the table with `vals`, so every change to `:item` re-runs
;; it. Its component only renders when the result changed: renaming item 2 (blue) doesn't
;; render the `:red` reader. `?first-label` touched only item 1, so other items never even
;; re-run it.

(defn ?items-by-color [env color]
  (->> (vals (:item env))
       (filter #(= color (:color %)))
       (sort-by :id)
       (mapv :label)))

(defn ?first-label [env]
  (get-in env [:item 1 :label]))

(defc ui-query-color [color]
  (bind labels (sg/query ?items-by-color color))
  (render
    (<< [:div {:class c/$row :data-read (str "query-" (name color))}
         "(sg/query ?items-by-color " (pr-str color) ") → " (pr-str labels) (c/render-count [::query-color color])])))

(defc ui-query-first []
  (bind label (sg/query ?first-label))
  (render
    (<< [:div {:class c/$row :data-read "query-first"}
         "(sg/query ?first-label) → " label (c/render-count ::query-first)])))

;; ## composing queries
;;
;; A query is a function of `env`, so building on another query is calling it with the
;; same `env`. Its reads go through that `env` and are observed like any other. Never
;; call `sg/query` or `sg/kv-lookup` inside a query: they claim the component's slot,
;; which the outer `sg/query` already holds.

(defn ?label-lengths [env color]
  (mapv count (?items-by-color env color)))

(defc ui-query-composed []
  (bind lengths (sg/query ?label-lengths :red))
  (render
    (<< [:div {:class c/$row :data-read "query-composed"}
         "(sg/query ?label-lengths :red) → " (pr-str lengths) (c/render-count ::query-composed)])))

;; ## precise reads
;;
;; - One read per bind, so unrelated changes don't re-run unrelated steps. Splitting one
;;   big query into two binds (the entity, plus a derived flag) is the standard precision
;;   trick, each invalidates separately.
;; - A component reading only one entity and one UI flag doesn't render when another
;;   entity changes. Split large components when parts depend on different data.
;; - Lists of ids: the query returns ids, each row `kv-lookup`s its entity, rendered with
;;   `(sg/keyed-seq ids identity ui-row)` ([lists](/lists/)). Changing one entity then
;;   renders one row.

;; ## what not to do
;;
;; - No untracked reads of table data. There is no supported "deref the runtime" in
;;   render, it would never update. A table that is still empty because nothing fetched
;;   it is not a tracking bug: the request wasn't started (from a tx, or a pull in an
;;   earlier bind, [http-pull](/http-pull/)), or its tx hasn't committed.
;; - No writes outside tx handlers. `bind`, `render` and queries compute, they never
;;   `swap!` or `assoc` tables.
;; - No whole-table iteration in render for big tables. Put the `vals` / `filter` /
;;   `sort` in a query, so it runs only when its inputs change.
;; - `nil` keys are not readable. Keep "no entity" representable in your queries, guard
;;   `(get-in env [:item nil])` explicitly.

;; # page

(deftx rename! [id]
  [tx-env ev e]
  (update-in tx-env [:item id :label] #(str % "!")))

(deftx recolor! [id]
  [tx-env ev e]
  (update-in tx-env [:item id :color] #(if (= % :red) :blue :red)))

(deftx add-item! []
  [tx-env ev e]
  (let [id (inc (count (:item tx-env)))]
    (assoc-in tx-env [:item id] {:id id :label (str "item " id) :color :blue})))

(defn controls []
  (<< [:div {:class [c/$row (css :mb-3)]}
       [:button {:class c/$btn :on-click (rename! 1)} "rename item 1"]
       [:button {:class c/$btn :on-click (rename! 2)} "rename item 2"]
       [:button {:class c/$btn :on-click (recolor! 3)} "recolor item 3"]
       [:button {:class c/$btn :on-click (add-item!)} "add item"]]))

(defc ui-root []
  (render
    (c/page "kv reads"
      {"kv-lookup and kv-lookups"
       (<< (controls)
           (ui-lookup-entity 1)
           (ui-lookup-entity 2)
           (ui-lookup-path 1)
           (ui-lookups [1 2])
           (ui-whole-table))

       "queries"
       (<< (controls)
           (ui-query-color :red)
           (ui-query-color :blue)
           (ui-query-first))

       "composing queries"
       (ui-query-composed)})))

(defn init []
  (sg/add-kv-table app :item {:primary-key :id}
    {1 {:id 1 :label "item 1" :color :red}
     2 {:id 2 :label "item 2" :color :blue}
     3 {:id 3 :label "item 3" :color :red}})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
