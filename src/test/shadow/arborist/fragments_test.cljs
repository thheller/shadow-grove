(ns shadow.arborist.fragments-test
  (:require
    ["jsdom" :refer (JSDOM)]
    [cljs.test :as ct :refer (deftest is)]
    [shadow.arborist.attributes :as sa]
    [shadow.grove :as sg :refer (<<)]
    [shadow.grove.db :as db]))

(deftest test-jsdom
  (let [dom (JSDOM. "<!DOCTYPE html>")
        doc (-> dom .-window .-document)
        root (.-body doc)

        data-ref
        (-> {}
            (db/configure {})
            (atom))

        rt-ref
        (-> {}
            (sg/prepare data-ref :test))]

    ;; hack until I decide whether document should come from env?
    ;; used in a few places and currently always js/document
    (set! js/global -document doc)

    (sa/add-attr ::test
      (fn [env node oval nval]
        (swap! rt-ref assoc :oval oval :nval nval)))

    (let [test-frag
          (fn [val]
            (<< [:div {::test val} val]))]

      (sg/render rt-ref root (test-frag "foo"))
      (is (= "<div>foo</div>" (.-innerHTML root)))

      (is (= "foo" (:nval @rt-ref)))
      (is (= nil (:oval @rt-ref)))

      (sg/render rt-ref root (test-frag "bar"))
      (is (= "<div>bar</div>" (.-innerHTML root)))

      (is (= "bar" (:nval @rt-ref)))
      (is (= "foo" (:oval @rt-ref)))

      ;; new fragment, unmounts previous, also must clean up old custom ::test attr
      (sg/render rt-ref root (<< [:h1 "hello world"]))
      (is (= "<h1>hello world</h1>" (.-innerHTML root)))

      (is (= nil (:nval @rt-ref)))
      (is (= "bar" (:oval @rt-ref)))
      )))

(comment
  (test-jsdom))