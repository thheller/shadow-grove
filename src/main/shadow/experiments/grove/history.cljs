(ns shadow.experiments.grove.history
  (:require
    [clojure.string :as str]
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove.events :as ev]
    [shadow.experiments.arborist.attributes :as attr])
  (:import [goog.history Html5History]))

(defn init!
  [rt-ref
   {:keys [start-token path-prefix use-fragment]
    :or {start-token "/dashboard"
         path-prefix "/"
         use-fragment false}
    :as config}]

  {:pre [(str/starts-with? path-prefix "/")
         (not (str/ends-with? path-prefix "/"))]}

  (let [history
        (doto (Html5History.)
          (.setPathPrefix path-prefix)
          (.setUseFragment use-fragment))]

    (attr/add-attr :ui/href
      (fn [env node oval nval]
        (when nval
          (when-not (str/starts-with? nval "/")
            (throw (ex-info (str ":ui/href must start with / got " nval)
                     {:val nval})))

          (set! node -href
            (if use-fragment
              (str "#" nval)
              (str path-prefix
                   (if-not (str/ends-with? path-prefix "/")
                     nval
                     (subs nval 1))))))))

    (ev/reg-fx rt-ref :ui/redirect!
      (fn [env token]
        {:pre [(str/starts-with? token "/")]}
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
                    ;; token must start with /, strip it to get tokens vector
                    (let [token (.-token e)
                          tokens (str/split (subs token 1) #"/")]

                      (sg/run-now! env #(sg/run-tx env {:e :ui/route! :token token :tokens tokens})))))

                ;; fragment uses hashchange event so we can skip checking clicks
                (when-not use-fragment
                  (.addEventListener root-el "click"
                    (fn [^js e]
                      (when (and (zero? (.-button e))
                                 (not (or (.-shiftKey e) (.-metaKey e) (.-ctrlKey e) (.-altKey e))))
                        (when-let [a (some-> e .-target (.closest "a"))]

                          (let [href (.getAttribute a "href")
                                a-target (.getAttribute a "target")]

                            (when (and href (seq href) (str/starts-with? href path-prefix) (nil? a-target))
                              (.preventDefault e)
                              (.setToken history (subs href (count path-prefix))))))))))

                (.setEnabled history true)
                env)))))))