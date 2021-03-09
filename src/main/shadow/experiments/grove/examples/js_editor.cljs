(ns shadow.experiments.grove.examples.js-editor
  (:require
    ["codemirror" :as cm]
    ["codemirror/mode/javascript/javascript"]
    [clojure.string :as str]
    [shadow.experiments.arborist.protocols :as ap]
    [shadow.experiments.arborist.common :as common]
    [shadow.experiments.arborist.dom-scheduler :as ds]
    [shadow.experiments.grove :as sg]
    [shadow.experiments.grove.components :as comp]
    [shadow.experiments.grove.protocols :as gp]))

(deftype EditorRoot
  [env
   marker
   ^:mutable opts
   ^:mutable editor
   ^:mutable editor-el]

  ap/IManaged
  (supports? [this next]
    (ap/identical-creator? opts next))

  (dom-sync! [this next-opts]
    (let [{:keys [value cm-opts]} next-opts]

      (when (and editor (seq value))
        (.setValue editor value))

      (reduce-kv
        (fn [_ key val]
          (.setOption editor (name key) val))
        nil
        cm-opts)

      (set! opts next-opts)
      ))

  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor))

  (dom-first [this]
    (or editor-el marker))

  ;; codemirror doesn't render correctly if added to an element
  ;; that isn't actually in the dcoument, so we delay construction until actually entered
  ;; codemirror also does a bunch of force layouts/render when mounting
  ;; which kill performance quite badly
  (dom-entered! [this]
    (ds/write!
      (let [{:keys [value]}
            opts

            ;; FIXME: this config stuff needs to be cleaned up, this is horrible
            cm-opts
            (js/Object.assign
              #js {:lineNumbers true
                   :theme "github"
                   :mode "javascript"
                   :matchBrackets true
                   :readOnly true
                   :autofocus false}
              (when (seq value)
                #js {:value value}))

            ed
            (cm.
              (fn [el]
                (set! editor-el el)
                (.insertBefore (.-parentElement marker) el marker))
              cm-opts)]

        (set! editor ed))))

  (destroy! [this dom-remove?]
    (when dom-remove?
      (when editor-el
        ;; FIXME: can't find a dispose method on codemirror?
        (.remove editor-el))
      (.remove marker))))

(defn make-editor [opts env]
  (EditorRoot. env (common/dom-marker env) opts nil nil))

(defn editor [opts]
  (with-meta opts {`ap/as-managed make-editor}))
