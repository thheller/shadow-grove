(ns conduit.frontend.main
  (:require
    [clojure.string :as str]
    [shadow.experiments.grove-main :as sg]
    [shadow.experiments.arborist :as sa]
    [conduit.model :as m]
    [conduit.frontend.views :as views])
  (:import [goog.history Html5History]))

(defonce app-env {})

(defonce root-el (.getElementById js/document "app"))

(defn navigate-to-token! [token]
  (sg/run-now! app-env #(sg/run-tx app-env [::m/route! token])))

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

(comment
  (defn set-token! [env new-token]
    (let [^goog history (get-in env [:shared ::m/history])]
      (js/console.log "set-token!" env new-token)
      (.setToken history new-token)
      )))

(defn ^:dev/after-load start []
  (sg/start app-env root-el (views/ui-root)))

(defn init []
  (let [history
        (doto (Html5History.)
          (.setPathPrefix "/")
          (.setUseFragment false))]

    (set! app-env (assoc app-env :history history))
    (set! app-env (sg/init app-env ::conduit js/SHADOW_WORKER))

    (setup-history history)

    (js/setTimeout start 0)))
