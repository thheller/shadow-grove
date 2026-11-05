(ns examples.markup
  "`<<` looks like hiccup but is a compiler. At macroexpansion time it splits static
   structure from dynamic values and emits minimal DOM create and update code. Static
   parts cost nothing on update.

   ```
   (<< [:div.card
        [:div.card-title title]     ;; a symbol: dynamic, updated when it changes
        [:div.card-body body]
        [:div.card-actions
         [:button \"ok\"]]])          ;; fully static: never touched again
   ```

   - `[:div#main.card ...]`: tag with CSS-style id and classes, like hiccup.
   - The first map, if any, is attributes.
   - Everything else are children: strings, numbers, nil (renders nothing), fragments,
     component calls, or any expression returning one of those.
   - **Vectors are DOM, calls are components**: `[:div (ui-thing x)]`, never
     `[:div [ui-thing x]]`.
   - Only vectors are analyzed. Anything else (`when`, `let`, a function call) is opaque
     code, so markup inside it needs its own `<<` (last chapter).
   - `<<`, `<>`, `fragment` and `html` are the same macro. `svg` starts in the svg
     namespace.

   The `css` syntax itself (aliases, maps, selectors) is shadow-css."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc << css)]))

(defonce app (sg/get-runtime :app))

;; ## css placement
;;
;; A `(css ...)` form as the **first child** is not a child: `<<` lifts it into
;; `:class`, and a map after it is still the attrs map, classes merged. Only a list whose
;; first symbol is exactly `css` (the referred macro, not `sg/css` or `shadow.css/css`)
;; directly after the tag is lifted. Anywhere else it is an ordinary call, and the
;; classname shows up as text (the last line of the demo).
;;
;; `:class` merges everything: `css` forms, defs like `$chip`, vectors, and nils from
;; `(when ...)`. A static `{:class (css ...)}` is skipped on update. Let-bound or
;; conditional classes go in the attrs map. Shared classes are plain defs.

(def $chip
  (css :inline-block :px-2 :py-1 :rounded :bg-gray-100))

(def $chip-on
  (css :bg-green-200))

(defn css-placement [on?]
  (<< [:div#first-child (css :p-2 :border :border-gray-400 {:border-style "dashed"})
       "(css ...) as the first child is lifted into :class"]
      [:div#with-attrs (css :p-2 :mt-2 :border :border-gray-400 {:border-style "dashed"}) {:class $chip :title "attrs still work"}
       "first-child css merged with an attrs :class"]
      [:div#vector-class {:class [$chip (when on? $chip-on) nil]} "{:class [$chip (when on? $chip-on)]}"]
      [:div#css-not-first [:span "css in another position is just a call, its classname shows as text: "]
       (css :p-2)]))

;; ## styles
;;
;; `:style` takes a map or a CSS string. A namespaced `:style/<property>` sets one
;; property through `setProperty`, the name is the keyword's name (`:style/font-weight`).
;; Prefer `css` for static styling. Numbers in `css` maps are not pixels (shadow-css);
;; `{:width "200px"}` in a `css` map is the escape hatch for a one-off length.

(defn styles [width]
  (<< [:div#style-map {:style {:color "rebeccapurple" :font-weight "bold"}} ":style as a map"]
      [:div#style-string {:style (str "width: " width "px; background: #fde68a;")} ":style as a string"]
      [:div#style-prop {:style/width (str width "px") :style/background "#bfdbfe"} ":style/width sets one property"]))

;; ## attributes and props
;;
;; - `:for`, `:type`, `:disabled`, `:placeholder`, `:role` and friends pass through as
;;   DOM attributes or props.
;; - Unknown kebab-case keys become camelCase DOM props: `:tab-index` is `tabIndex`,
;;   `:read-only` is `readOnly`. `:data-*` and `:aria-*` are set as attributes. SVG
;;   elements use `setAttribute`.
;; - `:value` plus an `:on-input` writing it back makes a controlled input. Without the
;;   handler the field shows a fixed string. Editors often leave `:value` out and fill the
;;   node from a `:mount` effect ([forms](/forms/)). Don't set `:value` "just in case".
;; - `:dom/ref` takes a volatile, `(ref node)` in a `defc`, and fills it with the node
;;   after mount ([refs](/refs/)).
;; - `:key` is not a thing on elements. List identity comes from `keyed-seq`
;;   ([lists](/lists/)).
;; - `[:a {:href "/page"}]` is an in-app link once `history/init!` runs, `:ui/href` with a
;;   path prefix ([routing](/routing/)). `:on-*` values: [dom-events](/dom-events/).
;;   Namespaced attributes like `::keyboard/listen` have registered handlers
;;   ([keyboard](/keyboard/), [custom-attributes](/custom-attributes/)).

(defn attributes [on?]
  (<< [:label {:for "ro-input" :class c/$muted} "read-only input, :tab-index and :read-only become tabIndex/readOnly"]
      [:input#ro-input {:class c/$input :value "can't edit" :read-only true :tab-index -1}]
      [:div#data-aria {:data-state (if on? "on" "off") :aria-label "state holder" :role "status"}
       "data-* and aria-* are set as attributes"]))

;; ## svg
;;
;; `[:svg ...]` inside `<<` is rewritten to the svg element namespace, icons don't need
;; the `svg` macro. Once inside svg, nested fragments stay svg, including ones built
;; under `when`. `foreignObject` back to HTML is not handled. Building svg tags as data
;; and passing them through a helper that doesn't re-enter `<<` creates them as HTML.

(defn icon-check []
  (<< [:svg {:width 16 :height 16 :viewBox "0 0 16 16" :aria-label "check icon"}
       [:path {:d "M2 8 L6 12 L14 4" :stroke "currentColor" :stroke-width 2 :fill "none"}]]))

;; ## re-entering << in code
;;
;; `when`, `if`, `case`, `let` and helper functions are opaque to `<<`. If their body
;; contains vectors, wrap it in `<<` again, otherwise the vector is not markup.
;;
;; - `nil` children are fine and render nothing. `false` is not special, use `when` so the
;;   branch is nil.
;; - Never put lazy seqs in fragments. Use `simple-seq` / `keyed-seq`
;;   ([lists](/lists/)), or `vec` first. Fragments assert this in dev.

(defn branches [on? items]
  (<< (when on?
        (<< [:div#when-branch {:class $chip} "rendered by a when branch"]))
      (if (seq items)
        (<< [:ul (sg/simple-seq items (fn [x] (<< [:li x])))])
        (<< [:div "empty"]))
      nil
      [:div {:class c/$muted} "nil renders nothing"]))

;; # page

(defc ui-root []
  (ref state (atom {:on? false :items ["a" "b"] :width 120}))
  (bind {:keys [on? items width]} (sg/watch state))

  (event ::toggle! [env ev e] (swap! state update :on? not))
  (event ::wider! [env ev e] (swap! state update :width + 40))

  (render
    (c/page "markup"
      {"css placement"
       (<< (css-placement on?)
           [:button {:class [c/$btn (css :mt-2)] :on-click ::toggle!} "toggle on?"])

       "styles"
       (<< (styles width)
           [:button {:class [c/$btn (css :mt-2)] :on-click ::wider!} "wider"])

       "attributes and props"
       (attributes on?)

       "svg"
       [:div {:class c/$row} (icon-check) "an icon fn returning [:svg ...] in <<"]

       "re-entering << in code"
       (<< (branches on? items)
           [:button {:class [c/$btn (css :mt-2)] :on-click ::toggle!} "toggle on?"])})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
