(ns examples.transactions-test
  "tx handlers and queries are functions over maps: no runtime, no DOM.
   node out/examples-test/test.js after compiling :examples-test"
  (:require
    [cljs.test :as ct :refer (deftest is)]
    [examples.kv-reads :as reads]
    [examples.transactions :as tx]
    [shadow.grove :as sg]
    [shadow.grove.kv :as kv]))

(defn run-ev
  "calls the handler a deftx map carries in its metadata"
  [env ev]
  ((::sg/tx (meta ev)) env ev nil))

(deftest deftx-handler-without-runtime
  (let [env {:counter {:value 1}}]
    (is (= 6 (get-in (run-ev env (tx/add! 5)) [:counter :value])))
    (is (= 4 (get-in (run-ev env (tx/add-point! {:x 1 :y 2})) [:counter :value])))
    (is (nil? (run-ev env (tx/noop-nil!))))))

(deftest event-maps-are-data
  (let [ev (tx/add! 5)]
    (is (= {:e ::tx/add! :args [5]} ev))
    (is (fn? (::sg/tx (meta ev))))
    (is (= :tagged-button (:source (tx/add-tagged! 1))))))

(deftest reg-event-handlers-are-plain-fns
  (is (= 100 (get-in (tx/reset-counter {:counter {:value 1}} {:e ::tx/reset! :to 100} nil) [:counter :value]))))

(deftest queries-take-the-same-map
  ;; kv/init gives the table the config kv/add and kv/merge-seq look for
  (let [env {:item (kv/init :item {:primary-key :id}
                     {1 {:id 1 :label "one" :color :red}
                      2 {:id 2 :label "two" :color :blue}})}]
    (is (= ["one"] (reads/?items-by-color env :red)))
    (is (= ["one" "three"]
           (-> env
               (kv/add :item {:id 3 :label "three" :color :red})
               (reads/?items-by-color :red))))))
