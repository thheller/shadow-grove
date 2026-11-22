(ns shadow.grove.kv-index-test
  (:require
    ["jsdom" :refer (JSDOM)]
    [cljs.test :as ct :refer (deftest is async)]
    [shadow.grove :as sg :refer (defc <<)]
    [shadow.grove.impl :as impl]))

(defn make-env [rt-id]
  (let [dom (JSDOM. "<!DOCTYPE html>")
        doc (-> dom .-window .-document)]
    (set! js/global -document doc)
    {:root (.-body doc)
     :rt-ref (sg/get-runtime rt-id)}))

(defn wait []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 10))))

(defn run-steps [done steps]
  (-> (reduce
        (fn [p step]
          (.then p (fn [] (step) (wait))))
        (js/Promise.resolve nil)
        steps)
      (.catch (fn [e]
                (js/console.error e)
                (is (nil? e) "step failed")))
      (.then done)))

;; the index is shared by all runtimes, every test uses its own tables
(defn read-keys [& tables]
  (let [tables (set tables)]
    (->> (impl/read-keys)
         (filter #(contains? tables (first %)))
         (set))))

(defn read-tables [& tables]
  (let [tables (set tables)]
    (->> (impl/read-tables)
         (filter #(contains? tables %))
         (set))))

(defn take-log! [log-ref]
  (let [log @log-ref]
    (reset! log-ref [])
    log))

(defn tx! [rt-ref f]
  (sg/run-tx! rt-ref (fn [env _ _] (f env))))

;; -- sg/kv-lookup

(defc ui-lookup [log-ref id]
  (bind item
    (sg/kv-lookup ::lookup id))
  (render
    (swap! log-ref conj id)
    (<< [:span (str (:text item) ";")])))

(deftest kv-lookup-reads
  (let [{:keys [root rt-ref]} (make-env ::lookup-rt)
        log-ref (atom [])]

    (sg/add-kv-table rt-ref ::lookup {:primary-key :id}
      {1 {:id 1 :text "a"}
       2 {:id 2 :text "b"}})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root
             (<< (ui-lookup log-ref 1)
                 (ui-lookup log-ref 1)
                 (ui-lookup log-ref 2)))
           (is (= "a;a;b;" (.-textContent root)))
           (is (= [1 1 2] (take-log! log-ref)))
           ;; two readers, one entry
           (is (= #{[::lookup 1] [::lookup 2]} (read-keys ::lookup)))
           (is (= #{} (read-tables ::lookup))))

         ;; both readers of a key are told
         (fn []
           (tx! rt-ref #(assoc-in % [::lookup 1 :text] "x")))

         (fn []
           (is (= "x;x;b;" (.-textContent root)))
           (is (= [1 1] (take-log! log-ref))))

         ;; one of the two readers moves to another key, the key stays read
         (fn []
           (sg/render rt-ref root
             (<< (ui-lookup log-ref 1)
                 (ui-lookup log-ref 2)
                 (ui-lookup log-ref 2)))
           (is (= "x;b;b;" (.-textContent root)))
           (is (= #{[::lookup 1] [::lookup 2]} (read-keys ::lookup)))
           (take-log! log-ref))

         (fn []
           (tx! rt-ref #(assoc-in % [::lookup 1 :text] "y")))

         (fn []
           (is (= "y;b;b;" (.-textContent root)))
           (is (= [1] (take-log! log-ref))))

         ;; the last reader of a key leaves, a key without a value can be read
         (fn []
           (sg/render rt-ref root
             (<< (ui-lookup log-ref 3)
                 (ui-lookup log-ref 2)
                 (ui-lookup log-ref 2)))
           (is (= ";b;b;" (.-textContent root)))
           (is (= #{[::lookup 3] [::lookup 2]} (read-keys ::lookup)))
           (take-log! log-ref))

         ;; no reader left for 1, a reader of a new key is told
         (fn []
           (tx! rt-ref #(-> %
                            (assoc-in [::lookup 1 :text] "z")
                            (assoc-in [::lookup 3] {:id 3 :text "c"}))))

         (fn []
           (is (= "c;b;b;" (.-textContent root)))
           (is (= [3] (take-log! log-ref))))

         ;; removed keys
         (fn []
           (tx! rt-ref #(update % ::lookup dissoc 2)))

         (fn []
           (is (= "c;;;" (.-textContent root)))
           (is (= [2 2] (take-log! log-ref))))

         (fn []
           (sg/unmount-root root)
           (is (= #{} (read-keys ::lookup))))]))))

;; -- key types and both ways invalidate-kv! finds the readers

(def a-uuid (uuid "0b3b2a0e-7b0a-4f3e-9d0a-1c2d3e4f5a6b"))

(def typed-keys
  [1 "1" :one ::one [1 :one] a-uuid])

(defc ui-typed [log-ref key]
  (bind val
    (sg/kv-lookup ::typed key))
  (render
    (swap! log-ref conj key)
    (<< [:span (str val ";")])))

(deftest kv-lookup-key-types
  (let [{:keys [root rt-ref]} (make-env ::typed-rt)
        log-ref (atom [])]

    (sg/add-kv-table rt-ref ::typed {}
      (zipmap typed-keys (repeat 0)))

    (async done
      (run-steps done
        (-> [(fn []
               (sg/render rt-ref root
                 (sg/keyed-seq typed-keys identity #(ui-typed log-ref %)))
               (is (= "0;0;0;0;0;0;" (.-textContent root)))
               (is (= (set (map #(vector ::typed %) typed-keys)) (read-keys ::typed)))
               (take-log! log-ref))]

            ;; a tx changing one key, fewer than are read: looks up the changed key.
            ;; keys are created again, they must not need to be identical
            (into
              (mapcat
                (fn [key]
                  [(fn []
                     (let [key (cond
                                 (keyword? key) (keyword (namespace key) (name key))
                                 (vector? key) (vec key)
                                 (uuid? key) (uuid (str key))
                                 :else key)]
                       (tx! rt-ref #(update-in % [::typed key] inc))))
                   (fn []
                     (is (= [key] (take-log! log-ref))))]))
              typed-keys)

            (into
              [(fn []
                 (is (= "1;1;1;1;1;1;" (.-textContent root))))

               ;; a tx changing more keys than are read: walks the read keys
               (fn []
                 (tx! rt-ref
                   (fn [env]
                     (-> (reduce #(assoc-in %1 [::typed [:other %2]] %2) env (range 20))
                         (update-in [::typed 1] inc)
                         (update-in [::typed ::one] inc)
                         (update-in [::typed [1 :one]] inc)
                         (update ::typed dissoc a-uuid)))))

               (fn []
                 (is (= "2;1;1;2;2;;" (.-textContent root)))
                 (is (= #{1 ::one [1 :one] a-uuid} (set (take-log! log-ref)))))

               (fn []
                 (sg/unmount-root root)
                 (is (= #{} (read-keys ::typed))))]))))))

;; -- sg/query

(def query-runs (atom 0))

(defn ?texts [env ids]
  (swap! query-runs inc)
  (mapv #(:text (get (::query env) %)) ids))

(defn ?count [env]
  (count (seq (::query env))))

(defc ui-query [log-ref ids]
  (bind texts
    (sg/query ?texts ids))
  (render
    (swap! log-ref conj ids)
    (<< [:span (str texts ";")])))

(defc ui-count []
  (bind n
    (sg/query ?count))
  (render
    (<< [:b n])))

(deftest query-reads
  (let [{:keys [root rt-ref]} (make-env ::query-rt)
        log-ref (atom [])]

    (reset! query-runs 0)

    (sg/add-kv-table rt-ref ::query {:primary-key :id}
      {1 {:id 1 :text "a"}
       2 {:id 2 :text "b"}
       3 {:id 3 :text "c"}})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root
             (<< (ui-query log-ref [1 2])
                 (ui-query log-ref [1 2])
                 (ui-count)))
           (is (= "[\"a\" \"b\"];[\"a\" \"b\"];3" (.-textContent root)))
           ;; shared by both components
           (is (= 1 @query-runs))
           (is (= #{[::query 1] [::query 2]} (read-keys ::query)))
           (is (= #{::query} (read-tables ::query)))
           (take-log! log-ref))

         ;; two of its keys change in one tx, runs once, both components update
         (fn []
           (tx! rt-ref #(-> %
                            (assoc-in [::query 1 :text] "x")
                            (assoc-in [::query 2 :text] "y"))))

         (fn []
           (is (= "[\"x\" \"y\"];[\"x\" \"y\"];3" (.-textContent root)))
           (is (= 2 @query-runs))
           (is (= [[1 2] [1 2]] (take-log! log-ref))))

         ;; a key it did not read
         (fn []
           (tx! rt-ref #(assoc-in % [::query 3 :text] "z")))

         (fn []
           (is (= 2 @query-runs))
           (is (= [] (take-log! log-ref))))

         ;; the seq reader is told about new keys
         (fn []
           (tx! rt-ref #(assoc-in % [::query 4] {:id 4 :text "d"})))

         (fn []
           (is (= "[\"x\" \"y\"];[\"x\" \"y\"];4" (.-textContent root)))
           (is (= 2 @query-runs)))

         ;; other args are another query, the first one keeps its reads while it has a sub
         (fn []
           (sg/render rt-ref root
             (<< (ui-query log-ref [1 2])
                 (ui-query log-ref [3])
                 (ui-count)))
           (is (= "[\"x\" \"y\"];[\"z\"];4" (.-textContent root)))
           (is (= #{[::query 1] [::query 2] [::query 3]} (read-keys ::query))))

         ;; and drops them with the last one
         (fn []
           (sg/render rt-ref root
             (<< (ui-query log-ref [3])
                 (ui-query log-ref [3])
                 (ui-count)))
           (is (= #{[::query 3]} (read-keys ::query)))
           (is (= #{::query} (read-tables ::query))))

         (fn []
           (sg/unmount-root root)
           (is (= #{} (read-keys ::query)))
           (is (= #{} (read-tables ::query))))]))))

;; -- a query reading other keys on its next run

(defn ?follow [env]
  (let [id (get (::follow env) :current)]
    (get (::follow env) id)))

(defc ui-follow []
  (bind val
    (sg/query ?follow))
  (render
    (<< [:span (str val)])))

(deftest query-changes-reads
  (let [{:keys [root rt-ref]} (make-env ::follow-rt)]

    (sg/add-kv-table rt-ref ::follow {}
      {:current :a
       :a "A"
       :b "B"})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-follow))
           (is (= "A" (.-textContent root)))
           (is (= #{[::follow :current] [::follow :a]} (read-keys ::follow))))

         (fn []
           (tx! rt-ref #(assoc-in % [::follow :current] :b)))

         (fn []
           (is (= "B" (.-textContent root)))
           (is (= #{[::follow :current] [::follow :b]} (read-keys ::follow))))

         ;; no longer read
         (fn []
           (tx! rt-ref #(assoc-in % [::follow :a] "A2")))

         (fn []
           (is (= "B" (.-textContent root))))

         (fn []
           (tx! rt-ref #(assoc-in % [::follow :b] "B2")))

         (fn []
           (is (= "B2" (.-textContent root))))

         (fn []
           (sg/unmount-root root)
           (is (= #{} (read-keys ::follow))))]))))

;; -- (sg/kv-lookup table)

(defc ui-table []
  (bind table
    (sg/kv-lookup ::whole))
  (render
    (<< [:span (str (count table))])))

(deftest kv-lookup-table
  (let [{:keys [root rt-ref]} (make-env ::whole-rt)]

    (sg/add-kv-table rt-ref ::whole {} {1 :a})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-table))
           (is (= "1" (.-textContent root)))
           (is (= #{} (read-keys ::whole)))
           (is (= #{::whole} (read-tables ::whole))))

         (fn []
           (tx! rt-ref #(assoc-in % [::whole 2] :b)))

         (fn []
           (is (= "2" (.-textContent root))))

         (fn []
           (sg/unmount-root root)
           (is (= #{} (read-tables ::whole))))]))))

;; -- ObservedData protocols in queries (contains?, find, IFn, count, reduce-kv)

(defn ?has-key [env k]
  (contains? (::obs-proto env) k))

(defn ?find-key [env k]
  (some-> (find (::obs-proto env) k) val))

(defn ?fn-key [env k]
  ((::obs-proto env) k))

(defn ?count-direct [env]
  (count (::obs-proto env)))

(defn ?sum-reduce-kv [env]
  (reduce-kv (fn [acc _ v] (+ acc v)) 0 (::obs-proto env)))

(defc ui-obs-protos [log-ref]
  (bind has-2? (sg/query ?has-key 2))
  (bind found-1 (sg/query ?find-key 1))
  (bind fn-1 (sg/query ?fn-key 1))
  (bind cnt (sg/query ?count-direct))
  (bind total (sg/query ?sum-reduce-kv))
  (render
    (swap! log-ref conj [has-2? found-1 fn-1 cnt total])
    (<< [:span (str has-2? "/" found-1 "/" fn-1 "/" cnt "/" total)])))

(deftest observed-protocols-track-queries
  (let [{:keys [root rt-ref]} (make-env ::obs-proto-rt)
        log-ref (atom [])]
    (sg/add-kv-table rt-ref ::obs-proto {} {1 10})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-obs-protos log-ref))
           (is (= "false/10/10/1/10" (.-textContent root)))
           (is (= #{[::obs-proto 1] [::obs-proto 2]} (read-keys ::obs-proto)))
           (is (= #{::obs-proto} (read-tables ::obs-proto)))
           (take-log! log-ref))

         ;; adding key 2 triggers ?has-key, ?count-direct, ?sum-reduce-kv
         (fn []
           (tx! rt-ref #(assoc-in % [::obs-proto 2] 20)))

         (fn []
           (is (= "true/10/10/2/30" (.-textContent root))))

         ;; updating key 1 triggers ?find-key, ?fn-key, ?sum-reduce-kv
         (fn []
           (tx! rt-ref #(assoc-in % [::obs-proto 1] 15)))

         (fn []
           (is (= "true/15/15/2/35" (.-textContent root))))

         ;; setting table to nil clears it and notifies all readers
         (fn []
           (tx! rt-ref #(assoc % ::obs-proto nil)))

         (fn []
           (is (= "false///0/0" (.-textContent root))))

         (fn []
           (sg/unmount-root root)
           (is (= #{} (read-keys ::obs-proto)))
           (is (= #{} (read-tables ::obs-proto))))]))))

;; -- sg/kv-lookups shares its query across components and re-runs

(defc ui-kv-lookups [ids]
  (bind items (sg/kv-lookups ::lookups ids))
  (render
    (<< [:span (str (count items) ":" (get-in items [1 :text]) ";")])))

(deftest kv-lookups-shares-query
  (let [{:keys [root rt-ref]} (make-env ::lookups-rt)]
    (sg/add-kv-table rt-ref ::lookups {:primary-key :id}
      {1 {:id 1 :text "a"}
       2 {:id 2 :text "b"}})

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root
             (<< (ui-kv-lookups [1 2])
                 (ui-kv-lookups [1 2])))
           (is (= "2:a;2:a;" (.-textContent root)))
           ;; both components share one active query entry
           (let [matching (filter (fn [[[_ args] _]] (= [::lookups [1 2]] args)) @impl/active-queries-ref)]
             (is (= 1 (count matching)))))

         (fn []
           (let [q-before (val (first (filter (fn [[[_ args] _]] (= [::lookups [1 2]] args)) @impl/active-queries-ref)))]
             (tx! rt-ref #(assoc-in % [::lookups 1 :text] "x"))
             (swap! rt-ref assoc ::q-before q-before)))

         (fn []
           (is (= "2:x;2:x;" (.-textContent root)))
           (let [q-after (val (first (filter (fn [[[_ args] _]] (= [::lookups [1 2]] args)) @impl/active-queries-ref)))]
             (is (identical? (::q-before @rt-ref) q-after))))

         (fn []
           (sg/unmount-root root)
           (is (= #{} (read-keys ::lookups))))]))))
