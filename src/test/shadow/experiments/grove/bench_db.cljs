(ns shadow.experiments.grove.bench-db
  (:require
    ["benchmark" :as b]
    ))

(defn log-cycle [event]
  (println (.toString (.-target event))))

(defn log-complete [event]
  (this-as this
    (js/console.log this)))

(defn db-flat-read [db idents]
  (->> idents
       (map #(get db %))
       (into [])))

(defn db-flat-write [db idents]
  (let [val (random-uuid)]
    (reduce
      (fn [db ident]
        (update db ident assoc ::val val))
      db
      idents)))

(defn db-nested-read [db idents]
  (->> idents
       (map #(get-in db %))
       (into [])))

(defn db-nested-write [db idents]
  (let [val (random-uuid)]
    (reduce
      (fn [db ident]
        (update-in db ident assoc ::val val))
      db
      idents)))

(defn gen-idents [num-types num-items]
  (->> (for [key-id (range num-types)
             val-id (range num-items)]
         [(keyword (str "key" key-id)) val-id])
       (shuffle)
       (vec)))

(defn gen-flat-db [idents]
  (reduce
    (fn [db ident]
      (assoc db ident {::ident ident}))
    {}
    idents))

(defn gen-nested-db [idents]
  (reduce
    (fn [db ident]
      (assoc-in db ident {::ident ident}))
    {}
    idents))

;; flat wins at pretty much any size
(defn main [& args]
  (let [idents
        (gen-idents 5 1000)

        flat-db
        (gen-flat-db idents)

        nested-db
        (gen-nested-db idents)

        read-idents
        (->> idents
             (shuffle)
             (take 50)
             (vec))

        get-ident
        (first idents)]

    (prn [:nested (count nested-db)])
    (prn [:flat (count flat-db)])

    (-> (b/Suite.)
        (.add "db-flat-get" #(get flat-db get-ident))
        (.add "db-nested-get" #(get-in nested-db get-ident))

        (.add "db-flat-read" #(db-flat-read flat-db read-idents))
        (.add "db-nested-read" #(db-nested-read nested-db read-idents))

        (.add "db-flat-write" #(db-flat-write flat-db read-idents))
        (.add "db-nested-write" #(db-nested-write nested-db read-idents))
        (.on "cycle" log-cycle)
        (.run))))
