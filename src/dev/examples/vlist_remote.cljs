(ns examples.vlist-remote
  "A [vlist](/vlist/) over a collection that lives on the server. The query returns the
   rows it has cached, or starts a fetch for the missing ones and suspends with a
   default. The server side is `src/dev/examples/server.clj`."
  (:require
    [cljs.reader :as reader]
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.ui.vlist3 :as vlist3]))

(defonce app (sg/get-runtime :app))

;; ## fetching missing rows from the query
;;
;; 100,000 rows, fetched as you scroll. The badge counts requests.
;;
;; - Read the collection from `env` first. Without that KV read, the `rows-loaded!` tx
;;   that writes `[:remote collection]` wouldn't invalidate the query, and the list would
;;   never update.
;; - If every row of the window is cached, return the slice.
;; - If any is missing, start the fetch **from inside the query**, then
;;   `(sg/suspend! {:item-count 0})`. This is the sanctioned exception to "queries don't
;;   cause effects": the fetch is idempotent (`pending` remembers what is in flight, the
;;   query runs again on scroll and on invalidation), and the suspend guarantees the
;;   query runs again once the data tx lands. Without the default there is no layout
;;   before the first rows arrive ([suspense](/suspense/)).
;; - The response comes back through `sg/run-tx!`, like any async result
;;   ([external-events](/external-events/)).
;; - While a mounted list's query is suspended, the list keeps showing the previous rows.
;;   On first mount there is nothing to show yet, so the nearest `suspense` shows its
;;   fallback; without one nothing mounts until the rows arrive.
;;
;; A collection that is already completely in KV doesn't need any of this, its query
;; just cuts the window and never suspends ([vlist](/vlist/)).

(defonce pending (js/Set.))

(defonce requests (atom 0))

(deftx rows-loaded! [{:keys [collection item-count more? offset rows]}]
  [tx-env ev e]
  (update-in tx-env [:remote collection]
    (fn [state]
      (-> state
          (assoc :item-count item-count :more? more?)
          (update :rows merge (zipmap (range offset (+ offset (count rows))) rows))))))

(defn fetch-slice! [rt-ref collection offset num]
  (let [k (str collection "/" offset "/" num)]
    (when-not (.has pending k)
      (.add pending k)
      (swap! requests inc)
      (-> (js/fetch "/vlist-remote/api/slice"
            #js {:method "POST"
                 :headers #js {"content-type" "application/edn"}
                 :body (pr-str {:collection collection :offset offset :num num})})
          (.then #(.text %))
          (.then (fn [text]
                   (.delete pending k)
                   (sg/run-tx! rt-ref (rows-loaded! (reader/read-string text)))))))))

(defn ?remote-window [env collection {:keys [offset num] :or {offset 0 num 0}}]
  (let [{:keys [item-count rows more?]} (get-in env [:remote collection])
        end (when item-count (min item-count (+ offset num)))
        slice (when end
                (reduce
                  (fn [v idx]
                    (if-some [row (get rows idx)]
                      (conj v row)
                      (reduced nil)))
                  []
                  (range offset end)))]
    (if slice
      {:item-count item-count :offset offset :slice slice :more? more?}
      (do (fetch-slice! (::sg/runtime-ref env) collection offset num)
          (sg/suspend! {:item-count 0})))))

(defn row [{:keys [idx label value]} {:keys [focus]}]
  (<< [:div {:class [(css :flex :gap-4 :px-2 :items-center {:height "28px"}) (when focus (css :bg-blue-100))]
             :data-idx idx}
       [:span (css :w-20 :text-gray-500) idx]
       [:span (css :flex-1) label]
       [:span value]]))

(defn big-list []
  (sg/suspense {:fallback (<< [:div#first-load "loading the first rows ..."]) :timeout 0}
    (<< [:div#big (css {:height "300px"})
         (vlist3/render {:item-height 28 :item-fn row :overscan 10
                         :class (css :border :border-gray-200 :rounded)}
           ?remote-window :big)])))

;; ## a growing collection
;;
;; A query returns `:more? true` while more rows exist past `:item-count`. The list then
;; renders one extra row with `(more-fn)`, or `"..."` without one. Loading more is up to
;; the query and your events: here the button runs a tx that asks the server, and the
;; result raises `:item-count`, which makes the query fetch the new rows as they come
;; into view.

(deftx more-loaded! [collection]
  [tx-env {:keys [result]} e]
  (assoc-in tx-env [:remote collection :item-count] (:item-count result)))

(deftx load-more! []
  [tx-env ev e]
  (sg/queue-fx tx-env ::more :growing))

(defn register-more! []
  (sg/reg-fx app ::more
    (fn [{:keys [transact!]} collection]
      (-> (js/fetch "/vlist-remote/api/more" #js {:method "POST"})
          (.then #(.text %))
          (.then #(transact! (assoc (more-loaded! collection) :result (reader/read-string %))))))))

(defn more-row []
  (<< [:div {:class (css :px-2 {:height "28px"})}
       [:button#more {:class c/$btn :on-click (load-more!)} "load more"]]))

(defn growing-list []
  (sg/suspense {:fallback "loading ..." :timeout 0}
    (<< [:div#growing (css {:height "200px"})
         (vlist3/render {:item-height 28 :item-fn row :more-fn more-row
                         :class (css :border :border-gray-200 :rounded)}
           ?remote-window :growing)])))

;; # page

(defc ui-request-count []
  (bind n (sg/watch requests))
  (render (<< [:span#requests {:class c/$badge} "requests: " n])))

(defc ui-root []
  (render
    (c/page "vlist remote"
      {"fetching missing rows from the query"
       (<< [:div {:class [c/$row (css :mb-2)]} (ui-request-count)]
           (big-list))

       "a growing collection"
       (growing-list)})))

(defn init []
  (sg/add-kv-table app :remote {} {})
  (register-more!)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
