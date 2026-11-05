(ns examples.auth-gate
  "Routing behind a session. The session is fetched first, `history/init!` and the first
   render wait for it. `:ui/route!` sends users without a session to `/login` and
   remembers where they wanted to go. Log in with demo / demo.

   Builds on [routing](/routing/), [http-fx](/http-fx/) for the requests and the
   `:db/loading` sentinel from [loading-sentinel](/loading-sentinel/)."
  (:require
    [clojure.string :as str]
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]
    [shadow.grove.edn :as edn]
    [shadow.grove.history :as history]
    [shadow.grove.http-fx :as http-fx]))

(defonce app (sg/get-runtime :app))

(declare ui-root route! request-error! secret-loaded!)

;; ## the app
;;
;; Open it at `/auth-gate/secret` without a session: you land on the login form, which
;; knows you wanted `/secret`. After logging in you are sent there.

;; ## session first
;;
;; `:ui/route!` reads the session, so the order at init is:
;;
;; 1. tables (with `{:current-user :db/loading}`), `:ui/route!`, the transport
;; 2. request the session
;; 3. once the session tx committed: `history/init!` (which routes immediately), then the
;;    first render.
;;
;; Starting history before the session is there routes a logged-in user through the
;; logged-out branch. Rendering before `history/init!` leaves out its click listener
;; ([routing](/routing/)). Here the session's success tx queues a `::start` fx, which
;; runs after the commit.
;;
;; `:current-user` is a map (logged in), nil (logged out) or `:db/loading` (not known
;; yet). nil must not mean "still loading", or the gate redirects to `/login` before the
;; session arrived.

(defonce started? (atom false))

(deftx session-loaded! []
  [tx-env {:keys [result]} e]
  (-> tx-env
      (assoc-in [:ui :current-user] (:user result))
      (sg/queue-fx ::start true)))

(defn boot! []
  (sg/add-kv-table app :ui {} {:current-user :db/loading})
  (sg/reg-event app :ui/route! route!)
  (edn/init! app {})
  (sg/reg-fx app :api
    (http-fx/make-handler
      {:base-url "/auth-gate/api"
       :request-format :edn
       :on-error (request-error!)}))

  (sg/reg-fx app ::start
    (fn [fx-env _]
      (when (compare-and-set! started? false true)
        (history/init! app {:path-prefix "/auth-gate" :start-token "/home"})
        (c/render! app (ui-root)))))

  ;; a tx can also be a plain fn of tx-env
  (sg/run-tx! app (fn [tx-env] (sg/queue-fx tx-env :api {:request "/session" :on-success (session-loaded!)}))))

;; ## the gate in :ui/route!
;;
;; Without a session the route handler shows the login page and remembers the token. It
;; redirects to `/login` so the URL says what is shown. It doesn't render a half-routed
;; app, and it doesn't drop the URL the user asked for. Logged-in users don't need
;; `/login` and go home.
;;
;; The route handler also loads the data a page needs. `/secret` sets `[:ui :secret]` to
;; `:db/loading` and queues the request ([http-fx](/http-fx/)). `ui-secret` suspends on
;; the sentinel ([loading-sentinel](/loading-sentinel/)), and the `sg/suspense` around
;; the page `case` keeps the previous page up until the secret is there
;; ([suspense](/suspense/)).

(defn route! [tx-env {:keys [token tokens]}]
  (let [user (get-in tx-env [:ui :current-user])]
    (if-not (map? user)
      (cond-> (assoc-in tx-env [:ui :current-page] {:id :login})
        (not= token "/login")
        (-> (assoc-in [:ui :after-login] token)
            (sg/queue-fx :ui/redirect! {:token "/login"})))
      (case (first tokens)
        "home" (assoc-in tx-env [:ui :current-page] {:id :home})
        "secret" (-> tx-env
                     (assoc-in [:ui :current-page] {:id :secret})
                     (assoc-in [:ui :secret] :db/loading)
                     (sg/queue-fx :api {:request "/secret" :on-success (secret-loaded!)}))
        (sg/queue-fx tx-env :ui/redirect! {:token "/home"})))))

(deftx secret-loaded! []
  [tx-env {:keys [result]} e]
  (assoc-in tx-env [:ui :secret] (:secret result)))

(defc ui-secret []
  (bind secret (sg/kv-lookup :ui :secret))
  (hook (when (= :db/loading secret) (sg/suspend!)))
  (render
    (<< [:p#secret secret])))

;; ## logging in
;;
;; The form posts the credentials. Success stores the user, takes `:after-login` out of
;; `:ui` and redirects there, or home. A wrong password comes back as an error status,
;; handled by `request-error!` below.

(deftx logged-in! []
  [tx-env {:keys [result]} e]
  (let [target (get-in tx-env [:ui :after-login] "/home")]
    (-> tx-env
        (assoc-in [:ui :current-user] (:user result))
        (update :ui dissoc :after-login :error)
        (sg/queue-fx :ui/redirect! {:token target}))))

(deftx login! []
  {:e/prevent-default true}
  [tx-env ev e]
  (let [data (js/FormData. (.-target e))]
    (sg/queue-fx tx-env :api
      {:request {:uri "/login"
                 :body {:username (str/trim (.get data "username"))
                        :password (.get data "password")}}
       :on-success (logged-in!)})))

(defc ui-login []
  (bind error (sg/kv-lookup :ui :error))
  (bind after (sg/kv-lookup :ui :after-login))
  (render
    (<< [:form#login {:class (css :flex :flex-col :gap-2 {:max-width "16rem"}) :on-submit (login!)}
         (when after
           (<< [:div {:class c/$muted} "after login: " after]))
         [:input {:class c/$input :name "username" :placeholder "username (demo)"}]
         [:input {:class c/$input :name "password" :type "password" :placeholder "password (demo)"}]
         [:button {:class [c/$btn c/$btn-primary] :type "submit"} "log in"]
         (when error
           (<< [:div#error (css :text-red-700 :text-sm) error]))])))

;; ## 403 means the session is gone
;;
;; The handler's shared `:on-error` treats a 403 from any request as "logged out": it
;; drops the user and redirects to the login page. "expire session on the server" logs
;; out on the server only, so the client still thinks it has a session until the next
;; request (open "secret").

(deftx request-error! []
  [tx-env {:keys [status result]} e]
  (cond-> (assoc-in tx-env [:ui :error] (:error result))
    (= 403 status)
    (-> (assoc-in [:ui :current-user] nil)
        (sg/queue-fx :ui/redirect! {:token "/login"}))))

(deftx session-expired! []
  [tx-env ev e]
  (c/log! "server session gone, the next request gets a 403")
  tx-env)

(deftx expire-session! []
  [tx-env ev e]
  (sg/queue-fx tx-env :api {:request {:uri "/logout" :method :post} :on-success (session-expired!)}))

(deftx logged-out! []
  [tx-env ev e]
  (-> tx-env
      (assoc-in [:ui :current-user] nil)
      (sg/queue-fx :ui/redirect! {:token "/login"})))

(deftx logout! []
  [tx-env ev e]
  (sg/queue-fx tx-env :api {:request {:uri "/logout" :method :post} :on-success (logged-out!)}))

;; # page

(defc ui-app []
  (bind user (sg/kv-lookup :ui :current-user))
  (bind page (sg/kv-lookup :ui :current-page))
  (render
    (<< (when (map? user)
          (<< [:nav {:class [c/$row (css :mb-4)]}
               [:span#user "logged in as " (:name user)]
               [:a {:class c/$btn :ui/href "/home"} "home"]
               [:a {:class c/$btn :ui/href "/secret"} "secret"]
               [:button {:class c/$btn :on-click (expire-session!)} "expire session on the server"]
               [:button {:class c/$btn :on-click (logout!)} "log out"]]))
        (sg/suspense {:fallback (<< [:p "loading ..."])}
          (case (:id page)
            :login (ui-login)
            :home (<< [:p#home "home page"])
            :secret (ui-secret)
            nil)))))

(defc ui-root []
  (render
    (c/page "auth gate"
      {"the app" (ui-app)})))

(defn init []
  (boot!))

(defn ^:dev/after-load reload! []
  (when @started?
    (c/render! app (ui-root))))
