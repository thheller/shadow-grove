(ns examples.async-node
  "A managed node whose content arrives later and cooperates with `suspense`, the way
   `lazy/use` does ([lazy-loading](/lazy-loading/)). The protocols and lifecycle are in
   [widget-node](/widget-node/)."
  (:require
    [examples.common :as c]
    [shadow.arborist.common :as common]
    [shadow.arborist.protocols :as ap]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.protocols :as gp]))

(defonce app (sg/get-runtime :app))

;; ## reporting the wait to suspense
;;
;; `(::sg/scheduler env)` is the nearest `gp/IScheduleWork` (`schedule-work!`,
;; `unschedule!`, `did-suspend!`, `did-finish!`): a component, a suspense boundary, or the
;; root. Grab it at construction and report the wait:
;; `(gp/did-suspend! scheduler this)` when it starts, `(gp/did-finish! scheduler this)`
;; when the content is in, and also from `destroy!` if it never finished. A boundary above
;; shows its fallback until every `did-suspend!` has its `did-finish!` (`:timeout 0`
;; here, so at once).
;;
;; - Own a marker (`common/dom-marker`), so `dom-first` and `dom-insert` work before the
;;   content exists.
;; - When the content arrives, create it with `ap/as-managed` and the node's env, and
;;   insert it after the marker if the node is already in the document. Call
;;   `dom-entered!` on it if the node already entered.
;; - `dom-sync!` updates the content in place when it supports the new body, or replaces
;;   it with `common/replace-managed`.
;;
;; Two delayed nodes share one boundary. The fallback stays until both finished, the
;; 300ms node waits for the 900ms one. "again" starts a new round under a new boundary.

(declare DelayedSeed)

(deftype DelayedNode
  [env scheduler marker ms body
   ^:mutable managed
   ^:mutable timeout
   ^:mutable dom-entered?]

  ap/IManaged
  (supports? [this next]
    ;; a different delay is a different thing, replace
    (and (instance? DelayedSeed next) (= ms (.-ms ^DelayedSeed next))))

  (dom-sync! [this ^DelayedSeed next]
    (when managed
      (if (ap/supports? managed (.-body next))
        (ap/dom-sync! managed (.-body next))
        (let [new (common/replace-managed env managed (.-body next))]
          (set! managed new)
          (when dom-entered? (ap/dom-entered! new))))))

  ;; the marker exists from the start, so dom-first and dom-insert work before the content
  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor)
    (when managed
      (ap/dom-insert managed parent anchor)))

  (dom-first [this] marker)

  (dom-entered! [this]
    (set! dom-entered? true)
    (when managed
      (ap/dom-entered! managed)))

  (destroy! [this dom-remove?]
    (when timeout
      (js/clearTimeout timeout)
      ;; never finished, tell the boundary we're gone
      (gp/did-finish! scheduler this))
    (when dom-remove?
      (.remove marker))
    (when managed
      (ap/destroy! managed dom-remove?)))

  Object
  (start! [this]
    (gp/did-suspend! scheduler this)
    (set! timeout
      (js/setTimeout
        (fn []
          (set! timeout nil)
          (c/log! "delayed node: content for" ms "ms ready")
          (set! managed (ap/as-managed body env))
          ;; already inserted? then put the content after the marker
          (when-some [parent (.-parentNode marker)]
            (ap/dom-insert managed parent (.-nextSibling marker))
            (when dom-entered?
              (ap/dom-entered! managed)))
          (gp/did-finish! scheduler this))
        ms))))

(deftype DelayedSeed [ms body]
  ap/IConstruct
  (as-managed [this env]
    (doto (DelayedNode. env (::sg/scheduler env) (common/dom-marker env "delayed") ms body nil nil false)
      (.start!))))

(defn delayed [ms body]
  (DelayedSeed. ms body))

;; keyed by round: a new round is a new boundary
(defn two-delayed [round]
  (sg/keyed-seq [round] identity
    (fn [r]
      (sg/suspense {:fallback (<< [:div#fallback "waiting for both ..."]) :timeout 0}
        (<< [:div#content
             (delayed 300 (<< [:div#fast "fast content, round " r]))
             (delayed 900 (<< [:div#slow "slow content, round " r]))])))))

;; # page

(defc ui-root []
  (ref state (atom {:round 0 :show? true}))
  (bind {:keys [round show?]} (sg/watch state))
  (event ::again! [env ev e] (swap! state update :round inc))
  (event ::show! [env ev e] (swap! state update :show? not))

  (render
    (c/page "async node"
      {"reporting the wait to suspense"
       (<< [:div {:class [c/$row (css :mb-2)]}
            [:button {:class c/$btn :on-click ::again!} "again"]
            [:button {:class c/$btn :on-click ::show!} (if show? "hide" "show")]]
           (when show?
             (two-delayed round)))})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
