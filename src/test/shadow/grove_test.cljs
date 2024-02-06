(ns shadow.grove-test
  (:require
    ["jsdom" :refer (JSDOM)]
    [cljs.test :as ct :refer (deftest is async)]
    [shadow.cljs.modern :refer (js-await)]
    [shadow.arborist.attributes :as sa]
    [shadow.grove :as sg :refer (defc <<)]
    [shadow.grove.db :as db]
    [shadow.grove.runtime :as rt]))


;; how the hell does anyone debug exceptions thrown in deftest

(defmethod ct/report [::ct/default :error] [{:keys [actual] :as m}]
  ;; gimme the stack already, just the message is not enough
  (println (.-stack actual))
  (ct/inc-report-counter! :error)
  (println "\nERROR in" (ct/testing-vars-str m))
  (when (seq (:testing-contexts (ct/get-current-env)))
    (println (ct/testing-contexts-str)))
  (when-let [message (:message m)] (println message))
  (ct/print-comparison m))

(set! cljs.core/missing-protocol
  (fn missing-protocol [proto obj]
    ;; tap, since "No protocol method ... for type [object Object]" is not exactly helpful.
    (tap> [:missing-protocol obj proto])
    (let [ty (type obj)
          ty (if (and ty (.-cljs$lang$type ty))
               (.-cljs$lang$ctorStr ty)
               (goog/typeOf obj))]
      (js/Error.
        (.join (array "No protocol method " proto
                 " defined for type " ty ": " obj) "")))))

(deftest test-basic-fragment-ops
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

(defc ui-dummy [state-ref order-ref]
  (hook (swap! order-ref conj :slot-1))
  (bind x (sg/watch state-ref))
  (bind y
    (swap! order-ref conj :slot-y)
    (str x "y"))
  (render y))


(deftest test-component-lifecycle
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

    (let [state-ref (atom "foo")
          order-ref (atom [])]

      (sg/render rt-ref root (ui-dummy state-ref order-ref))
      (is (= [:slot-1 :slot-y] @order-ref))

      ;; not using innerHTML since dev builds leave a component boundary comment
      (is (= "fooy" (.-textContent root)))

      ;; dom update is scheduled for next-tick to batch updates
      (reset! state-ref "bar")

      (async done
        (js-await [_ rt/ticker]
          ;; first slot untouched, does not run again
          (is (= [:slot-1 :slot-y :slot-y] @order-ref))
          (is (= "bary" (.-textContent root)))

          (let [before @order-ref]

            ;; render component again from root, same args
            (sg/render rt-ref root (ui-dummy state-ref order-ref))

            (is (= "bary" (.-textContent root)))

            ;; slots should not have run again
            (is (= before @order-ref))

            (let [test-ref (atom [])
                  before @order-ref]

              (sg/render rt-ref root (ui-dummy state-ref test-ref))

              ;; prev arg untouched
              (is (= before @order-ref))

              ;; since new arg, also running y again
              (is (= [:slot-1 :slot-y] @test-ref))

              (done)))))
      )))



(comment
  (test-jsdom))