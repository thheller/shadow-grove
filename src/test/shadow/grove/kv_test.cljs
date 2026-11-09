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
