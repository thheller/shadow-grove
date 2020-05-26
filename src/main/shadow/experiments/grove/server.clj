(ns shadow.experiments.grove.server
  (:require [clojure.spec.alpha :as s])
  (:import [java.io StringWriter]))

(s/def ::defc-args
  (s/cat
    :comp-name simple-symbol?
    :docstring (s/? string?)
    :opts (s/? map?)
    :bindings vector? ;; FIXME: core.specs for destructure help
    :hook-bindings vector?
    :body (s/* any?)))

(s/fdef defc :args ::defc-args)

(defn hook-process [env result]
  result)

(defprotocol ServerRenderable
  (html-gen [this env writer]))

(deftype ServerComponent [render-fn]
  ServerRenderable
  (html-gen [this env writer]
    (let [result (render-fn env)]
      (html-gen result env writer))))

(extend-protocol ServerRenderable
  java.lang.String
  (html-gen [this env writer]
    ;; FIXME: html encode
    (.write writer this))

  java.lang.Number
  (html-gen [this env writer]
    (.write writer (str this)))

  nil
  (html-gen [this env writer])

  clojure.lang.PersistentVector
  (html-gen [this env writer]
    (let [kw (nth this 0)
          props (nth this 1)
          children (subvec this 2)]
      (.write writer (str "<" (name kw)))
      (reduce-kv
        (fn [_ k v]
          ;; FIXME: properly encode k/v
          (.write writer (str " " (name k) "=\"" v "\"")))
        nil
        props)
      (.write writer ">")
      (run! #(html-gen % env writer) children)
      (.write writer (str "</" (name kw) ">")))))

(defmacro defc [& args]
  (let [{:keys [comp-name bindings hook-bindings opts body]}
        (s/conform ::defc-args args)

        env-sym (gensym "env")]

    `(defn ~comp-name ~bindings
       (->ServerComponent
         (fn [~env-sym]
           (let [~@(->> hook-bindings
                        (partition-all 2)
                        (mapcat
                          (fn [[binding init]]
                            [binding `(hook-process ~env-sym ~init)])))]
             ~@body))))))

(deftype Fragment [items]
  ServerRenderable
  (html-gen [this env writer]
    (run! #(html-gen % env writer) items)))

(defn << [& items]
  (->Fragment items))

(clojure.pprint/pprint
  (macroexpand-1
    '(defc test-comp [a b]
       [c (+ a b)]
       :body)))

(defc nested [thing]
  []
  [:div {:class "nested"} thing])

(defc dummy [foo bar]
  []
  (<< "before" 1 2 3
      [:div {:id "some-id"}
       [:h1 {} foo]
       [:h2 {} bar]
       (nested "nested")]
      "after"))

(let [sw (StringWriter.)]
  (html-gen (dummy "foo" "bar") {} sw)
  (println (str sw)))


