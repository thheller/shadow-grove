(ns examples.defc-basics
  "`defc` expresses incremental computation. A component is an ordered pipeline:

   - **args**: positional inputs from the parent. They only change when the parent
     renders again and passes different values.
   - **`bind` steps**: each stored in a slot. A plain `bind` derives a value from args
     and earlier steps. A bind can also be claimed by code it calls (`sg/kv-lookup`,
     `sg/query`, `sg/watch`, a pull), which then re-runs it when its source changes.
   - **`render`**: the last step, its value becomes the DOM.

   `ref` declares values the instance owns (an atom, a DOM node, a list handle).
   `event`, `effect` and `hook` attach behavior without being part of the computed value.
   The macro works out the dependency graph at compile time, the runtime does no diffing
   to find stale steps.

   The log panel shows which steps ran, the `× n` badges count renders."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; ## args, bind, render
;;
;; `full-name` re-runs only when `first-name` or `last-name` change. `shout` only uses
;; `last-name`, so changing the first name never runs it. `render` runs only when a bind
;; it uses produced a value `not=` to the one before.
;;
;; Setting the first name to the value it already has runs nothing at all: the
;; transaction commits no change, so no component is invalidated.

(defc ui-greeting [first-name last-name]

  (bind full-name
    (do (c/log! "greeting: bind full-name ran")
        (str first-name " " last-name)))

  ;; only uses last-name
  (bind shout
    (do (c/log! "greeting: bind shout ran")
        (.toUpperCase last-name)))

  (render
    (c/log! "greeting: render")
    (<< [:div {:class c/$row}
         "Hello, " [:b full-name] " (" shout ")" (c/render-count ::greeting)])))

;; ## the equality short-circuit
;;
;; When a step re-runs, its new value is compared with `=` to the previous one. Equal
;; means nothing downstream runs. Every change of `n` re-runs `even?`, but only a change
;; that flips the boolean reaches `label` and `render`. Small cheap binds guard expensive
;; work after them.

(defc ui-parity [n]

  ;; re-runs for every n, but n -> n+2 produces the same boolean
  (bind even?
    (do (c/log! "parity: bind even? ran for" n)
        (even? n)))

  ;; uses only even?, so it doesn't run when even? didn't change
  (bind label
    (do (c/log! "parity: bind label ran")
        (if even? "even" "odd")))

  (render
    (<< [:div {:class c/$row} "n is " [:b label] (c/render-count ::parity)])))

;; ## args destructure when the component is called
;;
;; Args destructure like `defn`, `& more` included. The destructuring happens when the
;; component is called, not when it mounts. Every symbol the arg vector binds becomes its
;; own arg: `(ui-destructured {:a 1 :b 2})` makes a seed with the args `[1]`.
;;
;; - Changes to keys the component doesn't bind never reach it, the seeds stay `=`. Click
;;   `b + 1`: the first component doesn't render.
;; - The map itself is only kept, and compared, with `:as`. The second component binds
;;   `:as m`, so `b + 1` updates it. Skip `:as` unless you need the whole map.
;; - A destructure that throws (`:keys!` in Clojure 1.13) throws in the caller's render,
;;   before anything mounts.

(defc ui-destructured [{:keys [a]}]
  (render
    (<< [:div {:class c/$row} "only binds :a, a = " a (c/render-count ::destructured)])))

(defc ui-destructured-as [{:keys [a] :as m}]
  (render
    (<< [:div {:class c/$row} "binds :a and :as m, a = " a ", keys " (pr-str (keys m)) (c/render-count ::destructured-as)])))

;; ## seeds and instances
;;
;; Calling a component, `(ui-kind-a x)`, runs no user code apart from destructuring its
;; args. It returns a cheap **seed**: the definition plus the args vector. A seed only
;; starts computing when something attaches it: a fragment, `simple-seq` / `keyed-seq`,
;; `suspense`, `portal` or `sg/render`.
;;
;; When a parent renders again and produces a seed at the same position:
;;
;; - **equal seed** (same definition, `=` args): nothing happens.
;; - **same definition, different args**: the live instance updates in place. Only the
;;   steps that use a changed arg run again.
;; - **different definition**, or a changed `^:stable` arg, or a custom `:supports?`
;;   returning false: the old instance unmounts (slot and effect cleanups run) and a new
;;   one mounts, with new `ref` values and `:mount` effects firing again. See
;;   [stable-args](/stable-args/).
;;
;; `suspense` relies on this: it attaches the new page's seed offscreen and swaps it in
;; once it stops suspending ([suspense](/suspense/)).

(defc ui-kind-a [x]
  (effect :mount [env]
    (c/log! "kind-a: mounted")
    (fn [] (c/log! "kind-a: unmounted")))
  (render
    (<< [:div {:class c/$row} "component A, x = " x (c/render-count ::kind-a)])))

(defc ui-kind-b [x]
  (effect :mount [env]
    (c/log! "kind-b: mounted")
    (fn [] (c/log! "kind-b: unmounted")))
  (render
    (<< [:div {:class c/$row} "component B, x = " x (c/render-count ::kind-b)])))

;; Something without data or state doesn't need to be a component. A plain function
;; returning a fragment is enough, the demos use one for their hints:

(defn hint [text]
  (<< [:span {:class c/$muted} text]))

;; ## anatomy and the rules the macro checks
;;
;; ```
;; (defc component-name
;;   "optional docstring"
;;   {:supports? (fn [old-args new-args] ...)} ;; optional opts map
;;   [arg1 arg2 & more]          ;; destructuring allowed
;;   (ref name init)             ;; owned value, created once. (ref name) for :dom/ref
;;   (bind name ...)             ;; any number, in order
;;   (hook ...)                  ;; a step without a usable value (prefer effect)
;;   (effect deps [env] ...)     ;; side effect after render
;;   (event ::id [env ev e] ...) ;; local event handler
;;   (render ...))               ;; exactly one
;; ```
;;
;; Enforced at macroexpansion time:
;;
;; - Exactly one `render`. Steps after `render` are an error, `event` may come before or
;;   after it.
;; - A step may only use args and **earlier** steps. A later name is a compile error.
;; - Later binds may shadow earlier names. Destructuring, `(bind {:keys [a b]} ...)`,
;;   makes each piece its own step.
;; - `_` for binds whose value you don't use. At most 30 steps per component, counting
;;   the destructured pieces of binds. Destructured args and `ref`s don't count.
;;
;; `render` returns any renderable: usually one `<<`, but also a string, a number, nil
;; or a component seed. Keep it thin and derive in binds, so `render` can be skipped.
;; Conditional output is plain code, `(if (seq items) (<< ...) (<< [:div "empty"]))`.
;; Components inside markup are calls, never vectors: `[:div (ui-thing x)]`, not
;; `[:div [ui-thing x]]`.
;;
;; The other parts have their own examples: [ref](/refs/), [effect](/effects/),
;; [event](/component-events/), claiming helpers in [bind](/bind-helpers/), and
;; [local state](/local-state/).

;; ## keeping updates cheap
;;
;; - One tracked read per bind (also the rule, see [bind-helpers](/bind-helpers/)), so
;;   unrelated changes don't re-run unrelated steps.
;; - Derive cheap booleans and strings in binds, branch on them in `render`.
;; - Split large components when parts depend on different data. Invalidation is per
;;   instance.
;; - Never cause updates from inside the computation: no `run-tx!`, no atom swaps in
;;   `bind` or `render`. Write from `event` / `effect` via `sg/run-tx`, or in tx
;;   handlers. The one exception is a `vlist3` query fetching its missing rows
;;   ([vlist-remote](/vlist-remote/)).

;; ## troubleshooting
;;
;; - "can't find let binding": a step uses a later or undefined name.
;; - "data bindings can only be defined before render" / "render already defined": step
;;   order, or two renders.
;; - A component remounts unexpectedly (refs lost, `:mount` fires again): a `^:stable`
;;   arg changes. An editor shows the previous entity after navigation: the id is *not*
;;   `^:stable` ([stable-args](/stable-args/)).
;; - It doesn't update when data changes: the read isn't tracked, e.g. a plain `get-in`
;;   on some map instead of `kv-lookup` / `query` ([kv-reads](/kv-reads/)).
;; - "slot already claimed": two claiming calls in one bind. Split them.
;; - "can only be used in component bind": `kv-lookup`, `query`, `watch`, `trace-state`
;;   or `claim-slot!` called from `render`, `event`, `effect` or a `ref` init. Move it
;;   into a bind.
;; - "ref x can only use ^:stable args ...": a `ref` init uses something that changes
;;   ([refs](/refs/)).

;; # page

(deftx set-state! [k v]
  [tx-env ev e]
  (assoc-in tx-env [:demo :state k] v))

(deftx inc-state! [k by]
  [tx-env ev e]
  (update-in tx-env [:demo :state k] + by))

(defc ui-root []
  (bind {:keys [first-name last-name n a b x kind]}
    (sg/kv-lookup :demo :state))

  (render
    (c/page "defc basics"
      {"args, bind, render"
       (<< (ui-greeting first-name last-name)
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click (set-state! :first-name (if (= first-name "Ada") "Grace" "Ada"))} "toggle first name"]
            [:button {:class c/$btn :on-click (set-state! :last-name (if (= last-name "Lovelace") "Hopper" "Lovelace"))} "toggle last name"]
            [:button {:class c/$btn :on-click (set-state! :first-name first-name)} "set first name to the same value"]])

       "the equality short-circuit"
       (<< (ui-parity n)
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click (inc-state! :n 1)} "n + 1"]
            [:button {:class c/$btn :on-click (inc-state! :n 2)} "n + 2"]]
           (hint "n + 2: even? runs and returns the same value, label and render don't run."))

       "args destructure when the component is called"
       (<< (ui-destructured {:a a :b b})
           (ui-destructured-as {:a a :b b})
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click (inc-state! :a 1)} "a + 1"]
            [:button {:class c/$btn :on-click (inc-state! :b 1)} "b + 1"]])

       "seeds and instances"
       (<< (case kind
             :a (ui-kind-a x)
             :b (ui-kind-b x))
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click (set-state! :x x)} "same args"]
            [:button {:class c/$btn :on-click (inc-state! :x 1)} "new args"]
            [:button {:class c/$btn :on-click (set-state! :kind (if (= kind :a) :b :a))} "other component"]]
           (hint "same args: nothing. new args: updates in place. other component: unmount + mount (log)."))})))

(defn init []
  (sg/add-kv-table app :demo {}
    {:state {:first-name "Ada" :last-name "Lovelace" :n 0 :a 1 :b 1 :x 1 :kind :a}})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
