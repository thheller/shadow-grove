(ns examples.remote
  "`shadow.grove.remote`: treats Grove KV as a bounded local cache over a Clojure
   server whose database may be far too large to load into memory. This page scrolls
   through 100,000 items while keeping KV about the size of the screen.

   Supports both WebSocket (single connection for reads, transacts, and push) and
   HTTP POST (+ optional SSE push) transports against the same DB-agnostic server
   multimethods (`src/dev/examples/remote_backend.clj`)."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.remote :as remote]
    [shadow.grove.transit :as transit]
    [shadow.grove.ui.vlist3 :as vlist3]))

(defonce app (sg/get-runtime :app))

(declare rename!)

(def remote-tables
  {:item {:primary-key :item/id
          :views {:detail {:satisfies #{:summary :default}}
                  :summary {:satisfies #{:default}}}}
   :owner {:primary-key :owner/id}})

;; ## reading entities with named views
;;
;; The virtual list loads `:summary` rows (`:item/id`, `:item/label`, `:item/done?`).
;; Selecting an item mounts `ui-detail`, which asks for `:detail` — fetching `:item/notes`
;; and sideloading `:owner` in one round-trip, so `(remote/entity :owner owner-id)` is an
;; immediate KV hit. Missing IDs (item 0) resolve to `nil` without suspending.

(defc ui-detail [^:stable id]
  (bind item (remote/entity :item id :detail))
  (bind owner (remote/entity :owner (:item/owner item)))
  (bind error (sg/kv-lookup :ui :rename-error))

  (event ::rename! [env ev e]
    (.preventDefault e)
    (let [input (.. e -target -elements -label)]
      (sg/run-tx env (rename! id (.-value input)))
      (set! input -value "")))

  (render
    (if-not item
      (<< [:div#detail "item " id " does not exist"])
      (<< [:div#detail
           [:div#detail-label {:class (css :font-semibold)} (:item/label item)]
           [:div#detail-notes {:class c/$muted} (:item/notes item)]
           [:div#detail-owner {:class c/$muted} (:owner/name owner)]
           [:form {:class [c/$row (css :mt-2)] :on-submit ::rename!}
            [:input {:class c/$input :name "label" :placeholder "new label (try !bad)"}]
            [:button {:class c/$btn :type "submit"} "rename"]]
           (when error
             (<< [:div#rename-error {:class (css :text-red-700 :text-sm :mt-1)} error]))]))))

;; ## non-entity projections
;;
;; A query that isn't an entity table (counts, reports, template lists) returns `{:value ...}`
;; from `rs/query` and is read directly with `remote/query`.

(defc ui-server-stats []
  (bind {:keys [state value]} (remote/query :remote-ex/stats nil {:suspend? false}))
  (render
    (<< [:span#server-stats {:class c/$badge}
         (if value
           (str "done: " (:done value) " / " (:total value))
           (str "stats: " (name state)))])))

;; ## 100,000-row virtual list + optimistic mutations
;;
;; `remote/window` fits `vlist3` directly. Toggling done or renaming writes optimistically
;; to KV, settles in 1 RTT via server `:data`, or rolls back automatically on `:reject`.

(defn ?items [env win]
  (remote/window env :remote-ex/items {} win {:page-size 50}))

(deftx select! [id]
  [tx-env _ _]
  (-> tx-env
      (assoc-in [:ui :selected] id)
      (assoc-in [:ui :rename-error] nil)))

(deftx set-done! [id done?]
  [tx-env _ _]
  (-> tx-env
      (assoc-in [:item id :item/done?] done?)
      (remote/transact :remote-ex/set-done {:id id :done? done?})))

(deftx rename-result! []
  [tx-env {:keys [call-result]} _]
  (assoc-in tx-env [:ui :rename-error] (:reject call-result)))

(deftx rename! [id label]
  [tx-env _ _]
  (-> tx-env
      (assoc-in [:item id :item/label] label)
      (remote/transact :remote-ex/rename {:id id :label label} (rename-result!))))

(defn item-row [{:item/keys [id label done?]} _opts]
  (<< [:div {:class (css :flex :gap-3 :px-2 :items-center :cursor-pointer {:height "28px"} [:hover :bg-gray-100])
             :data-id id
             :on-click (select! id)}
       [:input {:type "checkbox"
                :checked done?
                :on-click {:e ::toggle-click :id id :done? done?}}]
       [:span label]]))

(defn items-list []
  (sg/suspense {:fallback (<< [:div#first-load "loading ..."]) :timeout 0}
    (<< [:div#items (css {:height "300px"})
         (vlist3/render {:item-height 28 :item-fn item-row :overscan 10
                         :class (css :border :border-gray-200 :rounded)}
           ?items)])))

(defc ui-selected []
  (bind selected (sg/kv-lookup :ui :selected))
  (render
    (if (nil? selected)
      (<< [:div {:class c/$muted} "select an item"])
      (sg/suspense {:fallback (<< [:div#detail-loading "loading detail ..."]) :timeout 0}
        (ui-detail selected)))))

;; ## cache stats & transport switcher

(defonce stats-ref (atom nil))

(defc ui-stats []
  (bind {:keys [demands live held pending connected?]} (sg/watch stats-ref))
  (render
    (<< [:div {:class [c/$row (css :mb-2 :flex-wrap)]}
         [:span#demands {:class c/$badge} "demands: " demands " (live " live ")"]
         [:span#held {:class c/$badge} "held: " held]
         [:span#pending {:class c/$badge} "pending tx: " pending]
         [:span#conn {:class c/$badge} (if connected? "live" "pull-only")]
         (ui-server-stats)])))

(defn start-remote! [mode]
  (let [transport
        (case mode
          :ws
          (remote/ws-transport {:url "/remote/api/ws" :format :transit})

          :http-sse
          (remote/http-transport {:url "/remote/api" :sse-url "/remote/api/sse" :format :transit})

          :http
          (remote/http-transport {:url "/remote/api" :format :transit}))]
    (remote/init! app
      {:transport transport
       :tables remote-tables
       :grace-ms 2000
       :max-cached 60})))

(deftx switch-transport! [mode]
  [tx-env _ _]
  (-> tx-env
      (assoc-in [:ui :transport] mode)
      (sg/queue-fx ::switch-transport mode)))

(defc ui-root []
  (bind mode (sg/kv-lookup :ui :transport))

  (event ::toggle-click [env {:keys [id done?]} ^js e]
    (.stopPropagation e)
    (sg/run-tx env (set-done! id (not done?))))

  (render
    (c/page "remote"
      {"reading entities with named views"
       (<< [:div {:class [c/$row (css :flex-wrap)]}
            [:button#missing {:class c/$btn :on-click (select! 0)} "item 0 (absent)"]
            [:button#elsewhere {:class c/$btn :on-click {:e ::elsewhere}} "write item 1 from elsewhere"]])

       "non-entity projections"
       (ui-server-stats)

       "100,000-row virtual list + optimistic mutations"
       (<< (ui-stats)
           [:div {:class (css :grid {:grid-template-columns "2fr 1fr"} :gap-4)}
            (items-list)
            (ui-selected)])

       "cache stats & transport switcher"
       (<< [:div {:class [c/$row (css :flex-wrap)]}
            [:button {:class c/$btn :disabled (= mode :ws) :on-click (switch-transport! :ws)} "WebSocket"]
            [:button {:class c/$btn :disabled (= mode :http-sse) :on-click (switch-transport! :http-sse)} "HTTP + SSE"]
            [:button {:class c/$btn :disabled (= mode :http) :on-click (switch-transport! :http)} "HTTP pull-only"]])})))

(defn init []
  (sg/add-kv-table app :ui {} {:selected nil :rename-error nil :transport :ws})
  (transit/init! app)
  (start-remote! :ws)

  (sg/reg-fx app ::switch-transport
    (fn [_ mode]
      (start-remote! mode)))

  (sg/reg-event app ::elsewhere
    (fn [tx-env _]
      (sg/queue-fx tx-env ::elsewhere nil)))

  (sg/reg-fx app ::elsewhere
    (fn [_ _]
      (js/fetch "/remote/api/elsewhere" #js {:method "POST"})))

  (js/setInterval #(when (::remote/state @app) (reset! stats-ref (remote/stats app))) 150)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
