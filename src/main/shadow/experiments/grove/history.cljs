(ns shadow.experiments.grove.history
  (:require
    [clojure.string :as str]
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove.events :as ev])
  (:import [goog.history Html5History]))

(defn init!
  [rt-ref
   {:keys [start-token path-prefix use-fragment]
    :or {start-token "dashboard"
         path-prefix "/"
         use-fragment false}
    :as config}]

  (let [history
        (doto (Html5History.)
          (.setPathPrefix path-prefix)
          (.setUseFragment use-fragment))]

    (ev/reg-fx rt-ref :ui/redirect!
      (fn [env token]
        ;; FIXME: should accept map to build token
        (.setToken history token)))

    (swap! rt-ref
      (fn [rt]
        (-> rt
            (assoc ::history history ::config config)
            (update ::rt/env-init conj
              (fn [{::rt/keys [root-el] :as env}]

                (let [first-token (.getToken history)]
                  (when (and (= "" first-token) (seq start-token))
                    (.replaceToken history start-token)))

                (.listen history js/goog.history.EventType.NAVIGATE
                  (fn [^goog e]
                    (sg/run-now! env #(sg/run-tx env {:e :ui/route! :token (.-token e)}))))

                (.addEventListener root-el "click"
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
                env)))))))