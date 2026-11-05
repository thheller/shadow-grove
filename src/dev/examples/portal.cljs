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
;; The dialog renders conditionally, `(when open? (sg/portal ...))`. Only the owner cares
;; whether it is open, so the flag is an atom in a `ref` ([local-state](/local-state/)).
;; Open it and look at the DOM: the overlay is a child of `<body>`, not of this page.
;;
;; - `n + 1` runs a tx, and the read inside the portal updates like any other.
;; - "close" is a `::close!` map. The component that renders the portal has a slot for
;;   it, and the event finds it although the button isn't inside its DOM. The slot resets
;;   the atom, no tx runs.

(def $overlay
  (css :fixed :inset-0 :flex :items-center :justify-center :z-50 {:background "rgba(0,0,0,0.3)"}))

(def $dialog
  (css :bg-white :rounded-lg :shadow-xl :p-4 {:width "24rem"}))

(deftx bump! []
  [tx-env ev e]
  (update-in tx-env [:ui :n] inc))

(defc ui-dialog-body []
  (bind n (sg/kv-lookup :ui :n))
  (render
    (<< [:p "n from KV, read inside the portal: " [:b#portal-n n]]
        [:div {:class [c/$row (css :mt-4)]}
         [:button {:class c/$btn :on-click (bump!)} "n + 1"]
         [:button {:class [c/$btn c/$btn-primary] :on-click ::close!} "close"]])))

(defc ui-with-dialog []
  (ref open-ref (atom false))
  (bind open? (sg/watch open-ref))

  (event ::open! [env ev e]
    (reset! open-ref true))

  (event ::close! [env ev e]
    (c/log! "owner slot handled ::close! from inside the portal, no tx")
    (reset! open-ref false))

  (render
    (<< [:button {:class c/$btn :on-click ::open!} "open dialog"]
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
  (sg/add-kv-table app :ui {} {:n 0})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
