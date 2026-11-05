(ns examples.portal
  "`sg/portal` renders a subtree into another DOM node, `document.body` by default. It
   stays part of the component tree: event slots of the components around it and KV reads
   inside it work as anywhere else. Use it for dialogs, overlays and drawers that must
   escape the layout (overflow, stacking) of where they are declared."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; ## a dialog in document.body
;;
;; The dialog renders conditionally, `(when open? (sg/portal ...))`, so opening and
;; closing is just a flag. Open it and look at the DOM: the overlay is a child of
;; `<body>`, not of this page.
;;
;; - `n + 1` runs a tx, and the read inside the portal updates like any other.
;; - "ping the owner" is a `::local-ping!` map. The component that renders the portal has
;;   a slot for it, and the event finds it although the button isn't inside its DOM.
;; - "close" transacts the flag off. A flag that only the owner cares about could just as
;;   well be an atom in a `ref`, closed from a slot without a tx
;;   ([local-state](/local-state/)).

(def $overlay
  (css :fixed :inset-0 :flex :items-center :justify-center :z-50 {:background "rgba(0,0,0,0.3)"}))

(def $dialog
  (css :bg-white :rounded-lg :shadow-xl :p-4 {:width "24rem"}))

(deftx dialog-open! []
  [tx-env ev e]
  (assoc-in tx-env [:ui :dialog?] true))

(deftx dialog-close! []
  [tx-env ev e]
  (assoc-in tx-env [:ui :dialog?] false))

(deftx bump! []
  [tx-env ev e]
  (update-in tx-env [:ui :n] inc))

(defc ui-dialog-body []
  (bind n (sg/kv-lookup :ui :n))
  (render
    (<< [:p "n from KV, read inside the portal: " [:b#portal-n n]]
        [:div {:class [c/$row (css :mt-4)]}
         [:button {:class c/$btn :on-click (bump!)} "n + 1"]
         [:button {:class c/$btn :on-click ::local-ping!} "ping the owner"]
         [:button {:class [c/$btn c/$btn-primary] :on-click (dialog-close!)} "close"]])))

(defc ui-with-dialog []
  (bind open? (sg/kv-lookup :ui :dialog?))

  (event ::local-ping! [env ev e]
    (c/log! "owner slot handled ::local-ping! from inside the portal"))

  (render
    (<< [:button {:class c/$btn :on-click (dialog-open!)} "open dialog"]
        (when open?
          (sg/portal
            (<< [:div#overlay {:class $overlay}
                 [:div {:class $dialog :role "dialog"} (ui-dialog-body)]]))))))

;; ## a portal into a given node
;;
;; `(sg/portal node body)` renders into any element. `#side-target` is a plain element
;; appended to `<body>` at init. Portal content is removed from its node when the portal
;; unmounts.

(defonce side-el
  (doto (js/document.createElement "div")
    (.setAttribute "id" "side-target")))

(defc ui-into-node []
  (bind n (sg/kv-lookup :ui :n))
  (render
    (sg/portal side-el (<< [:div {:class c/$badge} "rendered into #side-target, n = " n]))))

;; # page

(defc ui-root []
  (bind n (sg/kv-lookup :ui :n))
  (render
    (c/page "portal"
      {"a dialog in document.body"
       (<< (ui-with-dialog)
           [:p {:class c/$muted} "n = " n])

       "a portal into a given node"
       (ui-into-node)})))

(defn init []
  (.append js/document.body side-el)
  (sg/add-kv-table app :ui {} {:dialog? false :n 0})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
