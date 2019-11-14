(ns shadow.experiments.arborist-test
  (:require
    [clojure.test :as t :refer (deftest is)]
    [clojure.pprint :refer (pprint)]
    [clojure.string :as str]
    [shadow.experiments.arborist.fragments :as frag]))

(def test-body
  '[[:foo {:i/key :key :bar 1 :foo foo :x nil :bool true}]
    "hello"
    [:div#id
     (dynamic-thing {:x 1})
     (if (even? 1)
       (<< [:h1 "even"])
       (<< [:h2 "odd"]))
     [:h1 "hello, " title ", " foo]
     (let [x 1]
       (<< [:h2 x]))]
    1
    (some-fn 1 2)])

(def test-body
  #_'[[:div.card {:style {:color "red"} :foo ["xyz" "foo" "bar"] :attr toggle}
       [:div.card-header title]
       [:div.card-body {:on-click ^:once [::foo {:bar yo}] :attr "foo"} "Hello"]]]

  '[[:div.card
     [:div.card-title title]
     [:div.card-body {:foo "bar"} body]
     [bar bar-child-can-change]
     [foo {:bar 1}
      [:foo.child "foo.child"]]
     [:div.card-actions
      [:button "ok"]]]]
  #_[[:div x]
     [:> component {:foo "bar"} [:c1 [:c2 {:x x}] y] [:c3]]])


(deftest test-macro-expand
  (pprint (frag/make-fragment {} test-body)))

