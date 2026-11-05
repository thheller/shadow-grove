(ns examples.http-pull
  "`shadow.grove.http-pull`: a request read from a `bind`. The pull claims the bind,
   shares in-flight requests between everyone asking for the same arg, and suspends while
   pending, for data a screen needs before it can render.

   **Experimental.** The design has known holes and will change, this is the best attempt
   so far, not the settled way to load data. The stable path is an [http-fx](/http-fx/)
   request queued from a tx: for user actions (save, login), and for page data from
   `:ui/route!` ([auth-gate](/auth-gate/)).

   The server side is `src/dev/examples/server.clj`, with some latency added."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.edn :as edn]
    [shadow.grove.http-fx :as http-fx]
    [shadow.grove.http-pull :as pull]
    [shadow.grove.kv :as kv]))

(defonce app (sg/get-runtime :app))

(declare rename!)

;; ## defining pulls
;;
;; Pulls are top-level definitions, usually in a data namespace.
;; `(pull/setup ::id config)` uses the runtime `(sg/get-runtime :app)`,
;; `(pull/setup rt-ref ::id config)` another one. It throws when called while a component
;; slot runs: a pull must not close over render state.
;;
;; - `:request` is the inner request: a string, a map, or a fn of `[env arg]` returning
;;   one ([http-fx](/http-fx/) request forms). Not an fx map, no deftx in it.
;; - The config is also the http-fx config for the request. Pulls don't go through a
;;   registered fx, so they don't inherit its `:base-url` or `:request-format`. Set
;;   them here. `:with-credentials` and `:response-formats` work too. The EDN or transit
;;   reader still comes from the runtime (`edn/init!`).
;; - `:on-success` is `(fn [env ev] env)`, not a deftx. `ev` has `:value` (after
;;   `:transform`), `:result` (the raw body), `:status` and `:request`. This is where list
;;   and detail payloads enter KV, with `kv/add` and `kv/merge-seq`
;;   ([kv-writes](/kv-writes/)).
;; - `:on-error` has the same shape. Without one, a tx still runs (and warns).

(def pull-item
  (pull/setup ::item
    {:base-url "/http-pull/api"
     :request (fn [env id] (str "/item/" id))
     :on-success (fn [env {:keys [value]}]
                   (kv/add env :item value))}))

(def pull-items
  (pull/setup ::items
    {:base-url "/http-pull/api"
     :request "/items"
     :on-success (fn [env {:keys [value]}]
                   (kv/merge-seq env :item value [:ui :item-ids]))}))

;; ## reading pulls in binds
;;
;; Call the pull in a bind, one pull per bind. It returns a result map:
;;
;; - pending: `{:state :pending :refresh! fn :last-update ms}`
;; - success: `{:state :success :value v :refresh! fn :last-update ms}`
;; - error: `{:state :error :status n :result body :last-update ms}` (`:result`, not
;;   `:value`)
;;
;; A pending pull calls `sg/suspend!` (`:suspend` defaults to true), so later binds and
;; `render` don't run until it arrives, and a `sg/suspense` above shows the fallback
;; ([suspense](/suspense/)). `(bind _ (pull-items))` is normal: the pull is there to
;; suspend and to fill the table, the next bind reads KV.
;;
;; The arg is the cache key: every component calling `(pull-item 1)` shares one request,
;; a no-arg call is one global instance. A new arg detaches the old instance and attaches
;; the new one, which suspends if it has no result yet. The detail takes the id as
;; `^:stable`, so another item remounts it ([stable-args](/stable-args/)).

(defn ?items [env]
  (->> (get-in env [:ui :item-ids]) (keep #(get-in env [:item %])) (vec)))

(defc ui-list []
  (bind _ (pull-items))
  (bind items (sg/query ?items))
  (render
    (<< [:ul#list
         (sg/simple-seq items
           (fn [{:keys [id label]}]
             (<< [:li [:button {:class c/$link :on-click {:e ::select! :id id}} label]])))])))

(defc ui-detail [^:stable id]
  (bind _ (pull-item id))
  (bind item (sg/kv-lookup :item id))
  (render
    (<< [:div#detail {:class (css :p-2 :bg-gray-50 :rounded)}
         [:b (:label item)] " · " (:details item) " · rev " (:rev item)
         [:button {:class [c/$btn (css :ml-2)] :on-click (rename! id (str (:label item) "!"))} "rename (server)"]])))

(defn list-and-detail [id]
  (<< (sg/suspense {:fallback (<< [:div#list-loading "loading list ..."]) :timeout 0}
        (ui-list))
      (when id
        (sg/suspense {:fallback (<< [:div#detail-loading "loading item ..."]) :timeout 0}
          (ui-detail id)))))

;; ## refreshing after a mutation
;;
;; "rename (server)" in the detail above saves through http-fx. Afterwards the pulls on
;; screen refetch with `(pull/invalidate! pull)` or `(pull/invalidate! pull arg)`.
;; Mounted instances refetch at once and **keep the previous result** meanwhile, so they
;; don't suspend again. Unmounted instances just drop their cached result.
;;
;; `invalidate!` is a side effect, so the success tx doesn't call it, it queues an fx
;; that does ([fx](/fx/)). It refetches, it doesn't write KV by itself, that is still
;; the pull's `:on-success`.

(defn register-invalidate! []
  (sg/reg-fx app ::invalidate
    (fn [fx-env [pull & args]]
      (apply pull/invalidate! pull args))))

(deftx renamed! [id]
  [tx-env {:keys [result]} e]
  (-> tx-env
      (sg/queue-fx ::invalidate [pull-item id])
      (sg/queue-fx ::invalidate [pull-items])))

(deftx rename! [id label]
  [tx-env ev e]
  (sg/queue-fx tx-env :api
    {:request {:uri (str "/item/" id) :body {:label label}}
     :on-success (renamed! id)}))

;; ## :value and :transform
;;
;; A payload that isn't an entity (a report, server info, search results) doesn't need a
;; table: leave out `:on-success` and read `:value`. `:transform (fn [env body] value)`
;; shapes what `:value` is. Component-specific shaping still belongs in a later bind.
;;
;; The search sends a POST with a body, so it sets `:request-format`. And it only pulls
;; once there is text: a `when` around the call means no request and no claim. A new arg
;; replaces the instance, but a `when` turning false later doesn't drop the claim it made
;; ([bind-helpers](/bind-helpers/)).

(def pull-info
  (pull/setup ::info
    {:base-url "/http-pull/api"
     :request "/info"}))

(def pull-search
  (pull/setup ::search
    {:base-url "/http-pull/api"
     :request-format :edn
     :request (fn [env text] {:method :POST :uri "/search" :body {:text text}})
     :transform (fn [env result] (:results result))}))

(defc ui-info []
  (bind {info :value} (pull-info))
  (render (<< [:div#info {:class c/$muted} "info (no table): " (pr-str info)])))

(defc ui-search []
  (ref state (atom ""))
  (bind text (sg/watch state))
  (bind {results :value} (when (seq text) (pull-search text)))
  (event ::input! [env ev e] (reset! state (.. e -target -value)))
  (render
    (<< [:div {:class c/$row}
         [:input#search {:class c/$input :placeholder "search (POST + :transform)" :on-input ::input!}]
         [:span#results (pr-str results)]])))

;; ## :suspend false, :refresh-every and errors
;;
;; With `:suspend false` the component renders with `{:state :pending}` instead of
;; suspending, and decides itself what to show. `:refresh-every ms` refetches while
;; someone reads the pull. The second pull always fails: its result has `:state :error`
;; and the status, and its `:on-error` ran.

(def pull-time
  (pull/setup ::time
    {:base-url "/http-pull/api"
     :request "/time"
     :suspend false
     :refresh-every 1000}))

(def pull-fail
  (pull/setup ::fail
    {:base-url "/http-pull/api"
     :request "/fail"
     :suspend false
     :on-error (fn [env ev] (c/log! "pull-fail :on-error, status" (:status ev)) env)}))

(defc ui-time []
  (bind {:keys [state value]} (pull-time))
  (render (<< [:div#time {:class c/$muted} ":suspend false + :refresh-every 1000: " (pr-str state) " " (:time value)])))

(defc ui-fail []
  (bind res (pull-fail))
  (render (<< [:div#fail {:class c/$muted} "error result: " (pr-str (dissoc res :refresh! :last-update))])))

;; ## which tool
;;
;; - Clicked save, login, publish: `queue-fx` an http-fx request with an `:on-success`
;;   deftx ([http-fx](/http-fx/)).
;; - The page can't render until this payload exists: a pull in a bind, `sg/suspense`
;;   above the page. Or, without a pull, `:ui/route!` queues an http-fx request and the
;;   page suspends on a `:db/loading` sentinel until it lands ([auth-gate](/auth-gate/)).
;; - The payload is an entity or a list of them: the pull's `:on-success` writes KV, the
;;   component reads it with `kv-lookup` / `query`.
;; - The payload is a one-off projection (a report, file metadata): `:transform` and read
;;   `:value`. Don't invent a table.
;; - A mutation should refresh a pull already on screen: `pull/invalidate!` from an fx
;;   the success tx queues, not a second manual request.
;; - Data that arrives from outside a component (embedded page data, a push message) is
;;   neither: a `:db/loading` sentinel ([loading-sentinel](/loading-sentinel/)).

;; # page

(defc ui-root []
  (ref selected (atom nil))
  (bind id (sg/watch selected))
  (event ::select! [env ev e] (reset! selected (:id ev)))

  (render
    (c/page "http-pull"
      {"reading pulls in binds"
       (list-and-detail id)

       ":value and :transform"
       (<< (sg/suspense {:fallback "loading info ..." :timeout 0} (ui-info))
           (sg/suspense {:fallback "searching ..." :timeout 300} (ui-search)))

       ":suspend false, :refresh-every and errors"
       (<< (ui-time) (ui-fail))})))

(defn init []
  (sg/add-kv-table app :item {:primary-key :id} {})
  (sg/add-kv-table app :ui {} {})
  (edn/init! app {})
  (sg/reg-fx app :api
    (http-fx/make-handler {:base-url "/http-pull/api" :request-format :edn}))
  (register-invalidate!)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
