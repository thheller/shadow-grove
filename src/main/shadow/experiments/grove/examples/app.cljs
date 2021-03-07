(ns shadow.experiments.grove.examples.app
  (:require
    [cljs.js :as cljs]
    [cljs.env :as env]
    ;; commonjs variant doesn't work
    ["prettier/esm/standalone.mjs$default" :as prettier]
    ["prettier/esm/parser-babel.mjs" :default prettier-babel]
    [shadow.resource :as rc]
    [shadow.cljs.bootstrap.browser :as boot]
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.arborist.fragments :as frag]
    [shadow.experiments.grove :as sg :refer (<< defc)]))

(defonce compile-state-ref (env/default-compiler-env))

(defonce dom-root (js/document.getElementById "root"))

(def source-ref (atom ""))

(defn handle-result [{:keys [error value] :as result}]
  (js/console.log "result" result)

  ;; FIXME: handle error

  (when (fn? value)
    ;; calling this directly makes stacktraces unusable since its somewhere deep
    ;; in compiler internals for some reason, just delaying with timeout fixes that
    (js/setTimeout
      (fn []
        (let [formatted-source
              (prettier/format
                @source-ref
                #js {:parser "babel"
                     :plugins #js [prettier-babel]})

              render-result
              (value)]

          (sg/start ::app dom-root
            (<< [:div.flex
                 [:pre.flex-1.text-xs formatted-source]
                 [:div.flex-1 render-result]]
                )))))))

(def code (rc/inline "./basic_example.cljs"))

(defn js-eval
  [{:keys [source] :as resource}]
  (js/console.log "eval" resource)
  ;; global eval
  (when (seq source)
    (swap! source-ref str source)
    (js* "(0,eval)(~{});" source)))

(defn ^:dev/after-load start []
  ;; code that is not compiled by shadow-cljs directly relies on fragment registry
  ;; so we need to reset this, otherwise fragments aren't replaceable
  ;; only needed to filter out fragments compiled by self-hosted code
  (frag/reset-known-fragments!)

  (reset! source-ref "")

  (cljs/eval-str
    compile-state-ref
    code
    "[test]"
    {:eval js-eval
     :load (partial boot/load compile-state-ref)
     ;; code looks pretty terrible since it doesn't have the shadow-cljs
     ;; tweaks for ifn/function invoke or analyze-top
     ;; looks even worse without static-fns
     :static-fns true}

    handle-result))

(defn init []
  (sg/init ::app {} [])

  (boot/init compile-state-ref
    {:path "/bootstrap"}
    start))
