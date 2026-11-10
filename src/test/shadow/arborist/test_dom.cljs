(ns shadow.arborist.test-dom
  "one jsdom window shared by tests that need DOM globals at load time,
   e.g. web component classes that extend HTMLElement and call customElements.define"
  (:require ["jsdom" :refer (JSDOM)]))

(defonce dom (JSDOM. "<!DOCTYPE html>"))

(def ^js window (.-window dom))

;; must exist before components.js loads, which is why this ns is required first
(set! js/global -HTMLElement (.-HTMLElement window))
(set! js/global -customElements (.-customElements window))
(set! js/global -CustomEvent (.-CustomEvent window))

(defn use-document!
  "other tests replace js/document with their own jsdom document,
   so tests using this window must put it back before touching the DOM"
  []
  (set! js/global -document (.-document window)))
