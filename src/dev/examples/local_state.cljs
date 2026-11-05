(ns examples.local-state
  "State that only one component (and the children it passes it to) cares about doesn't
   need a KV table. Where things go:

   - shared or domain data: a KV table ([kv-reads](/kv-reads/), [kv-writes](/kv-writes/))
   - per-component ephemeral data (a dialog flag, a list filter, a form draft): an atom in
     a `ref`, or `use-state`
   - atoms from outside grove: `sg/watch`
   - DOM nodes: `(ref node)` ([refs](/refs/))"
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc << css)]))

(defonce app (sg/get-runtime :app))

;; ## an atom in a ref, read with sg/watch
;;
;; The atom is created once per instance ([refs](/refs/)). The ref never changes, so binds
;; that only use `state` itself run once. Reading goes through `sg/watch` with a path,
;; and a write re-runs only the binds whose watched path changed.
;;
;; Passing `state` to children is cheap for the same reason: their args stay `=`, and each
;; child watches what it needs. Click `a + 1`: only the `a` row renders, not `b`, not the
;; parent. Closing drops the children, the atom and its values stay with the parent.
;;
;; Each `sg/watch` claims its bind, so one per bind. Writes happen in `event` slots (or
;; effects and callbacks), never in a bind or render. Outside a bind `sg/watch` throws;
;; events and effects just `@state`.

(defc ui-counter-child [state k]
  (bind v (sg/watch state [k]))
  (event ::inc! [env ev e]
    (swap! state update k inc))
  (render
    (<< [:div {:class c/$row}
         [:button {:class c/$btn :on-click ::inc!} (str (name k) " + 1")]
         (str (name k) " = " v)
         (c/render-count [::counter-child k])])))

(defc ui-atom-in-ref []
  (ref state (atom {:open? false :a 0 :b 0}))
  (bind open? (sg/watch state [:open?]))

  (event ::toggle! [env ev e]
    (swap! state update :open? not))

  (render
    (<< [:div {:class c/$row}
         [:button {:class c/$btn :on-click ::toggle!} (if open? "close" "open")]
         (c/render-count ::atom-in-ref)]
        (when open?
          (<< [:div (css :pl-4 :mt-2)
               (ui-counter-child state :a)
               (ui-counter-child state :b)])))))

;; ## sg/watch with a fn
;;
;; `(sg/watch atom f)` invalidates only when `(f value)` changes. This one renders only
;; when `n` crosses a multiple of 10. The forms are `(sg/watch atom)`,
;; `(sg/watch atom [:path])` and `(sg/watch atom f)`.

(defc ui-watch-fn []
  (ref state (atom {:n 0}))
  (bind tens (sg/watch state (fn [{:keys [n]}] (quot n 10))))
  (event ::inc! [env ev e]
    (swap! state update :n inc))
  (render
    (<< [:div {:class c/$row}
         [:button {:class c/$btn :on-click ::inc!} "n + 1"]
         "tens: " tens
         [:span {:class c/$muted} "(n = " (:n @state) " when this rendered)"]
         (c/render-count ::watch-fn)])))

;; ## watching an atom from outside
;;
;; `sg/watch` works with any atom, so it is also the interop with state that grove
;; doesn't own. This one is changed by a plain `setInterval`, no transaction involved.
;; For anything other than an atom, see the custom source in
;; [bind-helpers](/bind-helpers/).

(defonce outside-atom (atom 0))

(defonce ticker
  (js/setInterval #(swap! outside-atom inc) 1000))

(defc ui-outside []
  (bind v (sg/watch outside-atom))
  (render
    (<< [:div {:class c/$row}
         "outside atom = " v
         [:span {:class c/$muted} "(changed by a plain setInterval, no tx)"]])))

;; ## use-state and swap-state!
;;
;; The older form: state owned by one bind, created with `(sg/use-state init-map)` or
;; `(sg/use-state init-map merge-fn)`, updated with `(sg/swap-state! state f & args)`.
;;
;; - `init-map` must support metadata, a map does, a keyword doesn't.
;; - `swap-state!` needs the value `use-state` returned (its ref lives in the metadata),
;;   so keep it with `:as`. Swapping a destructured key throws.
;;
;; Prefer the atom in a `ref`: it can be passed to children and read by path.

(defc ui-use-state []
  (bind {:keys [query] :as state}
    (sg/use-state {:query ""}))

  (event ::input! [env ev e]
    (sg/swap-state! state assoc :query (.. e -target -value)))

  (render
    (<< [:div {:class c/$row}
         [:input {:class c/$input :placeholder "type here" :value query :on-input ::input!}]
         "length " (count query)])))

;; # page

(defc ui-root []
  (render
    (c/page "local state"
      {"an atom in a ref, read with sg/watch" (ui-atom-in-ref)
       "sg/watch with a fn" (ui-watch-fn)
       "watching an atom from outside" (ui-outside)
       "use-state and swap-state!" (ui-use-state)})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
