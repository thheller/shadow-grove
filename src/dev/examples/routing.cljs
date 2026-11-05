(ns examples.routing
  "Routing in grove only turns a URI string into data in KV. That data then decides what
   renders. There is no router in the component tree:

   ```
   browser URL /routing/item/2/edit
     -> history dispatches {:e :ui/route! :token \"/item/2/edit\" :tokens [\"item\" \"2\" \"edit\"]}
     -> the :ui/route! handler writes page data to [:ui :current-page] (and prepares drafts, ...)
     -> components reading [:ui :current-page] render that data
   ```

   Components don't know URLs exist. The route handler is a normal transaction with all
   tables, so turning a string into data can go beyond parsing: look entities up, check
   they exist, seed a draft, clear stale UI state, redirect. Most routing libraries are
   overkill, write helper functions if needed.

   This example is served at `/routing`, the URL bar shows what happens. Variants:
   [hash-routing](/hash-routing/), and a session gate in [auth-gate](/auth-gate/)."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.history :as history]))

(defonce app (sg/get-runtime :app))

;; the chapters are in reading order, not definition order
(declare route! ui-tabs ui-items ui-item ui-item-edit ui-about delete-ask! delete!)

;; ## the app
;;
;; Click around: the items, an item, edit, delete, a missing item, an unknown URL, About.
;; Use the browser's back button. Reload on any page.

;; ## wiring history/init!
;;
;; `history/init!` bridges the browser's location and history to `:ui/route!`
;; transactions. Order matters:
;;
;; - Register tables and `:ui/route!` first. `init!` **routes immediately**: it runs the
;;   first `:ui/route!` tx before it returns, so anything the handler reads must already
;;   be there.
;; - Then the first render. The click listener, `popstate` (back and forward) and the
;;   `:start-token` rewrite are installed through `::sg/env-init`, which runs when a root
;;   renders for the first time. Calling `init!` after the first render leaves them out:
;;   links silently reload the page.
;; - If something the route depends on arrives later (a session), start history only once
;;   it is there ([auth-gate](/auth-gate/)).
;;
;; Options:
;;
;; - `:start-token` (default `"/"`): when the app is opened at `/`, it routes there and
;;   replaces the URL.
;; - `:path-prefix` (default `""`): where the app is mounted, here `"/routing"`. It is
;;   stripped when reading the location and added when pushing state and writing
;;   `:ui/href`. Tokens are the path after the prefix (`"/items"`, not
;;   `"/routing/items"`). A path outside the prefix throws "path did not match path
;;   prefix".
;; - `:use-fragment` (default `false`): read the route from `location.hash` and listen to
;;   `hashchange` instead ([hash-routing](/hash-routing/)).
;; - `:root-el` (default `document.body`): where the delegated click listener goes. Pass
;;   the app's mount node, so only clicks inside the app are intercepted.
;;
;; Prefix and tokens start with `/` and don't end with one.

(defn start! []
  (sg/add-kv-table app :ui {} {})
  (sg/add-kv-table app :item {:primary-key :id}
    {"1" {:id "1" :label "first item"}
     "2" {:id "2" :label "second item"}
     "3" {:id "3" :label "third item"}})
  (sg/reg-event app :ui/route! route!)

  (history/init! app {:start-token "/items"
                      :path-prefix "/routing"
                      :root-el (js/document.getElementById "root")}))

;; ## turning the route into data
;;
;; The handler gets `tx-env` and `{:keys [token tokens]}`: `token` is the path
;; (`"/item/2"`), `tokens` its segments (`["item" "2"]`). Patterns worth copying:
;;
;; - **Central cleanup**: reset ephemeral `:ui` state (confirm dialogs, open editors,
;;   per-page panels) at the top, so it never survives navigation. New dialog keys get
;;   reset here too. Ask to delete an item, navigate away and back: the question is gone.
;; - **Validate against KV, redirect on miss**: if the entity the URL names isn't there,
;;   queue `:ui/redirect!` to a safe page instead of rendering a page whose lookup
;;   returns nil. Unknown URLs redirect too.
;; - **Enrich**: the page map can carry derived data (`:tab` for both item pages), and
;;   the edit route seeds the draft the edit page reads.
;;
;; The keys inside the page map are yours: this app uses `:id` and `:tab`, another might
;; use `:page` and whatever else its pages need. A `:ui/route!` that refuses to route
;; until there is a session is in [auth-gate](/auth-gate/).

(defn route! [tx-env {:keys [token tokens] :as ev}]
  (c/log! "route!" token (pr-str tokens))
  (let [tx-env (assoc-in tx-env [:ui :confirm] nil)
        [main id action] tokens
        set-page (fn [env page] (assoc-in env [:ui :current-page] page))
        redirect (fn [env token] (sg/queue-fx env :ui/redirect! {:token token}))]

    (case main
      "items"
      (set-page tx-env {:id :items :tab :items})

      "item"
      (let [item (get-in tx-env [:item id])]
        (cond
          (nil? item)
          (redirect tx-env "/items")

          (nil? action)
          (set-page tx-env {:id :item :tab :items :item-id id})

          (= "edit" action)
          (-> tx-env
              (assoc-in [:ui :draft] (:label item))
              (set-page {:id :item-edit :tab :items :item-id id}))

          :else
          (redirect tx-env "/items")))

      "about"
      (set-page tx-env {:id :about :tab :about})

      (redirect tx-env "/items"))))

;; ## rendering from route data
;;
;; The app reads `[:ui :current-page]` like any other KV entry and branches with plain
;; code. Page components take the entity id as an arg, marked `^:stable`, so
;; `/item/1` -> `/item/2` remounts the page instead of updating it in place
;; ([stable-args](/stable-args/)). "other item" shows the remount in the log.
;;
;; `ui-tabs` only gets `(:tab page)`, which is `:items` for the list and both item pages,
;; so moving between them doesn't render the tabs (watch its `×`).
;;
;; The `case` sits directly in a `sg/suspense`, so a page that loads data keeps the
;; previous page up until it is ready ([suspense](/suspense/)). The pages here don't
;; suspend, they swap in at once. The tabs go beside the boundary, they stay put.

(defc ui-app []
  (bind page (sg/kv-lookup :ui :current-page))
  (render
    (<< (ui-tabs (:tab page))
        [:main
         (sg/suspense {:fallback "Loading ..."}
           (case (:id page)
             :items (ui-items)
             :item (ui-item (:item-id page))
             :item-edit (ui-item-edit (:item-id page))
             :about (ui-about)
             nil))])))

(defc ui-tabs [tab]
  (render
    (<< [:nav#tabs {:class [c/$row (css :mb-4)]}
         [:a {:class [c/$btn (when (= tab :items) c/$btn-primary)] :ui/href "/items"} "Items"]
         [:a {:class [c/$btn (when (= tab :about) c/$btn-primary)] :ui/href "/about"} "About"]
         (c/render-count ::tabs)])))

(defc ui-item [^:stable id]
  (bind item (sg/kv-lookup :item id))
  (bind confirm (sg/kv-lookup :ui :confirm))
  (effect :mount [env] (c/log! "ui-item mounted for" id))
  (render
    (<< [:h3#item-title (css :font-semibold :mb-2) (:label item)]
        [:div {:class c/$row}
         [:a {:class c/$btn :ui/href (str "/item/" id "/edit")} "edit"]
         [:button {:class [c/$btn c/$btn-danger] :on-click (delete-ask!)} "delete"]
         [:a {:class c/$link :ui/href (str "/item/" (if (= id "1") "2" "1"))} "other item"]]
        (when confirm
          (<< [:div {:class [c/$row (css :mt-2)]}
               "really delete?"
               [:button {:class [c/$btn c/$btn-danger] :on-click (delete! id)} "yes, delete"]])))))

;; ## links
;;
;; `history/init!` intercepts plain left clicks on `<a>` elements whose `href` starts with
;; the path prefix, pushes the new URL and runs `:ui/route!`. Ordinary links need no
;; `:on-click`.
;;
;; - With a `:path-prefix` (or `:use-fragment`), write in-app links with `:ui/href`: it
;;   takes the path without the prefix (starting with `/`) and writes the full `href`. A
;;   plain `:href` must spell out the prefix, About shows one.
;; - Clicks with a modifier key, and anchors with a `target` attribute, are left to the
;;   browser. A real `:href` plus `:target "_blank"` leaves the app on purpose.

(defn ?items [env]
  (->> (vals (:item env)) (sort-by :id) (vec)))

(defc ui-items []
  (bind items (sg/query ?items))
  (render
    (<< [:h3 (css :font-semibold :mb-2) "Items"]
        [:ul
         (sg/simple-seq items
           (fn [{:keys [id label]}]
             (<< [:li [:a {:class c/$link :ui/href (str "/item/" id)} label]])))
         [:li [:a {:class c/$link :ui/href "/item/999"} "an item that doesn't exist"]]
         [:li [:a {:class c/$link :ui/href "/nowhere"} "an unknown URL"]]])))

(defn ui-about []
  (<< [:h3 (css :font-semibold :mb-2) "About"]
      [:p "A plain :href that starts with the prefix is intercepted too: "
       [:a#plain-href {:class c/$link :href "/routing/items"} "/routing/items"]]
      [:p "With a :target it is left to the browser: "
       [:a#blank-href {:class c/$link :href "/routing/items" :target "_blank"} "new tab"]]))

;; ## navigating from a transaction
;;
;; Queue the `:ui/redirect!` fx with `{:token "/path"}` (optionally `:title`). The token
;; is without the prefix. It pushes the URL and runs a follow-up `:ui/route!` tx. Use it
;; after creating or deleting an entity, after saving, or when `:ui/route!` rejects a
;; URL.

(deftx delete-ask! []
  [tx-env ev e]
  (assoc-in tx-env [:ui :confirm] true))

(deftx delete! [id]
  [tx-env ev e]
  (-> tx-env
      (update :item dissoc id)
      (sg/queue-fx :ui/redirect! {:token "/items"})))

(deftx draft-input! []
  [tx-env ev e]
  (assoc-in tx-env [:ui :draft] (.. e -target -value)))

(deftx save! [id]
  {:e/prevent-default true}
  [tx-env ev e]
  (-> tx-env
      (assoc-in [:item id :label] (get-in tx-env [:ui :draft]))
      (sg/queue-fx :ui/redirect! {:token (str "/item/" id)})))

(defc ui-item-edit [^:stable id]
  (bind draft (sg/kv-lookup :ui :draft))
  (render
    (<< [:form {:class c/$row :on-submit (save! id)}
         [:input#draft {:class c/$input :value draft :on-input (draft-input!)}]
         [:button {:class [c/$btn c/$btn-primary] :type "submit"} "save"]
         [:a {:class c/$btn :ui/href (str "/item/" id)} "cancel"]])))

;; # page

(defc ui-root []
  (render
    (c/page "routing"
      {"the app" (ui-app)})))

(defn init []
  (start!)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
