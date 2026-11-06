(ns examples.vlist
  "`shadow.grove.ui.vlist3` renders lists too large to mount fully: hundreds of rows and
   more. It mounts only the rows in view plus some overscan. It measures its scroll
   container, asks a query for just that window, and moves a positioned box of rows as
   you scroll. Here the data is already in KV. Fetching rows on demand is
   [vlist-remote](/vlist-remote/).

   When not to use it: fewer than about 100 rows, or rows of varying height, use the
   [lists](/lists/) functions. For variable heights, bidirectional loading or chat-style
   append, write a specialized list ([widget-node](/widget-node/)). `vlist3.cljs` itself
   is a ~400 line `defc` and a reasonable starting point."
  (:require
    [clojure.string :as str]
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc << css)]
    [shadow.grove.ui.vlist3 :as vlist3]))

(defonce app (sg/get-runtime :app))

(declare ?window)

;; ## a handle in a ref
;;
;; The list lives in a **handle** the using component owns with `ref`. The handle itself
;; is renderable: render it once, `(render items)` or inside markup, here
;; `[:div#rows items]`.
;;
;; - `(vlist3/prepare opts query-fn & args)` creates it. Opts, query and item fn can
;;   all come later, `(vlist3/prepare)` alone is valid.
;; - A `ref` init only sees `^:stable` args ([refs](/refs/)), so whatever changes is set
;;   from hooks: `set-query!` when the filter text changes, `set-opts!` when the list
;;   becomes active, `set-item-fn!` with a closure over the text. Hooks run before render,
;;   so the list has them by its first render, and each re-runs only when what it uses
;;   changed. Nothing else in the list re-renders.
;; - The handle keeps scroll position and keyboard focus. A list that unmounts and comes
;;   back (a view switch, a `when`) continues where it was, as long as the owning
;;   component stays mounted.
;; - Render a handle in one place only, two lists sharing one handle fight over scroll
;;   and focus.
;;
;; | fn | does |
;; |---|---|
;; | `(prepare)`, `(prepare opts)`, `(prepare opts query-fn & args)` | creates a handle |
;; | `(set-opts! h opts)` | merges opts |
;; | `(set-item-fn! h f)` | replaces `:item-fn` |
;; | `(set-query! h query-fn & args)` | replaces the query |
;; | `(focus-row! h idx)` | moves keyboard focus to `idx`, scrolling it into view when mounted. Doesn't move DOM focus. |
;; | `@h` | the current state: the opts, plus `:focus` with the focused row |
;;
;; That is the whole API. The handle's fields are internal to `vlist3`, don't read them
;; from outside.
;;
;; Try it: click into the list (or tab to it while active), then arrow keys, page up and
;; down, home and end, enter. Type a filter. "focus row 5000" calls `focus-row!`.

(defc ui-rows [text active?]
  (ref items
    (vlist3/prepare
      {:item-height 28
       :key-fn :id
       :select-event {:e ::row-selected!}
       :class (css :border :border-gray-200 :rounded)}))

  (hook (vlist3/set-query! items ?window text))
  (hook (vlist3/set-opts! items {:tab-index (if active? 0 -1)}))

  ;; a closure over text, replaced only when text changes
  (hook
    (vlist3/set-item-fn! items
      (fn [{:keys [label value]} {:keys [idx focus]}]
        (<< [:div {:class [(css :flex :gap-4 :px-2 :items-center {:height "28px"}) (when focus (css :bg-blue-100))]
                   :data-idx idx
                   :data-focus (when focus "true")}
             [:span (css :w-16 :text-gray-500) idx]
             [:span (css :flex-1)
              (if (and (seq text) (str/includes? label text))
                (<< [:mark label])
                label)]
             [:span value]]))))

  (event ::row-selected! [env {:keys [idx item]} e]
    (c/log! "selected idx" idx (:label item))
    (sg/dispatch-up! env {:e ::selected! :item item}))

  (event ::jump! [env ev e]
    (vlist3/focus-row! items 5000))

  (render
    (<< [:div {:class [c/$row (css :mb-2)]}
         [:button {:class c/$btn :on-click ::jump!} "focus row 5000"]]
        ;; the list fills its parent, which needs a bounded height
        [:div#rows (css {:height "300px"}) items])))

;; ## opts
;;
;; ```
;; {:item-height 22                 ;; required: fixed row height in px
;;  :item-fn row                    ;; (fn [entry {:keys [idx focus key]}] renderable)
;;  :key-fn :id                     ;; optional: rows by keyed-seq instead of simple-seq
;;  :select-event {:e ::selected!}  ;; optional: dispatched up on Enter
;;  :tab-index 0                    ;; default -1
;;  :box-class $grid                ;; optional: class of the box holding the rows
;;  :class $list                    ;; optional: class of the scroll container
;;  :content-wrap (fn [rows] ...)   ;; optional: wraps the rendered rows
;;  :more-fn (fn [] ...)            ;; optional: extra row while the result has :more?
;;  :overscan 10                    ;; rows rendered beyond the visible ones
;;  :initial-num 20}                ;; rows queried before the container is measured
;; ```
;;
;; - `:item-height` must be exact: the spacer is `item-count * item-height`, the box sits
;;   at `offset * item-height`. All rows have one height. Without it the list warns in
;;   dev and has nothing to lay out.
;; - `:item-fn` gets the entry and `{:idx absolute-index :focus bool}`, plus `:key` with
;;   `:key-fn`. `:focus` is true for the keyboard-focused row while the list has DOM
;;   focus. A `defc` works as `:item-fn`.
;; - `:key-fn` switches the rows to `keyed-seq` semantics ([lists](/lists/)). Use it when
;;   rows hold state or the same entry moves. Leave it out for positional slices.
;; - `:tab-index`: `0` is tab-focusable, `-1` only by click or script. The active panel
;;   passes `0`, inactive ones `-1`, so only one list grabs keys ([keyboard](/keyboard/)).
;; - Every opt can change after mount. Unknown keys warn in dev.

;; ## the query: window in, slice out
;;
;; The list calls `(sg/query query-fn ...args {:offset n :num m})`, the window appended as
;; the last arg. The query returns:
;;
;; ```
;; {:item-count n   ;; total rows (spacer height)
;;  :offset o       ;; the offset the slice starts at
;;  :slice [...]    ;; the window's entries, a vector
;;  :more? true}    ;; optional: more rows exist beyond :item-count
;; ```
;;
;; Data that needs a full scan to sort or filter isn't windowable by itself. Sort and
;; filter in a query first, then window the resulting vector, as `?window` does by calling
;; `?filtered` directly ([kv-reads](/kv-reads/)).
;;
;; The window has slack: scrolling that stays within half the overscan of its edge
;; doesn't change it, so not every scroll queries again. Scroll handling is throttled to
;; one measure per animation frame, and a `ResizeObserver` measures again when the
;; container size changes.

(defn ?filtered [env text]
  (let [rows (get-in env [:data :rows])]
    (if (str/blank? text)
      rows
      (filterv #(str/includes? (:label %) text) rows))))

(defn ?window [env text {:keys [offset num] :or {offset 0 num 0}}]
  (let [rows (?filtered env text)
        total (count rows)]
    {:item-count total
     :offset offset
     :slice (subvec rows (min offset total) (min total (+ offset num)))}))

;; ## keyboard and selection
;;
;; ArrowUp/Down, PageUp/Down and Home/End move the focused row and scroll it into view.
;; Enter dispatches `:select-event` up with `:idx` and `:item` merged in, if the focused
;; row is rendered. Handle it in the component that owns the list (`::row-selected!`
;; above, which sends `::selected!` on to the page), or let it bubble up to a tx. Enter is
;; the only key that leaves the list. `focus-row!` moves the focus from outside.

;; ## layout
;;
;; The scroll container is `width` / `height` 100% of its parent, so the parent needs a
;; bounded height (a fixed height, or `:flex-1 :overflow-hidden` in a flex column). A
;; container measured at zero height warns "vlist3 container measured zero height" and
;; shows nothing.

;; ## one call: vlist3/render
;;
;; `(vlist3/render opts query-fn & args)` owns a handle internally, for simple cases. Pass
;; a top-level `:item-fn` (a `defn` or `defc`), not a closure built in `render`: that
;; would replace it on every parent render and render all rows again.
;;
;; `:box-class` styles the moving box. Here it makes the box a two-column grid, so each
;; entry renders two cells.

(defn map-row [[k v] {:keys [idx]}]
  (<< [:div (css :px-2 :font-mono :text-sm {:height "24px"}) (str k)]
      [:div (css :px-2 :text-sm {:height "24px"}) (str v)]))

(def $grid
  (css :grid {:grid-template-columns "12rem 1fr"}))

(defn ?map-window [env {:keys [offset num] :or {offset 0 num 0}}]
  (let [entries (get-in env [:data :entries])
        total (count entries)]
    {:item-count total
     :offset offset
     :slice (subvec entries (min offset total) (min total (+ offset num)))}))

(defn map-view []
  (<< [:div#map-rows (css {:height "200px"})
       (vlist3/render {:item-height 24 :item-fn map-row :box-class $grid} ?map-window)]))

;; ## pitfalls
;;
;; - A lazy seq as `:slice`: fragments assert, `vec` it.
;; - An `:item-fn` closure built in `render`: every render replaces it and renders all
;;   rows again. Use a top-level fn, or `set-item-fn!` in a hook.
;; - One handle rendered in two places: both lists share one state.
;; - Remote data: no KV read in the query, `(sg/suspend!)` without a default, or a
;;   fetch that isn't idempotent ([vlist-remote](/vlist-remote/)).

;; ## vlist2 is deprecated
;;
;; `shadow.grove.ui.vlist2` is the previous version, still in the library. Don't use it
;; for new code. It takes `(vlist/render opts read-fn item-fn)` where `read-fn` is
;; `(fn [{:keys [offset num]}] (sg/query ...))`, only `:tab-index` updates after mount,
;; it never sees a `read-fn` suspend, and scrolling is debounced. Porting: `read-fn`
;; becomes the query fn and args of `prepare`, `item-fn` becomes `:item-fn`,
;; `:box-style` a `css` class in `:box-class`, opts that change `set-opts!` in a hook.

;; # page

(defc ui-root []
  (ref state (atom {:text "" :active? true :selected nil}))
  (bind {:keys [text active? selected]} (sg/watch state))

  (event ::input! [env ev e] (swap! state assoc :text (.. e -target -value)))
  (event ::active! [env ev e] (swap! state update :active? not))
  (event ::selected! [env {:keys [item]} e] (swap! state assoc :selected item))

  (render
    (c/page "vlist"
      {"a handle in a ref"
       (<< [:div {:class [c/$row (css :mb-2)]}
            [:input#filter {:class c/$input :placeholder "filter (set-query! from a hook)" :on-input ::input!}]
            [:button {:class c/$btn :on-click ::active!} (if active? "active, tab-index 0" "inactive, tab-index -1")]
            [:span#selected {:class c/$muted} "selected: " (pr-str (:label selected))]]
           (ui-rows text active?))

       "one call: vlist3/render"
       (map-view)})))

(defn init []
  (sg/add-kv-table app :data {}
    {:rows (vec (for [i (range 10000)]
                  {:id i :label (str "row " i) :value (mod (* i 7919) 1000)}))
     :entries (vec (for [i (range 500)]
                     [(keyword (str "key-" i)) (* i i)]))})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
