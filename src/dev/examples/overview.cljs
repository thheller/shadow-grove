(ns examples.overview
  "`shadow-grove` is a ClojureScript UI library with no external dependencies (no
   React). You describe DOM with the `<<` fragment macro, organize stateful UI in `defc`
   components, keep data in KV tables, and change data only through transaction handlers
   (`deftx` / `reg-event`). Start here, then follow the links to the example for whatever
   you are working on. Every example is a page like this one: the text, a live demo, and
   the code that runs it, read from the example's own source file."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

(declare ui-root)

;; ## the five pieces
;;
;; 1. **Runtime** (`sg/get-runtime`, `sg/render`): one atom per app id, holding the KV
;;    tables, event and fx registrations, and mounted roots. Created once, it survives
;;    hot-reload.
;; 2. **KV store** (`sg/add-kv-table`, `sg/kv-lookup`, `sg/query`): the data, as plain
;;    maps. Reads through `kv-lookup` and `query` are observed, so only components whose
;;    reads changed render again ([kv-reads](/kv-reads/), [kv-writes](/kv-writes/)).
;; 3. **Components** (`defc`): incremental computations of `bind` steps, `ref`s for owned
;;    values, one `render`, plus `event` and `effect`. Calling one only creates a cheap
;;    seed: `(ui-item id)` ([defc-basics](/defc-basics/)).
;; 4. **Fragments** (`<<`): hiccup-like markup compiled to minimal create and update code.
;;    `[:div ...]` is DOM, `(ui-item id)` is a component, never `[ui-item id]`
;;    ([markup](/markup/)).
;; 5. **Events and transactions** (`deftx`, `sg/reg-event`, `sg/queue-fx`): the only way
;;    KV data changes. Markup carries an event map. If no component `event` slot claims
;;    it, the root runs the handler with `tx-env` (all tables), commits the returned env
;;    and renders what changed ([transactions](/transactions/)). Component-local state (an
;;    atom in a `ref`) is the one thing that changes without a tx
;;    ([local-state](/local-state/)).
;;
;; ```
;; DOM event -> event map -> component event slot (optional)
;;   -> tx handler (tx-env -> tx-env)
;;   -> KV tables updated -> components whose reads changed run again
;;   -> fragments patch the DOM -> queued fx run
;; ```

;; ## one interaction, end to end
;;
;; A checklist: one table, one deftx, one query, two components. Check an item and watch
;; the `× n` render counters.
;;
;; `(toggle! id)` is just data, `{:e ::toggle! :args [id]}` with the handler in its
;; metadata. No component has a slot for it, so the root runs it. The handler returns
;; `tx-env` with `[:item id :done?]` flipped. The runtime commits it and runs only what
;; read changed data: the row of that item (`kv-lookup :item id`) and the summary (its
;; query read every item). The other rows don't render, and nothing in the checklist
;; component itself does, its `?item-ids` result didn't change.

(deftx toggle! [id]
  [tx-env ev e]
  (update-in tx-env [:item id :done?] not))

(defn ?item-ids [env]
  (->> (vals (:item env)) (sort-by :id) (mapv :id)))

(defn ?summary [env]
  (let [items (vals (:item env))]
    {:done (count (filter :done? items))
     :total (count items)}))

(defc ui-item [id]
  (bind {:keys [label done?]} (sg/kv-lookup :item id))
  (render
    (<< [:label {:class (css :flex :items-center :gap-2 :py-1) :data-item id}
         [:input {:type "checkbox" :checked done? :on-change (toggle! id)}]
         [:span {:class (when done? (css :line-through :text-gray-400))} label]
         (c/render-count [::item id])])))

(defc ui-summary []
  (bind {:keys [done total]} (sg/query ?summary))
  (render
    (<< [:div {:class [c/$row (css :mt-2)] :data-summary ""} done " of " total " done" (c/render-count ::summary)])))

(defc ui-checklist []
  (bind ids (sg/query ?item-ids))
  (render
    (<< [:div {:data-checklist ""}
         (sg/keyed-seq ids identity ui-item)
         (ui-summary)
         [:div {:class c/$muted} "checklist rendered " (c/render-count ::checklist)]])))

;; ## bootstrap
;;
;; The `:init-fn` of the build sets up tables, events and transport, then renders.
;; `^:dev/after-load` renders again after a hot-reload: the second `sg/render` updates
;; instead of mounting.
;;
;; - `add-kv-table` is init-only, it overwrites the table ([kv-writes](/kv-writes/)).
;; - Order matters once there are routes: everything `:ui/route!` reads must exist before
;;   `history/init!`, and `history/init!` must run before the first render
;;   ([routing](/routing/)). A session that arrives later defers both
;;   ([auth-gate](/auth-gate/)).
;; - The root can be any renderable: a `defc`, a plain fn returning a fragment, a
;;   `suspense`. Chrome that must survive a suspending page goes beside the boundary
;;   ([suspense](/suspense/)).
;;
;; ```
;; (defn ^:dev/after-load reload! []
;;   (sg/render app (js/document.getElementById "root") (ui-root)))
;; ```

(defn start! []
  (sg/add-kv-table app :item {:primary-key :id}
    {1 {:id 1 :label "milk" :done? false}
     2 {:id 2 :label "bread" :done? true}
     3 {:id 3 :label "apples" :done? false}})
  ;; events, fx and transport go here: reg-event, reg-fx, edn/init!
  ;; then history/init! if the app has routes
  (sg/render app (js/document.getElementById "root") (ui-root)))

;; ## page structure
;;
;; One root component reads `[:ui :current-page]`, written by `:ui/route!`, and `case`s
;; on it inside a `sg/suspense`. A tab bar gets only the slice it needs
;; ([routing](/routing/)). Dialogs and overlays use `sg/portal` ([portal](/portal/)).
;; Things that need no data or state are plain `defn`s returning fragments.

;; ## where things belong
;;
;; - Data from a table: `bind` + `sg/kv-lookup` / `sg/query` ([kv-reads](/kv-reads/)).
;;   Never deref runtime state in render.
;; - Deriving, sorting, filtering: a `?`-prefixed `defn` of `env`, called through
;;   `sg/query`, composed by calling other query fns ([kv-reads](/kv-reads/)).
;; - State only this component (and the children it passes it to) cares about: an atom in
;;   a `ref`, read with `sg/watch` ([local-state](/local-state/)).
;; - The user did something: an event map in markup, a tx ([transactions](/transactions/),
;;   [dom-events](/dom-events/)). Keys: `::keyboard/listen` ([keyboard](/keyboard/)).
;;   Forms: [forms](/forms/).
;; - Local work before (or instead of) a tx, like closing a dialog or animating: a
;;   component `event` slot, then `run-tx` / `dispatch-up!`
;;   ([component-events](/component-events/), [animations](/animations/)).
;; - A side effect after render (focus, measure, `.showModal`): an `effect` and
;;   `(ref node)` ([effects](/effects/), [refs](/refs/)). After a tx: an fx ([fx](/fx/)).
;; - Markup, attributes, `css`: [markup](/markup/). Collections: [lists](/lists/).
;;   Hundreds of rows and more: [vlist](/vlist/).
;; - Code loaded on demand: [lazy-loading](/lazy-loading/).
;; - URLs and navigation: `history/init!` and `:ui/route!` ([routing](/routing/)).
;; - A user action that hits the server: `queue-fx` an http-fx request, the success
;;   re-enters as a deftx ([http-fx](/http-fx/)).
;; - Data a screen needs before it can render: `:ui/route!` queues an http-fx request, the
;;   page suspends on a `:db/loading` sentinel until it lands, `sg/suspense` above it
;;   ([auth-gate](/auth-gate/), [loading-sentinel](/loading-sentinel/)). Data that
;;   arrives from outside (embedded session, push) the same way
;;   ([external-events](/external-events/)). An `http-pull` in a `bind` is the
;;   experimental alternative ([http-pull](/http-pull/)). Neither is a settled design.
;; - A connection that stays open: messages in as txs, out as fx
;;   ([websocket](/websocket/)).
;; - The built-ins don't fit (a specialized list, a widget that owns its DOM, subtree
;;   context): your own managed node ([widget-node](/widget-node/)).

;; ## namespaces
;;
;; | require | for |
;; |---|---|
;; | `[shadow.grove :as sg :refer (defc deftx css <<)]` | everything core |
;; | `[shadow.grove.kv :as kv]` | `kv/add`, `kv/merge-seq`, `kv/set`, ... ([kv-writes](/kv-writes/)) |
;; | `[shadow.grove.history :as history]` | `history/init!` ([routing](/routing/)) |
;; | `[shadow.grove.http-fx :as http-fx]` | `make-handler`, `trace-state` ([http-fx](/http-fx/)) |
;; | `[shadow.grove.http-pull :as pull]` | `pull/setup`, `pull/invalidate!` ([http-pull](/http-pull/)) |
;; | `[shadow.grove.edn :as edn]`, `[shadow.grove.transit :as transit]` | `init!`, response readers ([http-fx](/http-fx/)) |
;; | `[shadow.grove.keyboard :as keyboard]` | `::keyboard/listen`, key events ([keyboard](/keyboard/)) |
;; | `[shadow.grove.ui.vlist3 :as vlist3]` | virtual lists ([vlist](/vlist/)) |
;; | `[shadow.grove.ui.lazy :as lazy]` | lazy-loaded components ([lazy-loading](/lazy-loading/)) |
;; | `[shadow.grove.ui.edn :as ui-edn]` | `render-edn` ([edn-view](/edn-view/)), clashes with `shadow.grove.edn` |
;; | `[shadow.arborist.dom-scheduler :as ds]` | `ds/read!` / `ds/write!` ([effects](/effects/)) |
;; | `[shadow.grove.runtime :as rt]`, `[shadow.grove.components :as comp]` | `rt/claim-slot!`, `comp/set-cleanup!`, for helper authors ([bind-helpers](/bind-helpers/)) |
;; | `[shadow.arborist.protocols :as ap]`, `[shadow.arborist.common :as common]`, `[shadow.grove.protocols :as gp]`, `[shadow.arborist.attributes :as attr]` | custom managed nodes and attributes ([widget-node](/widget-node/)) |
;;
;; Not covered here, don't reach for them: `shadow.grove.events` (deprecated, see
;; [transactions](/transactions/)), `event-fsm`, `local`, `eql-query`,
;; `db.ident`, `ui.grid`, `ui.loadable`, `cards.*`, `server`, `ui.vlist2` (deprecated,
;; [vlist](/vlist/)). `ui.vgrid` and `ui.dnd-sortable` are reference implementations for
;; custom nodes, not documented for direct use. `flex` is an empty namespace, see
;; [signals](/signals/) for the experiment it is reserved for.

;; ## project setup
;;
;; - Dev tooling: add the preload to the build, `:devtools {:preloads [shadow.grove.preload]}`.
;;   `shadow.grove.dev-support` is an old alias for it.
;; - clj-kondo: the library ships `clj-kondo.exports` with hooks for `defc` and `deftx`.
;;   Import them with `clj-kondo --copy-configs --dependencies --lint "$(clojure -Spath)"`
;;   (or the equivalent for your build tool), so bind names and deftx args resolve.
;; - These examples: one `:target :esm` build in this repo's `shadow-cljs.edn`, one module
;;   per example, see `examples/README.md`.

;; # page

(defc ui-root []
  (render
    (c/page "overview"
      {"one interaction, end to end" (ui-checklist)})))

(defn init []
  (start!))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
