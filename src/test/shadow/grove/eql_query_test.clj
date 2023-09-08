(ns shadow.grove.eql-query-test
  (:require
    [clojure.test :refer (deftest is)]
    [clojure.pprint :refer (pprint)]
    [shadow.grove.db :as db]
    [shadow.grove.eql-query :as eql]))


(deftest test-query-kw-lookup
  (let [env {}
        db {:foo 1 :bar 1 :x 1}
        res (eql/query env db
              [:foo
               :bar])]

    (is (= {:foo 1 :bar 1} res))))


(deftest test-query-kw-join
  (let [env {}
        db {:foo {:a 1 :b 1} :bar 1}
        res (eql/query env db
              [{:foo
                [:a]}])]

    (is (= {:foo {:a 1}} res))))

(deftest test-query-kw-join-ident
  (let [env {}
        ident (db/make-ident :a 1)
        db {:foo ident
            ident {:a 1 :b 1}
            :bar 1}
        res (eql/query env db
              [{:foo
                [:a]}])]

    (is (= {:foo {:a 1}} res))))

(deftest test-query-list-join-ident
  (let [env {}
        ident (db/make-ident :a 1)
        db {:foo ident
            ident {:a 1 :b 1}
            :bar 1}
        res (eql/query env db
              '[{(:foo {:bar 1})
                 [:a]}])]

    (is (= {:foo {:a 1}} res))))

(deftest test-query-join-ident
  (let [env {}
        ident (db/make-ident :a 1)
        db {:foo ident
            ident {:a 1 :b 1}
            :bar 1}
        res (eql/query env db
              [{ident
                [:a]}])]

    (is (= {ident {:a 1}} res))))

(deftest test-query-trace
  (let [env {}
        ident (db/make-ident :a 1)
        db {:foo ident
            ident {:a 1 :b 1}
            :bar 1}
        res (eql/query env db
              [{ident
                [:a
                 :db/trace]}])]

    (pprint res)))

(deftest test-query-join-ident-not-found
  (let [env {}
        ident (db/make-ident :a 1)
        db {:foo ident
            :bar 1}
        res (eql/query env db
              [{ident
                [:a]}])]

    (is (= {ident {:db/ident ident :db/not-found true}} res))))

(deftest test-query-coll-ident-not-found
  (let [env {}
        ident (db/make-ident :a 1)
        db {}
        current {:foo [ident]}
        res (eql/query env db current
              [{:foo [:a]}])]

    (is (= {:foo [{:db/ident ident :db/not-found true}]} res))))

(deftest test-query-coll-ident-found
  (let [env {}
        ident (db/make-ident :a 1)
        db {ident {:a 1}}
        current {:foo [ident]}
        res (eql/query env db current
              [{:foo [:a]}])]

    (is (= {:foo [{:a 1}]} res))))