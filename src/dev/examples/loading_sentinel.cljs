(ns examples.loading-sentinel
  "`nil` means absent: a missing key and `(kv-lookup ...)` on it both give nil. When the
   UI must also represent \"not loaded yet\", store a value that isn't nil, `:db/loading`,
   and suspend on it. A missing value and a loaded-empty one are different problems,
   don't overload `nil` for both.

   This is for data that arrives from outside any component: page data embedded by the
   server, a session check, a push message. Data a screen fetches for itself is a pull,
   which suspends on its own ([http-pull](/http-pull/))."
  (:require
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

(defc ui-login-form [where]
  (effect :mount [env] (c/log! where "showed the login form"))
  (render (<< [:div {:class (css :p-2 :bg-red-50 :rounded) :data-login where} "login form"])))

(defc ui-welcome [where user]
  (render (<< [:div {:class (css :p-2 :bg-green-50 :rounded) :data-welcome where} "welcome, " (:name user)])))

;; ## :db/loading and suspend
;;
;; The table starts with `{:current-user :db/loading}`. The session arrives 1s after
;; load, from a timer standing in for embedded data or a push message, through
;; `sg/run-tx!` ([external-events](/external-events/)).
;;
;; `:db/loading` suspends, nil is logged out, a map is the user. Suspending avoids a flash
;; of the login form, and the `sg/suspense` above turns it into "Loading ..." instead of
;; a blank space ([suspense](/suspense/)). "reload session" sets the sentinel again and
;; delivers the session a second later.
;;
;; An app that waits for a session usually also defers `history/init!` until it is there
;; ([auth-gate](/auth-gate/)).

(defn maybe-suspend [res]
  (if (= :db/loading res)
    (do (sg/suspend!) nil)
    res))

(defc ui-with-sentinel []
  (bind user (maybe-suspend (sg/kv-lookup :ui :current-user)))
  (render
    (if user
      (ui-welcome "sentinel" user)
      (ui-login-form "sentinel"))))

(defn with-sentinel []
  (sg/suspense {:fallback (<< [:div {:data-fallback "sentinel"} "Loading ..."]) :timeout 0}
    (ui-with-sentinel)))

;; ## nil for both
;;
;; The same component with nil meaning both "loading" and "logged out": it shows the
;; login form until the session arrives (the log says when it showed).

(defc ui-nil-only []
  (bind user (sg/kv-lookup :ui :nil-user))
  (render
    (if user
      (ui-welcome "nil-only" user)
      (ui-login-form "nil-only"))))

;; # page

(deftx session-arrived! [user]
  [tx-env ev e]
  (c/log! "session arrived:" (pr-str user))
  (-> tx-env
      (assoc-in [:ui :current-user] user)
      (assoc-in [:ui :nil-user] user)))

(deftx log-out! []
  [tx-env ev e]
  (-> tx-env
      (assoc-in [:ui :current-user] nil)
      (assoc-in [:ui :nil-user] nil)))

(deftx reload-session! []
  [tx-env ev e]
  (-> tx-env
      (assoc-in [:ui :current-user] :db/loading)
      (assoc-in [:ui :nil-user] nil)
      (sg/queue-fx ::deliver-later 1000)))

(defn controls []
  (<< [:div {:class [c/$row (css :mb-3)]}
       [:button {:class c/$btn :on-click (reload-session!)} "reload session"]
       [:button {:class c/$btn :on-click (log-out!)} "log out"]]))

(defc ui-root []
  (render
    (c/page "loading sentinel"
      {":db/loading and suspend"
       (<< (controls) (with-sentinel))

       "nil for both"
       (<< (controls) (ui-nil-only))})))

(defn deliver-session! []
  (sg/run-tx! app (session-arrived! {:name "Ada"})))

(defn init []
  (sg/add-kv-table app :ui {} {:current-user :db/loading :nil-user nil})
  (sg/reg-fx app ::deliver-later
    (fn [fx-env ms]
      (js/setTimeout deliver-session! ms)))
  (c/render! app (ui-root))
  (js/setTimeout deliver-session! 1000))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
