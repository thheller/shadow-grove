(ns conduit.frontend.worker
  (:require
    [clojure.string :as str]
    [goog.string :as gstr]
    [shadow.experiments.grove.worker :as sg]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.http-fx :as http-fx]
    [shadow.json :as json]
    [conduit.model :as m])
  (:import [goog.history Html5History]))

(def api-base "https://conduit.productionready.io/api")

(defonce data-ref
  (-> {:active-page :home
       :tags ["butt" "test" "dragons" "training" "tags" "as" "coffee" "animation" "baby" "cars" "flowers" "caramel" "japan" "money" "happiness" "sugar" "clean" "sushi" "well" "cookies"]
       :articles-count 500}
      (with-meta {::db/schema (db/configure m/schema)})
      (atom)))

(defonce app-env
  (-> {}
      (sg/prepare data-ref)))

(sg/reg-event-fx app-env ::request-error!
  []
  (fn [{:keys [db] :as env} body status request]
    (js/console.log "request-error" status request)
    {}))

(sg/reg-event-fx app-env :home-articles-loaded
  []
  (fn [{:keys [db] :as env} {::m/keys [articles articles-count] :as result}]
    {:db (-> db
             (assoc ::m/articles-count articles-count)
             (db/merge-seq ::m/article articles [::m/home-articles]))}))

(sg/reg-event-fx app-env :active-profile-loaded
  []
  (fn [{:keys [db] :as env} profile-ident {::m/keys [profile] :as result}]
    ;; this is horrible .. should send out both requests at once and coordinate when both complete
    ;; this should not be done sequentially
    {:db (assoc db profile-ident (assoc profile :written-articles ::db/loading))
     :http {:uri [api-base "/articles" {:author (db/ident-val profile-ident)}]
            :on-success [:active-profile-articles-loaded profile-ident]}}))

(sg/reg-event-fx app-env :active-profile-articles-loaded
  []
  (fn [{:keys [db] :as env} profile-ident {::m/keys [articles articles-count] :as result}]
    {:db (-> db
             (assoc-in [profile-ident :written-articles-count] articles-count)
             (db/merge-seq ::m/article articles [profile-ident :written-articles]))}))

(sg/reg-event-fx app-env ::m/route!
  []
  (fn [{:keys [db] :as env} token]
    (tap> [::m/route! token env])
    (let [[main & more :as tokens] (str/split token #"/")
          db (assoc db :route-tokens tokens)]
      (case main
        "article"
        (let [[slug & more] more]
          {:db (assoc db :active-page :article
                         :active-article (db/make-ident ::m/article slug))})

        "profile"
        (let [[username & more] more

              profile-ident
              (db/make-ident ::m/user username)]

          (-> {:db (assoc db :active-page :profile
                             :active-profile profile-ident)}

              (cond->
                (not (get db profile-ident))
                (-> (update :db assoc profile-ident ::db/loading)
                    (assoc :http {:uri [api-base "/profiles" username]
                                  :on-success [:active-profile-loaded profile-ident]})
                    ))))

        ;; FIXME: should this always request articles? we might have some?
        "home"
        {:http {:uri [api-base "/articles"]
                :on-success [:home-articles-loaded]}
         :db (assoc db :active-page :home
                       ::m/home-articles ::db/loading)}

        "register"
        {:db (assoc db :active-page :register)}

        "login"
        {:db (assoc db :active-page :login)}

        (js/console.warn "unknown-route" tokens)
        ))))

(defn ^:dev/after-load start [])

(defn json-key-fn [key]
  (keyword "conduit.model" (gstr/toSelectorCase key)))

(defn init []
  (set! app-env (-> app-env
                    ;; FIXME: add http-fx/init fn that does all the setup
                    (assoc ::http-fx/on-error [::request-error!])
                    (sg/reg-fx :http http-fx/handler)
                    (http-fx/with-default-formats)
                    (assoc-in [::http-fx/response-formats "application/json"]
                      (fn [env xhr-req]
                        (json/to-clj (js/JSON.parse (.-responseText xhr-req))
                          {:key-fn (memoize json-key-fn)})))
                    (sg/init)))
  (start))