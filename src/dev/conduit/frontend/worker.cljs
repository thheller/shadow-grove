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
  (fn [{:keys [db] :as env} profile-ident {:keys [articles profile] :as result}]
    ;; annoying nesting in the api
    ;; one level from request-many one level from http responses
    (let [{::m/keys [articles articles-count]} articles
          profile (::m/profile profile)]
      {:db (-> db
               (assoc profile-ident (assoc profile :written-articles-count articles-count))
               (db/merge-seq ::m/article articles [profile-ident :written-articles]))
       })))

(sg/reg-event-fx app-env :active-article-loaded
  []
  (fn [{:keys [db] :as env} article-ident {::m/keys [article] :as result}]
    {:db (db/add db ::m/article article)}))

(sg/reg-event-fx app-env ::m/route!
  []
  (fn [{:keys [db] :as env} token]
    (let [[main & more :as tokens] (str/split token #"/")
          db (assoc db :route-tokens tokens)]
      (case main
        "article"
        (let [[slug & more] more
              article-ident (db/make-ident ::m/article slug)]
          (-> {:db (assoc db :active-page :article
                             :active-article article-ident)}

              (cond->
                (not (get db article-ident))
                (-> (update :db assoc article-ident ::db/loading)
                    (assoc :conduit-api
                           {:request ["/articles" slug]
                            :on-success [:active-article-loaded article-ident]}))
                )))

        "profile"
        (let [[username & more] more

              profile-ident
              (db/make-ident ::m/user username)]

          (-> {:db (assoc db :active-page :profile
                             :active-profile profile-ident)}

              (cond->
                (or (not (get db profile-ident))
                    ;; we already might have a profile but no articles
                    ;; FIXME: should probably instead display the profile and load articles async
                    (not (get-in db [profile-ident :written-articles])))
                (-> (update :db assoc profile-ident ::db/loading)
                    (assoc :conduit-api
                           {:request-many
                            {:profile ["/profiles" username]
                             :articles ["/articles" {:author username}]}
                            :on-success [:active-profile-loaded profile-ident]}))
                )))

        ;; FIXME: should this always request articles? we might have some?
        "home"
        {:conduit-api
         {:request ["/articles"]
          :on-success [:home-articles-loaded]}

         :db
         (assoc db :active-page :home
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
                    (sg/reg-fx :conduit-api
                      (http-fx/make-handler
                        {:on-error [::request-error!]
                         :base-url "https://conduit.productionready.io/api"
                         :request-format
                         (fn [env body opts]
                           (json/write-str body))
                         :response-formats
                         {"application/json"
                          (fn [env xhr-req]
                            (json/to-clj (js/JSON.parse (.-responseText xhr-req))
                              {:key-fn (memoize json-key-fn)}))}}))
                    (sg/init)))
  (start))