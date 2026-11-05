(ns examples.stable-args
  "When a parent passes new args to the same component, the instance updates in place by
   default. `^:stable` args and `:supports?` decide when it remounts instead: a new
   instance with new refs, `:mount` effects firing again, and the old one's cleanups run."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; ## an editor filled by a :mount effect
;;
;; An editor that owns its input writes the initial text from a `:mount` effect and
;; leaves the DOM alone after that (why: [forms](/forms/)). `:mount` runs once per
;; instance.
;;
;; Pick another item. The first editor updates in place: its binds run again with the
;; new id, but `:mount` doesn't, so the input keeps the previous item's text. The second
;; takes the id as `^:stable`. A new value for a stable arg remounts the component
;; instead of updating it, so `:mount` runs again for the new item.
;;
;; Use `^:stable` for the identity a page or editor displays: the entity id. It matters
;; when one position (a page `case` calling `(ui-editor id)`) gets a new id. `keyed-seq`
;; already remounts on a key change ([lists](/lists/)).

(defc ui-editor-updates [id]
  (ref input)
  (bind item (sg/kv-lookup :item id))
  (effect :mount [env]
    (c/log! "updating editor: mounted with id" id)
    (set! @input -value (:text item)))
  (render
    (<< [:div {:class c/$row}
         [:input {:dom/ref input :class c/$input :aria-label "updating editor"}]
         [:span {:class c/$muted} "showing id " id]])))

;; the same, with the id marked ^:stable
(defc ui-editor-remounts [^:stable id]
  (ref input)
  (bind item (sg/kv-lookup :item id))
  (effect :mount [env]
    (c/log! "stable editor: mounted with id" id)
    (set! @input -value (:text item)))
  (render
    (<< [:div {:class c/$row}
         [:input {:dom/ref input :class c/$input :aria-label "stable editor"}]
         [:span {:class c/$muted} "showing id " id]])))

;; ## ^:stable inside a destructure
;;
;; `^:stable` works on any symbol the arg vector binds. Here only `id` is identity:
;; a new label updates the row, a new id remounts it (the log shows unmount and mount).
;; `^:stable` on a whole destructure form, `^:stable {:keys [a b]}`, makes every symbol
;; it binds stable. Other shapes:
;;
;; ```
;; (defc ui-article [^:stable id] ...)
;; (defc ui-panel [^:stable oid panel-idx active?] ...) ;; only oid is identity
;; ```

(defc ui-row [{:keys [^:stable id label]}]
  (effect :mount [env]
    (c/log! "row: mounted for id" id)
    (fn [] (c/log! "row: unmounted for id" id)))
  (render
    (<< [:div {:class c/$row} "row " id ": " label (c/render-count ::row)])))

;; ## :supports? in the opts map
;;
;; A fully custom "can this update in place" check. It gets the old and new args
;; vectors **after** destructuring: one entry per bound symbol, in the order the
;; compiler binds them. This one keeps the instance while the kind stays the same.

(defc ui-custom
  {:supports? (fn [[old-kind] [new-kind]] (= old-kind new-kind))}
  [kind value]
  (effect :mount [env]
    (c/log! "custom: mounted as" kind)
    (fn [] (c/log! "custom: unmounted as" kind)))
  (render
    (<< [:div {:class c/$row} "kind " (name kind) ", value " value (c/render-count ::custom)])))

;; # page

(deftx select! [id]
  [tx-env ev e]
  (assoc-in tx-env [:ui :selected] id))

(deftx relabel! []
  [tx-env ev e]
  (update-in tx-env [:ui :label] #(if (= % "first label") "second label" "first label")))

(deftx custom! [k f]
  [tx-env ev e]
  (update-in tx-env [:ui k] f))

(defn flip-kind [k]
  (if (= k :circle) :square :circle))

(defc ui-root []
  (bind {:keys [selected label row-id kind value]} (sg/kv-lookup :ui))

  (render
    (c/page "stable args"
      {"an editor filled by a :mount effect"
       (<< [:div {:class [c/$row (css :mb-2)]}
            [:button {:class c/$btn :on-click (select! 1)} "item 1"]
            [:button {:class c/$btn :on-click (select! 2)} "item 2"]
            [:button {:class c/$btn :on-click (select! 3)} "item 3"]]
           (ui-editor-updates selected)
           (ui-editor-remounts selected))

       "^:stable inside a destructure"
       (<< (ui-row {:id row-id :label label})
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click (relabel!)} "change label"]
            [:button {:class c/$btn :on-click (custom! :row-id inc)} "change id"]])

       ":supports? in the opts map"
       (<< (ui-custom kind value)
           [:div {:class [c/$row (css :mt-2)]}
            [:button {:class c/$btn :on-click (custom! :value inc)} "change value"]
            [:button {:class c/$btn :on-click (custom! :kind flip-kind)} "change kind"]])})))

(defn init []
  (sg/add-kv-table app :item {:primary-key :id}
    {1 {:id 1 :text "text of item 1"}
     2 {:id 2 :text "text of item 2"}
     3 {:id 3 :text "text of item 3"}})
  (sg/add-kv-table app :ui {}
    {:selected 1 :label "first label" :row-id 1 :kind :circle :value 1})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
