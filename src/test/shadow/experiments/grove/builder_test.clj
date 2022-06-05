(ns shadow.grove.builder-test
  (:require
    [clojure.test :as t :refer (deftest is)]
    [clojure.pprint :refer (pprint)]
    [shadow.grove.builder :as b]))

(def test-body
  '[[:foo {:i/key :key :bar 1 :foo foo :x nil :bool true}]
    "hello"
    [:div#id
     (dynamic-thing {:x 1})
     (if (even? 1)
       (<< [:h1 "even"])
       (<< [:h2 "odd"]))
     [:h1 "hello, " title]
     (let [x 1]
       (<< [:h2 x]))]
    1
    (some-fn 1 2)])

(deftest test-macro-expand
  (pprint (b/compile
            {:skip-check false}
            {:line 1 :column 1 :ns {:name 'cljs}}
            test-body)))
