(ns shadow.grove.db-test
  (:require
    [shadow.grove.db :as db]
    [clojure.pprint :refer (pprint)]
    [clojure.test :as t :refer (deftest is)]))

(def schema
  {:a
   {:type :entity
    :primary-key :a-id
    :joins {:many [:many :b]
            :single [:one :b]}}

   :b
   {:type :entity
    :primary-key :b-id
    :joins {:c [:one :c]}}

   :c
   {:type :entity
    :primary-key :c-id}})

(def sample-data
  [{:a-id 1
    :a-value "a"
    :many [{:b-id 1
            :b-value "b"
            :c {:c-id 1 :c true}}
           {:b-id 2
            :b-value "c"}]
    :single {:b-id 1
             :b-value "x"}}])

(deftest building-a-db-normalizer
  (let [before
        (db/configure schema)

        after
        (db/merge-seq before :a sample-data [::foo])]

    (tap> @after)


    ))


(deftest tx-test
  (-> (db/configure
        {}
        {:thing
         {:type :entity
          :primary-key :id}})
      (db/tx-begin)
      (db/merge-seq :thing [{:id 1 :text "foo"}] [:things])
      (db/tx-commit!)
      (tap>)
      ))

(deftest add-test
  (let [schema
        {:foo
         {:type :entity
          :primary-key :foo-id
          :joins {:bar [:one :bar]
                  :baz [:one :baz]}}
         :bar
         {:type :entity
          :primary-key :bar-id}}

        db
        (db/configure {} schema)]

    (-> (db/transacted db)
        (db/add :foo {:foo-id 1 :foo "foo" :bar {:bar-id 1 :bar "bar"}})
        (db/tx-commit!)
        (get :data)
        (tap>))))

(deftest tx-simple-add-test
  (let [schema
        {}

        db
        (db/configure {} schema)

        {:keys [keys-updated keys-new keys-removed data] :as tx}
        (-> (db/transacted db)
            (assoc :foo "bar")
            (db/tx-commit!))]

    (is (= "bar" (get data :foo)))
    (is (= #{:foo} keys-new))
    (is (= #{} keys-removed))
    (is (= #{} keys-updated))
    ))

(deftest tx-simple-remove-test
  (let [schema
        {}

        db
        (db/configure {:foo "bar"} schema)

        {:keys [keys-updated keys-new keys-removed data] :as tx}
        (-> (db/transacted db)
            (dissoc :foo)
            (db/tx-commit!))]

    (is (= nil (get data :foo)))
    (is (= #{} keys-new))
    (is (= #{:foo} keys-removed))
    (is (= #{} keys-updated))
    ))

(deftest tx-ident-add-test
  (let [schema
        {:foo
         {:type :entity
          :primary-key :foo-id
          :joins {}}}

        ident
        (db/make-ident :foo 1)

        db
        (db/configure {} schema)

        {:keys [keys-updated keys-new keys-removed data] :as tx}
        (-> (db/transacted db)
            (assoc ident {:foo "bar"})
            (db/tx-commit!))]

    (is (= {:foo "bar"} (get data ident)))
    (is (= #{ident} keys-new))
    (is (= #{} keys-removed))
    (is (= #{[::db/all :foo]} keys-updated))
    ))

(deftest tx-ident-remove-test
  (let [schema
        {:foo
         {:type :entity
          :primary-key :foo-id
          :joins {}}}

        ident
        (db/make-ident :foo 1)

        db
        (-> (db/configure {} schema)
            (db/transacted)
            (assoc ident {:foo "bar"})
            (db/tx-commit!)
            (get :db))

        {:keys [keys-updated keys-new keys-removed data] :as tx}
        (-> (db/transacted db)
            (dissoc ident)
            (db/tx-commit!))]

    (is (= nil (get data ident)))
    (is (= #{} keys-new))
    (is (= #{ident} keys-removed))
    (is (= #{[::db/all :foo]} keys-updated))
    ))

(deftest tx-ident-update-test
  (let [schema
        {:foo
         {:type :entity
          :primary-key :foo-id
          :joins {}}}

        ident
        (db/make-ident :foo 1)

        db
        (-> (db/configure {} schema)
            (db/transacted)
            (assoc ident {:foo "bar"})
            (db/tx-commit!)
            (get :db))

        {:keys [keys-updated keys-new keys-removed data] :as tx}
        (-> (db/transacted db)
            (update ident assoc :foo "baz")
            (db/tx-commit!))]

    (is (= {:foo "baz"} (get data ident)))
    (is (= #{} keys-new))
    (is (= #{} keys-removed))
    (is (= #{ident} keys-updated))
    ))