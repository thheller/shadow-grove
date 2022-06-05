(ns shadow.grove.html-test
  (:require
    [clojure.test :refer (deftest is)]
    [clojure.pprint :refer (pprint)]
    [shadow.grove.html :as html :refer (<<)]))

(defn dynamic-thing [thing] thing)

(deftest test-write-str
  (let [foo "fo\"XSS\"o"
        title "wo<script>alert(\"boom\");\"</script>rld"
        some-fn (fn [& body] body)
        test-fn
        (fn [attrs]
          (<< [:foo {:i/key 1 :bar 1 :foo foo :x nil :bool true}]
              "hello"
              [:div#id.xxx (dynamic-thing {:foo "bar"})
               [:h1 "hello, " title]
               attrs
               (when (:nested attrs)
                 (<< [:h2 "nested fragment"]
                     "foo"
                     [:still "nested"]))]
              1
              (some-fn 1 2)))]

    (println (test-fn {:yo "attrs"}))
    (println)
    (println (test-fn {:nested true}))

    (println (html/str [:div#hello.world "yo"]))
    ))
