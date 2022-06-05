(ns shadow.grove.db-test
  (:require
    [shadow.grove.db :as db]
    [clojure.pprint :refer (pprint)]
    [clojure.test :as t :refer (deftest is)]))

(def schema
  (db/configure
    {:a
     {:type :entity
      :attrs {:a-id [:primary-key number?]
              :many [:many :b]
              :single [:one :b]}}

     :b
     {:type :entity
      :attrs {:b-id [:primary-key number?]
              :c :c}}

     :c
     {:type :entity
      :attrs {:c-id [:primary-key number?]}}}))

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
        (with-meta
          {:foo "bar"}
          {::db/schema schema})

        after
        (db/merge-seq before :a sample-data [::foo])]

    (pprint after)


    (pprint (db/query {} after [{:a [:a-value]}]))
    ))
