(ns shadow.grove.timeouts
  (:require
    [shadow.grove :as sg]
    [shadow.grove.runtime :as rt]
    [shadow.grove.events :as ev]))

;; FIXME: should this just be available by default?

(defn init! [rt-ref]
  (let [timeouts-ref (atom {})]

    (ev/reg-fx rt-ref :timeout/set
      (fn [env {:timeout/keys [id after] :as ev}]
        (let [tid (js/setTimeout
                    (fn []
                      (when id
                        (swap! timeouts-ref dissoc id))

                      (sg/run-tx! rt-ref ev))
                    after)]

          (when id
            (swap! timeouts-ref assoc id tid)))))

    (ev/reg-fx rt-ref :timeout/clear
      (fn [env {:timeout/keys [id]}]
        (when-some [tid (get @timeouts-ref id)]
          (js/clearTimeout tid)
          (swap! timeouts-ref dissoc id)
          )))

    rt-ref))


