(ns shadow.experiments.grove.history
  (:require
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.worker-engine :as eng]
    [clojure.string :as str])
  (:import [goog.history Html5History]))

(defn navigate-to-token! [app-env token]
  (sg/run-now! app-env #(sg/run-tx app-env {:e :ui/route! :token token})))

(defn setup-history [app-env ^goog history]
  (let [start-token "dashboard"
        first-token (.getToken history)]
    (when (and (= "" first-token) (seq start-token))
      (.replaceToken history start-token)))

  (.listen history js/goog.history.EventType.NAVIGATE
    (fn [^goog e]
      (navigate-to-token! app-env (.-token e))))

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

  (.setEnabled history true)
  app-env)

;; FIXME: this should take some config to be more flexible
(defn init []
  (fn [app-env]
    (let [history
          (doto (Html5History.)
            (.setPathPrefix "/")
            (.setUseFragment false))]

      (-> app-env
          (assoc ::history history)
          ;; FIXME: this shouldn't be coupled to the worker impl
          (eng/add-msg-handler :ui/redirect!
            (fn [token]
              (assert (str/starts-with? token "/") "redirect token must start with /")
              (.setToken history (subs token 1))))
          (setup-history history)
          ))))