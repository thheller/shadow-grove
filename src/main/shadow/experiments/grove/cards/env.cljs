(ns shadow.experiments.grove.cards.env)

(defonce cards-ref (atom {}))

(defn register-card [id opts rendered]
  (swap! cards-ref update id merge {:id id
                                    :opts opts
                                    :dirty? true
                                    :rendered rendered}))