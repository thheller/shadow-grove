(ns examples.component-events
  "How an event map travels. Markup carries an event map (`:on-click (count! \"plain\")`
   or `{:e ::local!}`, see [dom-events](/dom-events/)). The DOM event starts at the
   component owning the fragment:

   ```
   owning component's (event ...) slot, if it has one for the :e
     -> else the parent component, its parent, ...
     -> the root, which runs the tx (deftx in the map's metadata, or reg-event)
   ```

   A matching slot **handles and stops** the event. The tx doesn't run unless the slot
   continues with `sg/run-tx` or `sg/dispatch-up!`.

   `(event ::id [env ev e] body...)`, or `(event ::id handler-fn)`: `env` is the
   **component env** (not the tx-env), `ev` the event map, `e` the DOM event. All args
   and binds are in scope with their values from the last render, refs too. An optional
   fourth arg, `origin`, is the env where the event started. The return value is
   ignored. Slots don't write KV: they do local work (an atom in a `ref`, an
   animation, reading the DOM) and continue into a tx when data should change."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; ## bubbling
;;
;; The first button's map is the `count!` deftx, and no component has a slot for it,
;; so it reaches the root, which runs the tx. That is why most markup needs no slot at
;; all.
;;
;; The second button sits in `ui-child`, which has no `::local!` slot. The event goes on
;; to `ui-parent`, whose slot handles it.

(deftx count! [where]
  [tx-env ev e]
  (c/log! "tx count! ran from" where ", DOM event:" (if e (.-type e) "nil"))
  (update-in tx-env [:ui :count] inc))

(defc ui-plain []
  (render
    (<< [:button {:class c/$btn :on-click (count! "plain")} "no slot"])))

(defc ui-child []
  (render
    (<< [:button {:class c/$btn :on-click {:e ::local! :from "child"}} "child button, parent slot"])))

(defc ui-parent []
  (event ::local! [env {:keys [from]} e]
    (c/log! "parent slot ::local! handled event from" from))
  (render
    (<< [:div {:class c/$row} (ui-child)])))

;; ## slots for a deftx keyword
;;
;; A slot whose id is the deftx's `:e` gets the map before the root does. The deftx
;; keyword is namespaced to where it is defined, so `::count!` here matches `(count! ...)`
;; from the same ns.
;;
;; - Only the slot runs: the tx doesn't (first button).
;; - `(sg/run-tx env ev e)` enters the tx pipeline now. Pass `e` when the handler reads
;;   the DOM event (submit handlers reading the form).
;; - `(sg/dispatch-up! env ev)` sends the map on to the parent component, and eventually
;;   the root, **without** the DOM event: the handler gets `e` as nil. A handler calling
;;   `(.preventDefault e)` throws on that path, so prevent default before dispatching, or
;;   use `run-tx`. `dispatch-up!` requires a namespaced `:e`.
;;
;; A slot that only does local work and never continues doesn't become a transaction.
;; That is how a dialog closes without a KV write ([portal](/portal/)), and how a leave
;; animation holds back the removal until it is done ([animations](/animations/)).

(defc ui-swallow []
  (event ::count! [env ev e]
    (c/log! "slot swallowed count!, the tx doesn't run"))
  (render
    (<< [:button {:class c/$btn :on-click (count! "swallow")} "slot swallows"])))

(defc ui-run-tx []
  (event ::count! [env ev e]
    (c/log! "slot did local work, now sg/run-tx with e")
    (sg/run-tx env ev e))
  (render
    (<< [:button {:class c/$btn :on-click (count! "run-tx")} "slot + run-tx"])))

(defc ui-dispatch-up []
  (event ::count! [env ev e]
    (c/log! "slot did local work, now sg/dispatch-up!")
    (sg/dispatch-up! env ev))
  (render
    (<< [:button {:class c/$btn :on-click (count! "dispatch-up")} "slot + dispatch-up!"])))

;; ## components extract, transactions decide
;;
;; A component can turn DOM specifics into data and send a new map up. The picker
;; doesn't know what picking means: it reports `{:e ::picked! :value v}`, and whoever
;; holds it decides. Here the host keeps the value in local state. A caller can also pass
;; a deftx map in as an arg and the component `assoc`s the extracted value onto it before
;; `run-tx`, e.g. the selected option of a `<select>`.

(defc ui-picker []
  (event ::picked! [env {:keys [value]} e]
    (sg/dispatch-up! env {:e ::picked! :value value}))
  (render
    (<< [:div {:class c/$row}
         [:button {:class c/$btn :on-click {:e ::picked! :value :red}} "red"]
         [:button {:class c/$btn :on-click {:e ::picked! :value :blue}} "blue"]])))

(defc ui-picker-host []
  (ref picked (atom nil))
  (bind v (sg/watch picked))
  (event ::picked! [env {:keys [value]} e]
    (c/log! "host received" value "from the picker")
    (reset! picked value))
  (render
    (<< [:div {:class c/$row} (ui-picker) "picked: " (pr-str v)])))

;; ## unhandled events
;;
;; No slot matched, the map has no deftx handler in its metadata, and there is no
;; `reg-event` for its `:e`: the root throws, and the console shows `"Unhandled Event"`
;; with the component trace the event passed. Usually a typo in the `:e` keyword, a
;; missing `reg-event`, or a quoted keyword where the deftx constructor should be called.
;; (A slot that matched but didn't continue is silent, see above.)

(defc ui-unhandled []
  (render
    (<< [:button {:class c/$btn :on-click {:e ::nobody-handles-this!}} "unhandled event"])))

;; # page

(defn count-line [n]
  (<< [:p {:class [c/$row (css :mb-2)]} "count (written by the count! tx): " [:b n]]))

(defc ui-root []
  (bind n (sg/kv-lookup :ui :count))
  (render
    (c/page "component events"
      {"bubbling"
       (<< (count-line n)
           [:div {:class c/$row} (ui-plain) (ui-parent)])

       "slots for a deftx keyword"
       (<< (count-line n)
           [:div {:class c/$row} (ui-swallow) (ui-run-tx) (ui-dispatch-up)])

       "components extract, transactions decide"
       (ui-picker-host)

       "unhandled events"
       (ui-unhandled)})))

(defn init []
  (sg/add-kv-table app :ui {} {:count 0})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
