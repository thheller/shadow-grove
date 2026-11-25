(ns examples.lists
  "Collections render through `sg/simple-seq`, `sg/diff-seq`, `sg/diff-seq-by-value`,
   `sg/keyed-seq` or `sg/each`, never as lazy seqs (fragments assert that in dev). They differ in how
   they match old items to new ones, which decides what happens to a row's DOM when the
   collection changes. All of them mount every item: for hundreds of rows and more, see
   [vlist](/vlist/)."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc << css)]))

(defonce app (sg/get-runtime :app))

;; ## four ways to render a collection
;;
;; The same items rendered four ways. Each row shows its component instance (`#n`,
;; created once per instance) and the index it was last rendered with, and has an
;; uncontrolled input. Type into a few inputs, then change the list.
;;
;; | | items match by | renders over another item's DOM | pick when |
;; |---|---|---|---|
;; | `simple-seq` | position | yes, always | items append or remove at the end, or update in place |
;; | `diff-seq` | `identical?` (strings and numbers by value) | only unmatched ones | items are inserted or removed anywhere, no per-item DOM state |
;; | `diff-seq-by-value` | `=` | only unmatched ones | the same, but the coll is rebuilt with fresh equal maps |
;; | `keyed-seq` | `(key-fn item)`, by `=` | never | items hold focus, input or `:mount` state, or must keep their DOM when moved |
;;
;; What to look for:
;;
;; - **remove first**: `simple-seq` renders item 2 over item 1's row, so the typed text
;;   moves to item 2. The others remove that one row's DOM. The rows after it keep their
;;   DOM and render again only to show their new `idx`.
;; - **reverse**: `simple-seq` keeps every row in place and renders new data into it. The
;;   others move rows, the instance numbers travel with the items.
;; - **reverse as fresh maps**: equal maps that aren't identical, what a query building
;;   new maps on every run returns. `diff-seq` can't match them and renders over in place,
;;   `diff-seq-by-value` still matches.
;; - **idx after a move**: an item whose `idx` changed always renders again, even when its
;;   data is identical, so a displayed `idx` (row numbers, odd/even classes) is always
;;   current. Only an item with the same data at the same `idx` skips rendering. The
;;   exception is `sg/each` with a body not using `idx`, see below.
;; - **duplicate a key**: keys must be unique. `keyed-seq` throws in dev builds, release
;;   builds don't check.

(defonce instance-seq (volatile! 0))

(defc ui-row [item idx]
  ;; created once per instance, so it tells whether the instance stayed with the item
  (ref instance (vswap! instance-seq inc))
  (render
    (<< [:div {:class (css :flex :items-center :gap-2 :py-1 :border-b :border-gray-100 :text-sm)
               :data-label (:label item)}
         [:span (css :w-16 :font-semibold) (:label item)]
         [:span {:class c/$muted :data-idx idx} "idx " idx]
         [:span {:class c/$badge :data-instance instance} "#" instance]
         [:input {:class [c/$input (css :w-20)] :placeholder "type"}]])))

(defn column [title body]
  (<< [:div {:class (css :flex-1 :min-w-0)}
       [:h3 (css :font-semibold :text-sm :mb-1) title]
       body]))

(defn four-lists [items]
  (<< [:div {:class (css :flex :gap-4)}
       [:div#simple {:class (css :flex-1)} (column "simple-seq (by position)" (sg/simple-seq items ui-row))]
       [:div#diff {:class (css :flex-1)} (column "diff-seq (identical?)" (sg/diff-seq items ui-row))]
       [:div#diff-value {:class (css :flex-1)} (column "diff-seq-by-value (=)" (sg/diff-seq-by-value items ui-row))]
       [:div#keyed {:class (css :flex-1)} (column "keyed-seq (:id)" (sg/keyed-seq items :id ui-row))]]))

;; ## render fns
;;
;; ```
;; (sg/simple-seq items ui-row)                    ;; a defc directly
;; (sg/simple-seq items (fn [item idx] (<< ...)))  ;; inline
;; (sg/keyed-seq items :id ui-row)
;; (sg/keyed-seq items :id
;;   (fn [item] (ui-row item other-local)))        ;; closing over a local
;; ```
;;
;; - `simple-seq` and `diff-seq*` call the render fn with `[item idx]`, `keyed-seq` with
;;   `[item idx key]`. A one-arg fn is fine. Return a fragment or a component call.
;; - The render fn can be a `defc` directly, no wrapper fn needed unless it closes over
;;   other locals.
;; - An item renders again only when its data, its `idx` or the render fn changed. An
;;   inline `(fn ...)` is new on every render of the parent, so every item renders again
;;   on every invalidation of the parent. That is still correct, but wasted work. Use a
;;   top-level fn, `(bind row-fn (fn ...))`, or `sg/each` below. The clj-kondo config
;;   shadow-grove exports warns about inline fns (`:shadow.grove/inline-seq-render-fn`).

;; ## each
;;
;; ```
;; (sg/each items [item idx] (<< ...))             ;; like simple-seq
;; (sg/each items :id [item idx key]               ;; like keyed-seq
;;   (ui-row item marked))
;; ```
;;
;; `sg/each` is a macro taking the render fn body inline. It finds the outer locals the
;; body uses (`marked` above, never `item` or names the body binds itself) and compares
;; those with `=` instead of comparing the fn. Same values as last render: only items
;; whose data or `idx` changed render again. If nothing changed at all the parent
;; fragment skips the collection completely.
;;
;; Below, the same rows once via `keyed-seq` with an inline fn and once via `sg/each`.
;; Each row shows how often its render fn ran.
;;
;; - **render parent**: changes a counter only the header shows. The inline column renders
;;   every row again, `sg/each` none.
;; - **rename first**: the inline column renders every row, `sg/each` only the first.
;; - **reverse**: keyed rows move. The inline column renders every row, `sg/each` none:
;;   the body doesn't use `idx`, so a moved row has nothing new to show. Binding `idx`
;;   (`[item idx]`) and using it in the body brings back the render for every row whose
;;   `idx` changed. Not binding it, binding it to `_` or not using it all count as unused.
;;   Without a key-fn `sg/each` matches by position, so `idx` never changes for an item.
;; - **mark next**: `marked` is used by the body, so `sg/each` renders every row too. A
;;   dep that changes on every render gives no gain. A local holding a fn created during
;;   render is such a dep.
;; - A body calling a `defc` is cheaper to render again than one building a fragment: the
;;   component skips when its args are `=`. `sg/each` also skips that call.
;; - Locals are compared by value. `@some-atom` in the body is not a dep, only the atom
;;   itself is.

;; ## picking one
;;
;; - `simple-seq` is the default. Removing the first of 100 rows makes it render the next
;;   item's data into each of the 99 rows after it. `diff-seq` and `keyed-seq` remove that
;;   row's DOM and call the render fn for the 99 with their new `idx`. If a row doesn't
;;   show `idx`, that call changes no DOM: about 8x faster at 100 rows and 10x at 1000.
;;   If it does, every shifted row writes its new `idx`. Keyed `sg/each` with a body not
;;   using `idx` skips those calls too, another 5x at 100 rows and 11x at 1000.
;; - `diff-seq` needs items that keep their identity across updates: unchanged entity maps
;;   from KV tables, strings, numbers. Keywords, UUIDs and vectors match by instance, not
;;   value. A query building fresh maps (`(map #(assoc % ...))`) defeats it, it then costs
;;   what `simple-seq` costs plus a lookup. Use `diff-seq-by-value` there, or return the
;;   stored maps. `=` adds a hash and a deep compare for each non-identical item, avoid it
;;   for large freshly built items. Duplicates are fine in both.
;; - `diff-seq` still renders a new item over an unmatched old one. Per-item DOM state
;;   (focus, input values, inputs filled by a `:mount` effect, animations) can end up on
;;   the wrong item. `keyed-seq` only ever updates, moves or destroys a key's own DOM, so a
;;   new key always remounts.
;; - `keyed-seq` costs about the same as `diff-seq`. It is slower only when most keys
;;   change at once, since it destroys and creates rows instead of rendering over them.
;;   Key by entity id, never by index.
;; - Full reorders (sort toggle, reverse, shuffle) are cheaper with `simple-seq` once
;;   layout is counted: rewriting rows beats moving DOM nodes (a 1000-row reverse is about
;;   10ms with `simple-seq`, 15ms with the others). Only pay for moves when per-row DOM
;;   state must follow the row.

;; ## lists of ids
;;
;; When a query returns ids and each row does its own `kv-lookup`, use
;; `(sg/keyed-seq ids identity ui-row)`: identity as `key-fn` is the "the collection is
;; already keys" case, not a special API. `(sg/diff-seq ids ui-row)` doesn't guarantee a
;; row keeps its id: a new id that lines up with a removed one is rendered over that row,
;; so the row component gets a new id in place (the `^:stable` case in
;; [stable-args](/stable-args/)). [kv-reads](/kv-reads/) shows the pattern.

;; # page

(defn make-items []
  (mapv (fn [i] {:id i :label (str "item " i)}) (range 1 6)))

(defonce render-counts (volatile! {}))

(defn ui-counted-row [column item marked]
  ;; side effect in render, only to show how often this runs
  (let [n (get (vswap! render-counts update [column (:id item)] (fnil inc 0)) [column (:id item)])]
    (<< [:div {:class (css :flex :gap-2 :py-1 :border-b :border-gray-100 :text-sm)
               :data-label (:label item)}
         [:span (css :w-20 :font-semibold) (:label item) (when (= marked (:id item)) " *")]
         [:span {:class c/$muted :data-renders n} "rendered " n "x"]])))

(defc ui-each-demo []
  (ref state (atom {:items (subvec (make-items) 0 3) :ticks 0 :marked 1}))
  (bind {:keys [items ticks marked]} (sg/watch state))

  (event ::tick! [env ev e]
    (swap! state update :ticks inc))
  (event ::rename-first-each! [env ev e]
    (swap! state update-in [:items 0 :label] #(str % "!")))
  (event ::mark-next! [env ev e]
    (swap! state update :marked #(inc (mod % 3))))
  (event ::reverse-each! [env ev e]
    (swap! state update :items #(vec (rseq %))))

  (render
    (<< [:div#each-demo {:class [c/$row (css :mb-4)]}
         [:button {:class c/$btn :on-click ::tick!} "render parent"]
         [:button {:class c/$btn :on-click ::rename-first-each!} "rename first"]
         [:button {:class c/$btn :on-click ::mark-next!} "mark next"]
         [:button {:class c/$btn :on-click ::reverse-each!} "reverse"]
         [:span {:class c/$muted :data-ticks ticks} "parent rendered " ticks]]
        [:div {:class (css :flex :gap-4)}
         [:div#rows-inline {:class (css :flex-1)}
          (column "keyed-seq, inline fn"
            #_{:clj-kondo/ignore [:shadow.grove/inline-seq-render-fn]}
            (sg/keyed-seq items :id (fn [item] (ui-counted-row :inline item marked))))]
         [:div#rows-each {:class (css :flex-1)}
          (column "sg/each"
            (sg/each items :id [item] (ui-counted-row :each item marked)))]])))

(defc ui-root []
  (ref state (atom {:items (make-items) :next-id 6}))
  (bind items (sg/watch state [:items]))

  (event ::prepend! [env ev e]
    (swap! state (fn [{:keys [next-id] :as s}]
                   (-> s
                       (update :items #(into [{:id next-id :label (str "item " next-id)}] %))
                       (update :next-id inc)))))
  (event ::remove-first! [env ev e]
    (swap! state update :items #(vec (rest %))))
  (event ::reverse! [env ev e]
    (swap! state update :items #(vec (reverse %))))
  (event ::shuffle! [env ev e]
    (swap! state update :items #(vec (shuffle %))))
  ;; equal maps, but not identical
  (event ::fresh-reverse! [env ev e]
    (swap! state update :items #(mapv (fn [m] (into {} m)) (reverse %))))
  (event ::rename-first! [env ev e]
    (swap! state update-in [:items 0 :label] #(str % "!")))
  (event ::duplicate-key! [env ev e]
    (swap! state update :items #(conj % (first %))))
  (event ::reset! [env ev e]
    (reset! state {:items (make-items) :next-id 6}))

  (render
    (c/page "lists"
      {"four ways to render a collection"
       (<< [:div {:class [c/$row (css :mb-4)]}
            [:button {:class c/$btn :on-click ::prepend!} "insert at top"]
            [:button {:class c/$btn :on-click ::remove-first!} "remove first"]
            [:button {:class c/$btn :on-click ::reverse!} "reverse"]
            [:button {:class c/$btn :on-click ::shuffle!} "shuffle"]
            [:button {:class c/$btn :on-click ::fresh-reverse!} "reverse as fresh maps"]
            [:button {:class c/$btn :on-click ::rename-first!} "rename first"]
            [:button {:class c/$btn :on-click ::duplicate-key!} "duplicate a key"]
            [:button {:class c/$btn :on-click ::reset!} "reset"]]
           (four-lists items))
       "each"
       (ui-each-demo)})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
