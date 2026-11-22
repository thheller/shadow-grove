(ns shadow.grove.kv-test
  (:require
    [cljs.test :as ct :refer (deftest is)]
    [shadow.grove.kv :as kv]))

(defn todo-table []
  (kv/init :todo {:primary-key :id}
    {1 {:id 1 :text "a"}
     2 {:id 2 :text "b"}
     3 {:id 3 :text "c"}}))

(defn dissoc-each [kv]
  (reduce dissoc kv (keys kv)))

(defn remove-all [kv]
  (:todo (kv/remove-all {:todo kv} :todo)))

;; what the tx did before the table is emptied
(def histories
  {:untouched identity
   :added #(assoc % 4 {:id 4})
   :updated #(assoc % 1 {:id 1 :text "x"})
   :removed #(dissoc % 2)
   :removed-and-added-again #(-> % (dissoc 2) (assoc 2 {:id 2 :text "y"}))
   :added-and-removed #(-> % (assoc 4 {:id 4}) (dissoc 4))
   :everything #(-> % (assoc 4 {:id 4}) (assoc 1 {:id 1 :text "x"}) (dissoc 2) (assoc 5 {:id 5}) (dissoc 5))})

(deftest remove-all-tracks-keys-like-dissoc
  (doseq [[label history] histories]
    (let [expected (-> (kv/transacted (todo-table)) (history) (dissoc-each) (kv/tx-commit!))
          actual (-> (kv/transacted (todo-table)) (history) (remove-all) (kv/tx-commit!))]
      (is (= expected actual) (str label))
      (is (= {} (:data actual)) (str label))
      ;; table config must survive
      (is (= (meta (:data expected)) (meta (:data actual))) (str label)))))

(deftest remove-all-then-write
  (let [commit
        (-> (kv/transacted (todo-table))
            (remove-all)
            (assoc 1 {:id 1 :text "again"})
            (assoc 9 {:id 9})
            (kv/tx-commit!))]

    (is (= {1 {:id 1 :text "again"} 9 {:id 9}} (:data commit)))
    (is (= #{9} (:keys-new commit)))
    ;; existed before the tx, so removing and adding it is an update
    (is (= #{1} (:keys-updated commit)))
    (is (= #{2 3} (:keys-removed commit)))))

(deftest remove-all-on-empty-table
  (let [kv (kv/transacted (kv/init :todo {:primary-key :id} {}))
        commit (kv/tx-commit! (remove-all kv))]
    (is (identical? (:data commit) (:data-before commit)))
    (is (= #{} (:keys-removed commit)))))

(deftest remove-all-outside-tx
  (let [table (todo-table)
        result (:todo (kv/remove-all {:todo table} :todo))]
    (is (= {} result))
    (is (= (meta table) (meta result)))))

(defn tx-env []
  {:post (kv/transacted (kv/init :post {:primary-key :id :joins {:author :user :comments :comment}} {}))
   :user (kv/transacted (kv/init :user {:primary-key :id} {7 {:id 7 :name "old" :admin true}}))
   :comment (kv/transacted (kv/init :comment {:primary-key :id} {}))
   :db (kv/transacted (kv/init :db {} {}))})

(deftest merge-seq-normalizes-across-tables
  (let [env
        (kv/merge-seq (tx-env) :post
          [{:id 1 :title "one" :author {:id 7 :name "seven"} :comments [{:id 10 :text "x"} {:id 11 :text "y"}]}
           {:id 2 :title "two" :author {:id 8 :name "eight"}}
           {:id 3 :title "three" :author {:id 7 :name "seven!"}}]
          [:db :posts])

        post (kv/tx-commit! (:post env))
        user (kv/tx-commit! (:user env))
        comment (kv/tx-commit! (:comment env))]

    (is (= [1 2 3] (get-in env [:db :posts])))

    (is (= {1 {:id 1 :title "one" :author 7 :comments [10 11]}
            2 {:id 2 :title "two" :author 8}
            3 {:id 3 :title "three" :author 7}}
           (:data post)))
    (is (= #{1 2 3} (:keys-new post)))

    ;; merged into the existing user, later items win
    (is (= {7 {:id 7 :name "seven!" :admin true}
            8 {:id 8 :name "eight"}}
           (:data user)))
    (is (= #{8} (:keys-new user)))
    (is (= #{7} (:keys-updated user)))

    (is (= {10 {:id 10 :text "x"} 11 {:id 11 :text "y"}} (:data comment)))))

(deftest add-merges-into-existing
  (let [env (kv/add (tx-env) :user {:id 7 :name "new"})]
    (is (= {:id 7 :name "new" :admin true} (get-in env [:user 7])))))

;; a plain map in place of a table, what the kv-interceptor does with (assoc tx-env :todo new-map)

;; writing new-table entry by entry, the tracking transacted-replace must match
(defn write-each [kv new-table]
  (reduce
    (fn [kv k]
      (if (contains? new-table k)
        (assoc kv k (get new-table k))
        (dissoc kv k)))
    kv
    (into (set (keys kv)) (keys new-table))))

(def replacements
  (let [table (todo-table)]
    {:same-instance table
     :same-entries (into {} table)
     :empty {}
     :all-new {7 {:id 7} 8 {:id 8} 9 {:id 9}}
     :one-more (assoc table 4 {:id 4})
     :one-less (dissoc table 2)
     :one-changed (assoc table 2 {:id 2 :text "changed"})
     :mixed (-> table (dissoc 1) (assoc 2 {:id 2 :text "changed"}) (assoc 4 {:id 4}))
     ;; same count as before, one removed and one added
     :swapped (-> table (dissoc 1) (assoc 4 {:id 4}))
     :bigger (into {} (map (fn [id] [id {:id id}])) (range 100))}))

(deftest replace-tracks-keys-like-single-writes
  (doseq [[label new-table] replacements]
    (let [expected (-> (kv/transacted (todo-table)) (write-each new-table) (kv/tx-commit!))
          actual (-> (kv/transacted (todo-table)) (kv/transacted-replace new-table) (kv/tx-commit!))]
      (is (= expected actual) (str label))
      (is (= (meta (:data expected)) (meta (:data actual))) (str label)))))

(deftest replace-without-changes-keeps-table
  (let [table (todo-table)]
    (doseq [new-table [table (into {} table) (with-meta (into {} table) {:other true})]]
      (let [commit (-> (kv/transacted table) (kv/transacted-replace new-table) (kv/tx-commit!))]
        (is (identical? table (:data commit)))
        (is (identical? (:data commit) (:data-before commit)))))))

(deftest replace-ignores-earlier-writes
  ;; the new map is the table after the tx, so only the difference to before counts
  (let [table (todo-table)
        ;; entry 1 is the instance from before, an equal copy would count as an update
        new-table {1 (get table 1) 4 {:id 4}}
        commit
        (-> (kv/transacted table)
            (assoc 5 {:id 5})
            (dissoc 1)
            (assoc 2 {:id 2 :text "x"})
            (kv/transacted-replace new-table)
            (kv/tx-commit!))]

    (is (= new-table (:data commit)))
    (is (= #{4} (:keys-new commit)))
    (is (= #{} (:keys-updated commit)))
    (is (= #{2 3} (:keys-removed commit)))))

(deftest replace-validates-changed-entries
  (let [seen (atom [])
        table (kv/init :todo
                {:primary-key :id
                 :validate-fn (fn [table key value]
                                (swap! seen conj key)
                                (when-not (number? key)
                                  (throw (ex-info "bad key" {:key key}))))}
                {1 {:id 1} 2 {:id 2}})
        one (get table 1)]

    (kv/transacted-replace (kv/transacted table) {1 one 2 {:id 2 :x 1} 3 {:id 3}})
    ;; 1 is the same instance, not a write
    (is (= #{2 3} (set @seen)))

    (is (thrown? ExceptionInfo (kv/transacted-replace (kv/transacted table) {"a" {:id "a"}})))
    (is (thrown? ExceptionInfo (kv/transacted-replace (kv/transacted table) {nil {:id nil}})))))

(deftest observed-data-map-protocols
  (let [table (todo-table)]
    ;; contains? tracks key
    (let [obs (kv/observed table)]
      (is (true? (contains? obs 1)))
      (is (false? (contains? obs 99)))
      (is (= [false #{1 99}] (kv/observed-keys obs))))

    ;; find tracks key
    (let [obs (kv/observed table)]
      (is (= [1 {:id 1 :text "a"}] (find obs 1)))
      (is (nil? (find obs 99)))
      (is (= [false #{1 99}] (kv/observed-keys obs))))

    ;; IFn tracks key
    (let [obs (kv/observed table)]
      (is (= {:id 1 :text "a"} (obs 1)))
      (is (= :missing (obs 99 :missing)))
      (is (= [false #{1 99}] (kv/observed-keys obs))))

    ;; nil key checks on contains?, find, IFn
    (let [obs (kv/observed table)]
      (is (thrown? ExceptionInfo (contains? obs nil)))
      (is (thrown? ExceptionInfo (find obs nil)))
      (is (thrown? ExceptionInfo (obs nil))))

    (let [obs (kv/observed (kv/init :allow-nil {:allow-nil-key? true} {nil :ok}))]
      (is (true? (contains? obs nil)))
      (is (= [nil :ok] (find obs nil)))
      (is (= :ok (obs nil)))
      (is (= [false #{nil}] (kv/observed-keys obs))))

    ;; count marks seq-used
    (let [obs (kv/observed table)]
      (is (= 3 (count obs)))
      (is (= [true #{}] (kv/observed-keys obs))))

    ;; reduce-kv marks seq-used
    (let [obs (kv/observed table)]
      (is (= #{1 2 3} (reduce-kv (fn [acc k _] (conj acc k)) #{} obs)))
      (is (= [true #{}] (kv/observed-keys obs))))

    ;; reduce (2- and 3-arity) marks seq-used
    (let [obs (kv/observed table)]
      (is (= 6 (reduce (fn [acc [k _]] (+ acc k)) 0 obs)))
      (is (= [true #{}] (kv/observed-keys obs))))
    (let [obs (kv/observed {1 10})]
      (is (= [1 10] (reduce (fn [a b] a) obs)))
      (is (= [true #{}] (kv/observed-keys obs))))

    ;; into / iterator marks seq-used
    (let [obs (kv/observed table)]
      (is (= table (into {} obs)))
      (is (= [true #{}] (kv/observed-keys obs))))

    ;; equiv (both directions and observed-to-observed) marks seq-used
    (let [obs1 (kv/observed table)
          obs2 (kv/observed table)
          obs3 (kv/observed table)]
      (is (= obs1 table))
      (is (= obs1 obs2))
      (is (not= obs1 {}))
      (is (= [true #{}] (kv/observed-keys obs1)))
      (is (= [true #{}] (kv/observed-keys obs2)))
      ;; plain map on the left calls -count and -lookup on obs3
      (is (= table obs3))
      (is (true? (first (kv/observed-keys obs3)))))

    ;; hash matches underlying map and marks seq-used
    (let [obs (kv/observed table)]
      (is (= (hash table) (hash obs)))
      (is (= [true #{}] (kv/observed-keys obs))))

    ;; empty and pr-str
    (let [obs (kv/observed table)]
      (is (= {} (empty obs)))
      (is (= (pr-str table) (pr-str obs)))
      (is (= [true #{}] (kv/observed-keys obs))))

    ;; read-only mutations throw
    (let [obs (kv/observed table)]
      (is (thrown? ExceptionInfo (assoc obs 4 {:id 4})))
      (is (thrown? ExceptionInfo (dissoc obs 1)))
      (is (thrown? ExceptionInfo (conj obs [4 {:id 4}]))))))

(deftest transacted-data-map-protocols
  (let [table (todo-table)
        tx (kv/transacted table)]
    ;; IFn, find, reduce-kv, reduce, into, equiv, hash, empty, pr-str
    (is (= {:id 1 :text "a"} (tx 1)))
    (is (= :missing (tx 99 :missing)))
    (is (= [2 {:id 2 :text "b"}] (find tx 2)))
    (is (nil? (find tx 99)))
    (is (= #{1 2 3} (reduce-kv (fn [acc k _] (conj acc k)) #{} tx)))
    (is (= 6 (reduce (fn [acc [k _]] (+ acc k)) 0 tx)))
    (is (= table (into {} tx)))
    (is (= tx table))
    (is (= table tx))
    (is (= tx (kv/transacted table)))
    (is (= (hash table) (hash tx)))
    (is (= (pr-str table) (pr-str tx)))

    ;; empty does not mutate tx tracking state
    (is (= {} (empty tx)))
    (let [commit (kv/tx-commit! tx)]
      (is (= #{} (:keys-removed commit)))
      (is (identical? table (:data commit)))
      ;; pr-str still works after commit for debugging
      (is (= (pr-str table) (pr-str tx)))
      ;; completed tx throws on reads/ops
      (is (thrown? ExceptionInfo (seq tx)))
      (is (thrown? ExceptionInfo (contains? tx 1)))
      (is (thrown? ExceptionInfo (tx 1)))
      (is (thrown? ExceptionInfo (find tx 1)))
      (is (thrown? ExceptionInfo (reduce-kv (fn [acc _ _] acc) nil tx)))
      (is (thrown? ExceptionInfo (reduce (fn [acc _] acc) nil tx)))
      (is (thrown? ExceptionInfo (into {} tx)))
      (is (thrown? ExceptionInfo (= tx table)))
      (is (thrown? ExceptionInfo (hash tx)))
      (is (thrown? ExceptionInfo (empty tx)))
      (is (thrown? ExceptionInfo (conj tx []))))))

(deftest transacted-dissoc-missing-key-is-noop
  (let [table (todo-table)
        tx (kv/transacted table)
        tx2 (dissoc tx 999)]
    (is (identical? tx tx2))
    (let [commit (-> tx2
                     (assoc 1 {:id 1 :text "updated"})
                     (kv/tx-commit!))]
      (is (= #{1} (:keys-updated commit)))
      (is (= #{} (:keys-removed commit)))
      (is (= #{} (:keys-new commit)))))

  ;; dissoc of missing key followed by assoc of same key must count as :keys-new, not :keys-updated
  (let [commit (-> (kv/transacted (todo-table))
                   (dissoc 999)
                   (assoc 999 {:id 999 :text "fresh"})
                   (kv/tx-commit!))]
    (is (= #{999} (:keys-new commit)))
    (is (= #{} (:keys-updated commit)))
    (is (= #{} (:keys-removed commit)))))
