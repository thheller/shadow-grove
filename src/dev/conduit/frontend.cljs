(ns conduit.frontend
  (:require
    [clojure.string :as str]
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.arborist :as sa]
    [conduit.frontend.views :as views]
    [conduit.frontend.env :as env])
  (:import [goog.history Html5History]))

(defonce root-el (.getElementById js/document "app"))

(sg/reg-event-fx env/app ::route!
  []
  (fn [{:keys [db] :as env} token]
    (js/console.log ::route! env token)

    (let [[main & more :as tokens] (str/split token #"/")
          db (assoc db :route-tokens tokens)]
      (case main
        "article"
        (let [[slug & more] more]
          {:db (assoc db :active-page :article
                         :active-article (db/make-ident ::env/article slug))})

        "profile"
        (let [[username & more] more]
          {:db (assoc db :active-page :profile
                         :active-profile (db/make-ident ::env/user username))})

        "home"
        {:db (assoc db :active-page :home)}

        "register"
        {:db (assoc db :active-page :register)}

        "login"
        {:db (assoc db :active-page :login)}

        (js/console.warn "unknown-route" tokens)
        ))))

(defn navigate-to-token! [token]
  (sa/run-now! env/app #(sg/run-tx env/app ::route! token)))

(defn setup-history [^goog history]
  (let [start-token "dashboard"
        first-token (.getToken history)]
    (when (and (= "" first-token) (seq start-token))
      (.replaceToken history start-token)))

  (.listen history js/goog.history.EventType.NAVIGATE
    (fn [^goog e]
      (navigate-to-token! (.-token e))))

  (js/document.body.addEventListener "click"
    (fn [^js e]
      (when (and (zero? (.-button e))
                 (not (or (.-shiftKey e) (.-metaKey e) (.-ctrlKey e) (.-altKey e))))
        (when-let [a (some-> e .-target (.closest "a"))]

          (let [href (.getAttribute a "href")
                a-target (.getAttribute a "target")]

            (when (and href (seq href) (str/starts-with? href "/") (nil? a-target))
              (.preventDefault e)
              (.setToken history (subs href 1))))))))

  (.setEnabled history true))

(defn set-token! [env new-token]
  (let [^goog history (get-in env [:shared ::env/history])]
    (js/console.log "set-token!" env new-token)
    (.setToken history new-token)
    ))

(defn ^:dev/after-load start []
  (sg/start env/app root-el (views/ui-root)))

(defn init []
  (let [history
        (doto (Html5History.)
          (.setPathPrefix "/")
          (.setUseFragment false))]

    (setup-history history)

    (set! env/app (assoc env/app :history history))

    (start)))