(ns shadow.arborist.wc-test
  (:require
    [goog.object :as gobj]
    [cljs.test :as ct :refer (deftest is)]
    [clojure.string :as str]
    [shadow.grove :as sg :refer (<<)]
    [shadow.arborist :as sa]
    [shadow.arborist.protocols :as ap]
    [shadow.arborist.attributes :as attr]
    ["./components.js"]))

;; some tests to check web component compatibility, similar to
;; https://github.com/webcomponents/custom-elements-everywhere
;; just written in CLJS

;; similar in structure to
;; https://github.com/webcomponents/custom-elements-everywhere/blob/master/libraries/preact/src/basic-tests.js
;; https://github.com/webcomponents/custom-elements-everywhere/blob/master/libraries/preact/src/advanced-tests.js

(defn ^:dev/before-load clear-console []
  (js/console.clear))

(defn with-test-node
  ([callback]
   (with-test-node {} callback))
  ([env callback]
   (let [node (js/document.createElement "div")]
     (js/document.body.append node)
     (callback node (sa/dom-root node env))
     (.remove node))))


;; FIXME: should these all use :dom/ref instead of looking at lastChild?

(deftest can-mount-without-children
  (with-test-node
    (fn [node root]
      (sa/update! root (<< [:ce-without-children]))
      (let [wc (.-lastChild node)]
        (is wc)
        ))))

(defn check-children [^js wc]
  (let [shadow-root (.-shadowRoot wc)
        h1 (.querySelector shadow-root "h1")
        p (.querySelector shadow-root "p")]

    (is h1)
    (is (= "Test h1" (.-textContent h1)))

    (is p)
    (is (= "Test p" (.-textContent p)))))

(deftest can-mount-with-children
  (with-test-node
    (fn [node root]
      (sa/update! root (<< [:ce-with-children]))
      (let [wc (.-lastChild node)]
        (is wc)
        (check-children wc)
        ))))

(deftest can-mount-with-children-and-slot
  (with-test-node
    (fn [node root]
      (sa/update! root (<< [:ce-with-children 1]))
      (let [wc (.-lastChild node)]
        (is wc)
        (check-children wc)

        ;; textContent doesn't include shadowRoot
        (is (= "1" (.-textContent wc))))

      (sa/update! root (<< [:ce-with-children 2]))
      (let [wc (.-lastChild node)]
        (is wc)
        (check-children wc)

        ;; textContent doesn't include shadowRoot
        (is (= "2" (.-textContent wc)))
        ))))

(deftest can-replace-with-regular-node
  (with-test-node
    (fn [node root]
      (sa/update! root (<< [:ce-with-children]))
      (let [wc (.-lastChild node)]
        (is wc)
        (check-children wc))

      ;; this test seems kinda pointless, pretty fundamental to replace things
      ;; nothing special regarding custom elements in this whatsoever
      (sa/update! root (<< [:div "Dummy view"]))

      (let [div (.-lastChild node)]
        (is div)
        (is (nil? (.-shadowRoot div)))
        ;; textContent doesn't include shadowRoot
        (is (= "Dummy view" (.-textContent div)))
        ))))

(deftest can-pass-properties
  (with-test-node
    (fn [node root]
      (sa/update! root
        (<< [:ce-with-properties
             {:bool true
              :num 42
              :str "shadow"
              ;; lol who wants arrays or objects
              :arr [1 2 3]
              :obj {:foo "bar"}}]))

      (let [^js wc (.-lastChild node)]
        (is wc)

        ;; https://github.com/webcomponents/custom-elements-everywhere/blob/83c186bffd3987cfd3442ba4dcfbc104b19f6614/libraries/preact/src/basic-tests.js#L104-L105
        ;; why is it ok for tests if these just work through .getAttribute?
        ;; seems like a pretty significant problem since .getAttribute/.setAttribute
        ;; turns vals into strings? maybe some old outdated stuff? maybe some spec fallback?
        (is (true? (.-bool wc)))
        (is (identical? 42 (.-num wc)))
        (is (identical? "shadow" (.-str wc)))
        (is (= [1 2 3] (.-arr wc)))
        (is (= {:foo "bar"} (.-obj wc)))
        ))))

(deftest can-handle-events
  (let [events-ref (atom #{})]

    (with-test-node
      ;; low level custom event handler, would usually use component
      ;; but we are testing fragments here not components
      {::ap/dom-event-handler
       (reify
         ap/IHandleDOMEvents
         (validate-dom-event-value! [this env event value]
           (assert (true? value)))

         (handle-dom-event! [this event-env event value dom-event]
           (swap! events-ref conj event)))}

      (fn [node root]
        (sa/update! root
          (<< [:ce-with-event
               {:on-lowercaseevent true
                :on-kebab-event true
                :on-camelEvent true
                :on-CAPSevent true
                :on-PascalEvent true}]))

        (let [^js wc (.-lastChild node)]
          (is wc)

          (.click wc)

          (is (= #{"lowercaseevent" "kebab-event" "camelEvent" "CAPSevent" "PascalEvent"}
                 @events-ref))
          )))))