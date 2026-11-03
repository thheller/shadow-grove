(ns shadow.grove.components-test
  (:require
    ["jsdom" :refer (JSDOM)]
    [cljs.test :as ct :refer (deftest is async)]
    [shadow.grove :as sg :refer (defc <<)]
    [shadow.grove.protocols :as gp]
    [shadow.grove.runtime :as rt]))

(defn make-env [rt-id]
  (let [dom (JSDOM. "<!DOCTYPE html>")
        doc (-> dom .-window .-document)]

    ;; same hack as grove-test, document is used directly in a few places
    (set! js/global -document doc)

    {:root (.-body doc)
     :rt-ref (sg/get-runtime rt-id)}))

(defn wait []
  (js/Promise. (fn [resolve] (js/setTimeout resolve 10))))

(defn run-steps
  "runs each step fn, waiting for scheduled updates to finish in between"
  [done steps]
  (-> (reduce
        (fn [p step]
          (.then p (fn [] (step) (wait))))
        (js/Promise.resolve nil)
        steps)
      (.catch (fn [e]
                (js/console.error e)
                (is (nil? e) "step failed")))
      (.then done)))

(defn take-log! [log-ref]
  (let [log @log-ref]
    (reset! log-ref [])
    log))

(defc ui-bind-as [state-ref log-ref]
  (bind {:keys [a b] :as all}
    (sg/watch state-ref))
  (bind a-out
    (swap! log-ref conj :a)
    (str "a=" a))
  (bind b-out
    (swap! log-ref conj :b)
    (str "b=" b))
  (bind all-out
    (swap! log-ref conj :all)
    (count all))
  (render
    (swap! log-ref conj :render)
    (str a-out " " b-out " " all-out)))

(deftest test-bind-destructure-as
  (let [{:keys [root rt-ref]} (make-env ::bind-as)
        state-ref (atom {:a 1 :b 2})
        log-ref (atom [])]

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-bind-as state-ref log-ref))
           (is (= [:a :b :all :render] (take-log! log-ref)))
           (is (= "a=1 b=2 2" (.-textContent root))))

         ;; unrelated key changes, only things using the :as value run
         (fn [] (swap! state-ref assoc :c 3))
         (fn []
           (is (= [:all :render] (take-log! log-ref)))
           (is (= "a=1 b=2 3" (.-textContent root))))

         ;; a changes, b must not run. all is marked changed without comparing
         (fn [] (swap! state-ref assoc :a 10))
         (fn []
           (is (= [:a :all :render] (take-log! log-ref)))
           (is (= "a=10 b=2 3" (.-textContent root))))

         ;; b changes, a must not run
         (fn [] (swap! state-ref assoc :b 20))
         (fn []
           (is (= [:b :all :render] (take-log! log-ref)))
           (is (= "a=10 b=20 3" (.-textContent root))))]))))

(defc ui-bind-no-as [state-ref log-ref]
  (bind {:keys [a]}
    (sg/watch state-ref))
  (bind a-out
    (swap! log-ref conj :a)
    (str "a=" a))
  (render
    (swap! log-ref conj :render)
    a-out))

(deftest test-bind-destructure-no-as
  (let [{:keys [root rt-ref]} (make-env ::bind-no-as)
        state-ref (atom {:a 1})
        log-ref (atom [])]

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-bind-no-as state-ref log-ref))
           (is (= [:a :render] (take-log! log-ref)))
           (is (= "a=1" (.-textContent root))))

         ;; nothing uses the full value, so an unrelated change stops right there
         (fn [] (swap! state-ref assoc :c 3))
         (fn []
           (is (= [] (take-log! log-ref)))
           (is (= "a=1" (.-textContent root))))

         (fn [] (swap! state-ref assoc :a 2))
         (fn []
           (is (= [:a :render] (take-log! log-ref)))
           (is (= "a=2" (.-textContent root))))]))))

(defc ui-arg-destructure [{:keys [x y] :as m} log-ref]
  (bind x-out
    (swap! log-ref conj :x)
    (str "x=" x))
  (bind y-out
    (swap! log-ref conj :y)
    (str "y=" y))
  (render
    (str x-out " " y-out " " (count m))))

(deftest test-arg-destructure
  (let [{:keys [root rt-ref]} (make-env ::arg-destructure)
        log-ref (atom [])]

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-arg-destructure {:x 1 :y 2} log-ref))
           (is (= [:x :y] (take-log! log-ref)))
           (is (= "x=1 y=2 2" (.-textContent root))))

         (fn []
           (sg/render rt-ref root (ui-arg-destructure {:x 1 :y 3} log-ref))
           (is (= [:y] (take-log! log-ref)))
           (is (= "x=1 y=3 2" (.-textContent root))))

         (fn []
           (sg/render rt-ref root (ui-arg-destructure {:x 1 :y 3 :z 4} log-ref))
           (is (= [] (take-log! log-ref)))
           (is (= "x=1 y=3 3" (.-textContent root))))]))))

(defc ui-misc-destructure [state-ref fallback]
  ;; vector, nested map, :or using an arg, & rest
  (bind [first-item {:keys [deep] :or {deep fallback}} & more]
    (sg/watch state-ref))
  (render
    (str first-item "/" deep "/" (pr-str more))))

(deftest test-misc-destructure
  (let [{:keys [root rt-ref]} (make-env ::misc-destructure)
        state-ref (atom [1 {} 3 4])]

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-misc-destructure state-ref "fb"))
           (is (= "1/fb/(3 4)" (.-textContent root))))

         ;; :or default uses the arg, so arg change must re-run the destructure
         (fn []
           (sg/render rt-ref root (ui-misc-destructure state-ref "fb2"))
           (is (= "1/fb2/(3 4)" (.-textContent root))))

         (fn [] (reset! state-ref [1 {:deep "d"} 3]))
         (fn []
           (is (= "1/d/(3)" (.-textContent root))))

         (fn [] (reset! state-ref [1 {:deep "d"}]))
         (fn []
           (is (= "1/d/nil" (.-textContent root))))]))))

(defn use-pushed-value [ref-holder init]
  (let [ref (rt/claim-slot! ::pushed-value)]
    (vreset! ref-holder ref)
    init))

(defc ui-provide-new-value [ref-holder log-ref]
  (bind {:keys [a b]}
    (use-pushed-value ref-holder {:a 1 :b 2}))
  (bind a-out
    (swap! log-ref conj :a)
    (str "a=" a))
  (bind b-out
    (swap! log-ref conj :b)
    (str "b=" b))
  (render
    (str a-out " " b-out)))

(deftest test-provide-new-value
  (let [{:keys [root rt-ref]} (make-env ::provide-new-value)
        ref-holder (volatile! nil)
        log-ref (atom [])]

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-provide-new-value ref-holder log-ref))
           (is (= [:a :b] (take-log! log-ref)))
           (is (= "a=1 b=2" (.-textContent root))))

         ;; value pushed without running the slot must still be destructured
         (fn [] (gp/provide-new-value! @ref-holder {:a 1 :b 5}))
         (fn []
           (is (= [:b] (take-log! log-ref)))
           (is (= "a=1 b=5" (.-textContent root))))]))))

(defn seed-args [^clj seed]
  (.-args seed))

(defc ui-arg-keys [{:keys [foo]}]
  (render (str foo)))

(defc ui-arg-keys-as [{:keys [foo] :as m}]
  (render (str foo (count m))))

(deftest test-arg-destructure-on-call
  ;; destructured when called, the seed only holds what the component binds
  (is (= [1] (seed-args (ui-arg-keys {:foo 1}))))
  ;; so keys it doesn't bind don't make seeds differ
  (is (= (ui-arg-keys {:foo 1}) (ui-arg-keys {:foo 1 :bar 2})))
  (is (not= (ui-arg-keys {:foo 1}) (ui-arg-keys {:foo 2})))

  ;; :as keeps the map
  (is (= #{1 {:foo 1}} (set (seed-args (ui-arg-keys-as {:foo 1})))))
  (is (not= (ui-arg-keys-as {:foo 1}) (ui-arg-keys-as {:foo 1 :bar 2}))))

;; stand-in for :keys! until the compiler supports it
;; a vector destructure of something not sequential throws too
(defc ui-arg-throws [[a b]]
  (render (str a b)))

(deftest test-arg-destructure-throws-on-call
  (is (= [1 2] (seed-args (ui-arg-throws [1 2]))))
  ;; throws in the caller, without anything mounting
  (is (thrown? js/Error (ui-arg-throws 1))))

(defc ui-arg-rest [a & more]
  (render (str a (pr-str more))))

(deftest test-arg-rest
  (is (= [1 [2 3]] (seed-args (ui-arg-rest 1 2 3))))
  (is (= [1 nil] (seed-args (ui-arg-rest 1))))
  (is (thrown? js/Error (ui-arg-rest))))

(defc ui-arg-stable [{:keys [^:stable id label]} log-ref]
  (effect :mount [env]
    (swap! log-ref conj [:mount id]))
  (render (str id ":" label)))

(deftest test-arg-destructure-stable
  (let [{:keys [root rt-ref]} (make-env ::arg-stable)
        log-ref (atom [])]

    (async done
      (run-steps done
        [(fn []
           (sg/render rt-ref root (ui-arg-stable {:id 1 :label "a"} log-ref))
           (is (= "1:a" (.-textContent root))))

         (fn []
           (is (= [[:mount 1]] (take-log! log-ref)))
           ;; label is not stable, updates in place
           (sg/render rt-ref root (ui-arg-stable {:id 1 :label "b"} log-ref))
           (is (= "1:b" (.-textContent root))))

         (fn []
           (is (= [] (take-log! log-ref)))
           ;; id is, remounts
           (sg/render rt-ref root (ui-arg-stable {:id 2 :label "b"} log-ref))
           (is (= "2:b" (.-textContent root))))

         (fn []
           (is (= [[:mount 2]] (take-log! log-ref))))]))))
