(ns examples.hash-routing
  "`history/init!` with `:use-fragment true` keeps the route in `location.hash`
   (`/hash-routing/#/two`). Useful when the server can't serve the app for every path.
   Everything else works as in [routing](/routing/): `:ui/route!` gets the token after
   the `#`, components render the data it wrote."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.history :as history]))

(defonce app (sg/get-runtime :app))

;; ## the route in the hash
;;
;; - The token is read from the hash, `:start-token` is written into it.
;; - `:ui/href "/two"` writes `href="#/two"`. There is no click interception, a hash link
;;   is a normal browser navigation, and history listens to `hashchange` (and `popstate`
;;   for back and forward).
;; - `:ui/redirect!` is meant to change the hash too. Known issue: currently it pushes
;;   the token as a path, the URL becomes `/two` instead of `/hash-routing/#/two`. The page
;;   shows the right route, but a reload lands somewhere else. Navigate with links, or set
;;   `js/location.hash` from an fx, until that is fixed.

(defn route! [tx-env {:keys [token tokens]}]
  (c/log! "route!" token)
  (case (first tokens)
    "one" (assoc-in tx-env [:ui :page] :one)
    "two" (assoc-in tx-env [:ui :page] :two)
    (sg/queue-fx tx-env :ui/redirect! {:token "/one"})))

(deftx go-two! []
  [tx-env ev e]
  (sg/queue-fx tx-env :ui/redirect! {:token "/two"}))

(defc ui-app []
  (bind page (sg/kv-lookup :ui :page))
  (render
    (<< [:nav {:class [c/$row (css :mb-4)]}
         [:a#link-one {:class c/$btn :ui/href "/one"} "one"]
         [:a#link-two {:class c/$btn :ui/href "/two"} "two"]
         [:button {:class c/$btn :on-click (go-two!)} ":ui/redirect! to /two"]]
        [:p#page "page: " (pr-str page)])))

(defn start! []
  (sg/add-kv-table app :ui {} {})
  (sg/reg-event app :ui/route! route!)
  (history/init! app {:use-fragment true :start-token "/one"}))

;; # page

(defc ui-root []
  (render
    (c/page "hash routing"
      {"the route in the hash" (ui-app)})))

(defn init []
  (start!)
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
