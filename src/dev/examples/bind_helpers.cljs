(ns examples.bind-helpers
  "A plain `bind` derives its value from args and earlier steps. A bind can also be
   **claimed** by code it calls, which hands that code a ref owned by the component
   instance, so it can keep state and invalidate the bind itself. That is how a bind
   gets inputs from outside the parent:

   | call in `bind` | re-runs when |
   |---|---|
   | `sg/kv-lookup`, `sg/kv-lookups`, `sg/query` | the KV data it read changed ([kv-reads](/kv-reads/)) |
   | `sg/watch atom`, `sg/env-watch` | the atom (or path, or fn result) changed ([local-state](/local-state/), [child-host](/child-host/)) |
   | `sg/use-state` | `sg/swap-state!` on it ([local-state](/local-state/)) |
   | a `http-pull` | the request completes, suspends while pending ([http-pull](/http-pull/)) |
   | `http-fx/trace-state` | request progress changes ([http-fx](/http-fx/)) |
   | `sg/track-change` | its value changed, see [effects](/effects/) |
   | your own helper using `rt/claim-slot!` | whatever it decides, below |

   `(ref name)` and `(sg/ref)` don't claim, they are plain volatiles for `:dom/ref`."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.components :as comp]
    [shadow.grove.runtime :as rt]))

(defonce app (sg/get-runtime :app))

;; ## one claim per bind
;;
;; A reading helper is a plain function that a bind calls. Whatever it reads claims the
;; bind it runs in.
;;
;; A second claim in the same bind throws "slot already claimed". Two lookups, or a pull
;; plus a lookup, need two binds, which is also what keeps their invalidation
;; independent. Several keys of one table go through one `sg/kv-lookups`, several tables
;; through one query ([kv-reads](/kv-reads/)).
;;
;; Try the button: `ui-two-reads` calls `item-label` twice in one bind and fails to
;; render (the console shows the error).

(defn item-label [id]
  (sg/kv-lookup :item id :label))

(defc ui-label [id]
  (bind label (item-label id))
  (render (<< [:span {:class c/$badge} label])))

;; several keys of one table in one read
(defc ui-labels [ids]
  (bind items (sg/kv-lookups :item ids))
  (render
    (<< [:span (pr-str (into {} (map (fn [[k v]] [k (:label v)])) items))])))

;; two claims in one bind, throws
(defc ui-two-reads [ids]
  (bind labels (mapv item-label ids))
  (render (<< [:span (pr-str labels)])))

;; ## a custom source read with sg/watch
;;
;; `sg/watch` works with anything that implements `IWatchable` and `IDeref`, not only
;; atoms. The fn passed to `sg/watch` is the cutoff: this ticker counts every 200ms, the
;; component only renders when `(quot n 5)` changes.

(deftype Ticker [^:mutable n ^:mutable watches]
  IDeref
  (-deref [_] n)

  IWatchable
  (-notify-watches [this old new]
    (doseq [[k f] watches]
      (f k this old new)))
  (-add-watch [this k f]
    (set! watches (assoc watches k f))
    this)
  (-remove-watch [this k]
    (set! watches (dissoc watches k))
    this)

  Object
  (tick! [this]
    (let [old n]
      (set! n (inc n))
      (-notify-watches this old n))))

(defonce ticker (Ticker. 0 {}))

(defonce tick-interval
  (js/setInterval #(.tick! ticker) 200))

(defc ui-ticker []
  (bind fives (sg/watch ticker (fn [n] (quot n 5))))
  (render
    (<< [:div {:class c/$row} "ticks / 5 = " fives (c/render-count ::ticker)])))

;; ## claim-slot!: owned state with a cleanup
;;
;; `shadow.grove.runtime/claim-slot!` is the mechanism behind the helpers above. Normal
;; components don't need it. Use it when writing a helper other components call, e.g. a
;; form draft shared by several field editors. When one component is enough, prefer an
;; atom in a `ref` ([local-state](/local-state/)).
;;
;; - It is only callable while a slot runs (`bind` or `hook`). From `render`, `event`,
;;   `effect` or a `ref` init it throws "can only be used in component bind".
;; - It returns a ref that survives re-runs. Initialize it on the first run,
;;   `(when (nil? @ref) ...)`, and register teardown with `comp/set-cleanup!`. The
;;   cleanup runs on unmount with the ref's value.
;; - `swap!` / `reset!` from **inside** the running slot just stores. From outside (an
;;   event, a callback, the interval here) it invalidates the slot and schedules an
;;   update. `gp/invalidate!` on the ref does the same without changing the value.
;; - The bind's value is whatever the helper returns (`@ref`, a form map). Don't return
;;   the ref itself unless callers should hold it.

(defn use-interval-count
  "counts up every ms while the bind owning it is alive"
  [label ms]
  (let [ref (rt/claim-slot! ::interval)]
    (when (nil? @ref)
      ;; inside the running slot, swap! only stores
      (swap! ref assoc :n 0 :id
        (js/setInterval
          (fn []
            (c/log! label "interval fired")
            ;; outside the slot, swap! invalidates it and schedules an update
            (swap! ref update :n inc))
          ms))
      (comp/set-cleanup! ref
        (fn [{:keys [id]}]
          (c/log! label "interval cleared")
          (js/clearInterval id))))
    (:n @ref)))

(defc ui-interval []
  (bind n (use-interval-count "always:" 1000))
  (render (<< [:div "seconds mounted: " n])))

;; ## a claim under a condition
;;
;; A `when` around a claiming call is fine: the branch that doesn't run doesn't claim,
;; e.g. a pull that needs an arg which may not be there yet. But it doesn't release
;; something claimed on an earlier run either. That stays until the component unmounts.
;;
;; Turn it on, then off: the component stays mounted, only the condition flips, and the
;; log shows the interval still firing. Don't use `when` to drop a subscription
;; mid-life, unmount the component that owns it instead (e.g. `(when on? (ui-thing))`
;; in the parent).

(defc ui-conditional-claim [on?]
  (bind n (when on? (use-interval-count "conditional:" 1000)))
  (render (<< [:div "conditional claim: " (if on? (str "on, " n) "off")])))

;; # page

(deftx toggle! [k]
  [tx-env ev e]
  (update-in tx-env [:ui :flags k] not))

(defc ui-root []
  (bind {:keys [show-interval? claim-on? two-reads?]} (sg/kv-lookup :ui :flags))

  (render
    (c/page "bind helpers"
      {"one claim per bind"
       (<< [:div {:class c/$row} (ui-label 1) (ui-label 2) (ui-labels [1 2 3])]
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click (toggle! :two-reads?)} (if two-reads? "remove" "try two reads in one bind")]
            (when two-reads?
              (ui-two-reads [1 2]))])

       "a custom source read with sg/watch"
       (ui-ticker)

       "claim-slot!: owned state with a cleanup"
       (<< (when show-interval?
             (ui-interval))
           [:button {:class [c/$btn (css :mt-2)] :on-click (toggle! :show-interval?)}
            (if show-interval? "unmount" "mount")])

       "a claim under a condition"
       (<< (ui-conditional-claim claim-on?)
           [:button {:class [c/$btn (css :mt-2)] :on-click (toggle! :claim-on?)}
            (if claim-on? "turn off" "turn on")])})))

(defn init []
  (sg/add-kv-table app :item {:primary-key :id}
    {1 {:id 1 :label "one"}
     2 {:id 2 :label "two"}
     3 {:id 3 :label "three"}})
  (sg/add-kv-table app :ui {}
    {:flags {:show-interval? true :claim-on? false :two-reads? false}})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
