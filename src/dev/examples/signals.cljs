(ns examples.signals
  "A signal is a value that tells its readers when it changes. Grove doesn't ship signals,
   and this is an **experiment**, not a commitment to keeping them in grove. It shows how
   such an abstraction could plug in, using
   [flex](https://github.com/lilactown/flex) as the implementation.

   Two small pieces are enough: a managed node that puts a signal straight into markup,
   and a bind helper that reads one in a component. Both only use what
   [widget-node](/widget-node/) and [bind-helpers](/bind-helpers/) describe. A library
   would provide them, possibly `shadow.grove.flex`, which is an empty namespace for
   now. `town.lilac/flex` is a dev dependency of this repo, not of grove."
  (:require
    [examples.common :as c]
    [shadow.arborist.common :as common]
    [shadow.arborist.protocols :as ap]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.components :as comp]
    [shadow.grove.protocols :as gp]
    [shadow.grove.runtime :as rt]
    [town.lilac.flex :as flex]))

(defonce app (sg/get-runtime :app))

;; ## signals in short
;;
;; `flex/source` holds a value, and calling it changes the value: `(counter inc)`.
;; `flex/signal` derives a value from sources and other signals it derefs.
;;
;; - A signal only passes a change on when its value is no longer `=`, so `fives` stays
;;   quiet for four of five increments.
;; - A signal only runs while something listens. When the last listener goes, it drops
;;   its value and disconnects from its sources. The next listener starts it again.

(defonce counter (flex/source 0))
(defonce other (flex/source 100))

(defonce doubled (flex/signal (* @counter 2)))
(defonce fives (flex/signal (quot @counter 5)))

;; ## a signal in markup
;;
;; `ap/IConstruct` decides what a value becomes in markup. Extended to the two flex
;; types, sources and signals can go into `<<` directly, as `ui-in-markup` does. When
;; the value changes the node updates its own content, the component around it doesn't
;; render.
;;
;; - `connect!` listens first and derefs after. Deref'ing a signal nobody listens to
;;   runs it again and makes flex print a warning.
;; - The listener doesn't touch the DOM, it schedules the node with
;;   `gp/schedule-work!`. Everything that updates the DOM goes through the scheduler, so
;;   a node its parent removes in the same update does no work, and several changes
;;   become one write. `work!` reads the latest value when its turn comes.
;; - `supports?` keeps the node for the same source. `dom-sync!` has nothing to do, a
;;   change always arrives through the listener. A different source gets a new node.
;; - `destroy!` unschedules the node and disposes the listener.
;;
;; Try it: "counter + 1" writes both nodes and the `× n` badge stays at 1, the
;; component rendered once. "counter + 2" changes the source twice in one event, each node
;; still writes once.

(deftype SignalNode [env scheduler src root ^:mutable listener]
  ap/IManaged
  (supports? [this next] (identical? src next))
  (dom-sync! [this next])
  (dom-insert [this parent anchor] (ap/dom-insert root parent anchor))
  (dom-first [this] (ap/dom-first root))
  (dom-entered! [this] (ap/dom-entered! root))
  (destroy! [this dom-remove?]
    (gp/unschedule! scheduler this)
    (flex/dispose! listener)
    (ap/destroy! root dom-remove?))

  gp/IWork
  (work! [this]
    (gp/unschedule! scheduler this)
    (c/log! "node wrote" @src)
    (ap/update! root @src))

  Object
  (connect! [this]
    (set! listener
      (flex/listen src (fn [_] (gp/schedule-work! scheduler this ::signal))))
    (ap/update! root @src)
    this))

(defn as-signal-node [src env]
  (.connect! (SignalNode. env (::sg/scheduler env) src (common/managed-root env) nil)))

(extend-protocol ap/IConstruct
  flex/SyncSource
  (as-managed [src env] (as-signal-node src env))

  flex/SyncSignal
  (as-managed [src env] (as-signal-node src env)))

(defc ui-in-markup []
  (render
    (<< [:div {:class c/$row}
         [:span {:data-markup ""} "count: " counter ", doubled: " doubled]
         (c/render-count ::markup)])))

;; ## a signal in a bind
;;
;; Markup is enough to show a value. To compute with it or branch on it, a component needs
;; it in a `bind`. `sig-listen` is to signals what `sg/watch` is to atoms, a claiming
;; helper as described in [bind-helpers](/bind-helpers/).
;;
;; - The listener ignores the value it gets. `gp/invalidate!` schedules the component,
;;   which runs the bind again, and the bind derefs. A render never sees an old value.
;; - A different signal moves the listener: the old one is disposed, a new one created.
;; - The cleanup runs on unmount and disposes whichever listener is current.
;;
;; Try it: the component renders when `fives` changes, so once per five increments.
;; The signal is the cutoff, the same job as the fn passed to `sg/watch`.

(defn sig-listen [signal]
  (let [ref (rt/claim-slot! ::sig-listen)
        {prev :signal listener :listener} @ref]

    (comp/set-cleanup! ref
      (fn [{:keys [listener]}]
        (flex/dispose! listener)))

    (when-not (identical? signal prev)
      (when listener
        (flex/dispose! listener))
      (reset! ref
        {:signal signal
         :listener (flex/listen signal (fn [_] (gp/invalidate! ref)))}))

    @signal))

(defc ui-in-bind []
  (bind n (sig-listen fives))
  (bind label (if (even? n) "even" "odd"))
  (render
    (<< [:div {:class c/$row}
         [:span {:data-fives ""} "counter / 5 = " n " (" label ")"]
         (c/render-count ::bind)])))

;; ## signals owned by a component
;;
;; A signal made in a `bind` belongs to that component instance. The bind runs again when
;; `src` changes, which makes a new signal: the markup node for `twice` is replaced and
;; `sig-listen` moves to the new `thrice`.
;;
;; There is nothing to clean up by hand. `destroy!` disposes the node's listener, the slot
;; cleanup disposes the one from `sig-listen`, and a signal left without listeners
;; disconnects itself from its source.
;;
;; Try it: "counter + 1" runs both signals (see the log). "use other" moves them to the
;; other source, so "counter + 1" runs neither and "other + 1" runs both. "hide" removes the
;; component, then no change runs anything.

(defc ui-owned [src]
  (bind twice
    (flex/signal
      (c/log! "twice ran")
      (* @src 2)))

  (bind thrice
    (flex/signal
      (c/log! "thrice ran")
      (* @src 3)))

  (bind n (sig-listen thrice))

  (render
    (<< [:div "twice: " twice ", thrice: " n])))

;; ## what signals don't bring along
;;
;; Everything else grove offers is built around transactions and the kv tables. Signals
;; sit next to that, they don't join it.
;;
;; - Changing a source is a plain function call. There is no tx handler, no `:fx`, and
;;   nothing for the devtools to show. Data that lives in kv tables stays in
;;   [transactions](/transactions/).
;; - `sig-listen` never suspends, a signal always has a value. Pending states are the
;;   signal's own business, unlike [suspense](/suspense/).

;; # page

(defc ui-buttons []
  (event ::inc! [env ev e]
    (counter inc))

  (event ::inc-twice! [env ev e]
    (counter inc)
    (counter inc))

  (render
    (<< [:div {:class [c/$row (css :mb-2)]}
         [:button {:class c/$btn :on-click ::inc!} "counter + 1"]
         [:button {:class c/$btn :on-click ::inc-twice!} "counter + 2"]])))

(defc ui-root []
  (ref state (atom {:show? true :other? false}))

  (bind {:keys [show? other?]} (sg/watch state))

  (event ::show! [env ev e]
    (swap! state update :show? not))

  (event ::switch! [env ev e]
    (swap! state update :other? not))

  (event ::inc-other! [env ev e]
    (other inc))

  (render
    (c/page "signals"
      {"a signal in markup"
       (<< (ui-buttons)
           (ui-in-markup))

       "a signal in a bind"
       (<< (ui-buttons)
           (ui-in-bind))

       "signals owned by a component"
       (<< (ui-buttons)
           [:div {:class [c/$row (css :mb-2)]}
            [:button {:class c/$btn :on-click ::inc-other!} "other + 1"]
            [:button {:class c/$btn :on-click ::switch!} (if other? "use counter" "use other")]
            [:button {:class c/$btn :on-click ::show!} (if show? "hide" "show")]]
           (when show?
             (ui-owned (if other? other counter))))})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
