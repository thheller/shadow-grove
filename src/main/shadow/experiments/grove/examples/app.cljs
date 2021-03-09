(ns shadow.experiments.grove.examples.app
  (:require
    [cljs.js :as cljs]
    [cljs.env :as cljs-env]
    ;; commonjs variant doesn't work
    ["prettier/esm/standalone.mjs$default" :as prettier]
    ["prettier/esm/parser-babel.mjs" :default prettier-babel]
    [shadow.resource :as rc]
    [shadow.cljs.bootstrap.browser :as boot]
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.arborist.fragments :as frag]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.events :as ev]
    [shadow.experiments.grove.local :as local-eng]
    [shadow.experiments.grove.examples.env :as env]
    [shadow.experiments.grove.examples.model :as m]
    [shadow.experiments.grove.examples.js-editor :as ed-js]
    [shadow.experiments.grove.examples.cljs-editor :as ed-cljs]
    ))

(defonce dom-root (js/document.getElementById "root"))

(defc ui-root []
  (bind {::m/keys [example-tab example-code example-js example-result] :as data}
    (sg/query-root
      [::m/example-code
       ::m/example-tab
       ::m/example-result
       ::m/example-js]))

  (render
    (<< [:div.inset-0.fixed.flex.flex-col
         [:div {:class "border-b border-t p-2"}
          [:div.text-xl.font-bold "shadow-grove Playground"]
          [:div.text-xs "ctrl+enter to eval"]]

         [:div {:class "flex flex-1 overflow-hidden"}
          [:div {:class "flex-1 text-sm"}
           (ed-cljs/editor {:value example-code
                            :submit-event {:e ::m/compile!}})]

          [:div {:class "flex-1 border-l-2 flex flex-col"}
           [:div.font-bold.py-4.border-b
            [:div.inline.p-4.cursor-pointer.border-r
             {:on-click {:e ::m/select-tab! :tab :result}}
             "Example Result"]
            [:div.inline.p-4.cursor-pointer.border-r
             {:on-click {:e ::m/select-tab! :tab :code}}
             "JS Code"]]

           ;; always embedding this to avoid unmounting/mounting examples when switching tabs
           ;; they might use local state which would get lost
           [:div {:class (str "p-2 flex-none" (when (not= example-tab :result) " hidden")) :style "height: 300px"}
            example-result]

           (when (= :code example-tab)
             (<< [:div {:class "flex-1 flex flex-col"}
                  [:div.flex-1.relative
                   ;; FIXME: CodeMirror style messes up flexbox here without this
                   [:div.absolute.inset-0
                    (ed-js/editor {:value example-js})]]
                  [:div {:class "border-b border-t p-4"}
                   "This is " [:span.font-bold "unoptimized"] " JS code. :advanced optimizations will shrink this significantly. shadow-cljs output is also slighty more optimized than self-hosted"]]))]
          ]]))

  ;; FIXME: should need to declare these, should just have a default handler
  (event ::m/compile! sg/tx)
  (event ::m/select-tab! sg/tx))

(def source-ref (atom ""))

(def code (rc/inline "./basic_example.cljs"))

(ev/reg-event env/rt-ref ::m/select-tab!
  (fn [{:keys [db] :as env} {:keys [tab] :as e}]
    {:db (assoc db ::m/example-tab tab)}))

(ev/reg-event env/rt-ref ::m/compile-result!
  (fn [{:keys [db] :as env} {:keys [source result]}]
    {:db (assoc db
           ::m/example-js source
           ;; FIXME: this isn't data strictly speaking, maybe put somewhere else?
           ::m/example-result result)}))

(defn handle-compile-result
  [{:keys [transact!] :as env}
   source-ref
   {:keys [error value] :as result}]

  ;; (js/console.log "result" result env)

  ;; FIXME: handle error

  ;; code that is not compiled by shadow-cljs directly relies on fragment registry
  ;; so we need to reset this, otherwise fragments aren't replaceable
  ;; only needed to filter out fragments compiled by self-hosted code
  (frag/reset-known-fragments!)

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

          ;; (js/console.log "handle-compile-result" env result)
          (transact!
            {:e ::m/compile-result!
             :source formatted-source
             :result render-result})
          )))))

(ev/reg-event env/rt-ref ::m/compile!
  (fn [{:keys [db] :as env} {:keys [code] :as e}]
    {:db (assoc db ::m/example-code code
                   ::m/example-result "Compiling ...")
     :cljs-compile code}))

(ev/reg-fx env/rt-ref :cljs-compile
  (fn [env code]
    (let [source-ref (atom "")
          compile-state-ref (::m/compile-state-ref env)]

      ;; FIXME: fx me
      (cljs/eval-str
        (::m/compile-state-ref env)
        code
        "[test]"
        {:eval
         (fn js-eval
           [{:keys [source] :as resource}]
           ;; (js/console.log "eval" resource)
           ;; global eval
           (when (seq source)
             ;; can't find a different way to access the generated JS code?
             (swap! source-ref str source)
             (js* "(0,eval)(~{});" source)))

         :load (partial boot/load compile-state-ref)
         ;; code looks pretty terrible since it doesn't have the shadow-cljs
         ;; tweaks for ifn/function invoke or analyze-top
         ;; looks even worse without static-fns
         :static-fns true}

        #(handle-compile-result env source-ref %)))))

(def example-source (rc/inline "./basic_example.cljs"))

(defn ^:dev/after-load start []
  (sg/app-tx ::app {:e ::m/compile! :code example-source})

  (sg/start ::app dom-root (ui-root)))

(defn init []
  (sg/init ::app
    {}
    [(local-eng/init env/rt-ref)])

  (boot/init (::m/compile-state-ref @env/rt-ref)
    {:path "bootstrap"}
    start))
