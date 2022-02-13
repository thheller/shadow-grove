(ns shadow.experiments.grove.examples.app
  (:require
    [goog.object :as gobj]
    [cljs.js :as cljs]
    [cljs.compiler :as comp]
    [cljs.env :as cljs-env]
    [clojure.string :as str]
    ;; commonjs variant doesn't work
    ["prettier/esm/standalone.mjs$default" :as prettier]
    ["prettier/esm/parser-babel.mjs" :default prettier-babel]
    [shadow.resource :as rc]
    [shadow.cljs.bootstrap.browser :as boot]
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.arborist.fragments :as frag]
    [shadow.experiments.grove :as sg :refer (<< defc)]
    [shadow.experiments.grove.events :as ev]
    [shadow.experiments.grove.transit :as transit]
    [shadow.experiments.grove.http-fx :as http-fx]
    [shadow.experiments.grove.local :as local-eng]
    [shadow.experiments.grove.examples.env :as env]
    [shadow.experiments.grove.examples.model :as m]
    [shadow.experiments.grove.examples.js-editor :as ed-js]
    [shadow.experiments.grove.examples.cljs-editor :as ed-cljs]
    [shadow.experiments.grove.runtime :as gr]))

(defonce dom-root
  (js/document.getElementById "app"))

(defc ui-root []
  (bind {::m/keys [example-tab example-code example-js example-ns] :as data}
    (sg/query-root
      [::m/example-code
       ::m/example-tab
       ::m/example-ns
       ::m/example-js]))

  (bind ed-cljs-ref
    (sg/ref))

  (bind example-div
    (sg/ref))

  (hook
    (sg/effect [example-ns]
      (fn [env]
        (when example-ns
          (let [example-fn (js/goog.getObjectByName (comp/munge (str example-ns "/example")))
                init-fn (js/goog.getObjectByName (comp/munge (str example-ns "/init")))]

            (cond
              ;; full custom render
              (and init-fn (fn? init-fn))
              (do (init-fn) ;; expected to mount to #root el
                  (fn example-cleanup []
                    ;; FIXME: only expects sg/render to render into div
                    (sg/unmount-root @example-div)))

              ;; simple example without any schema or custom env
              (and example-fn (fn? example-fn))
              (let [rt-ref (gr/prepare {} (atom {}) ::example)]
                (sg/render rt-ref @example-div (example-fn))
                (fn example-cleanup []
                  (sg/unmount-root @example-div)))

              :else
              (js/console.log "TBD, no example or render?")))))))

  (render
    (<< [:div.inset-0.fixed.flex.flex-col
         [:div {:class "border-b border-t p-2"}
          [:div.text-xl.font-bold "shadow-grove Playground"]]

         [:div {:class "flex flex-1 overflow-hidden"}
          [:div {:class "flex-1 text-sm flex flex-col"}
           [:div.flex-1.relative
            [:div.absolute.inset-0
             (ed-cljs/editor
               {:editor-ref ed-cljs-ref
                :value example-code
                :submit-event {:e ::m/compile!}})]]

           [:div.border-t.p-4
            [:button.px-4.border.shadow
             {:on-click ::m/compile!}
             "Eval"]
            " or ctrl+enter or shift+enter to eval"]]

          [:div {:class "flex-1 border-l-2 flex flex-col"}
           [:div.font-bold.py-4.border-b
            [:div.inline.p-4.cursor-pointer.border-r
             {:on-click {:e ::m/select-tab! :tab :result}}
             "Example Result"]
            [:div.inline.p-4.cursor-pointer.border-r
             {:on-click {:e ::m/select-tab! :tab :code}}
             "JS Code"]]

           ;; hiding/showing to avoid unmounting/mounting examples when switching tabs
           ;; they might use local state which would get lost
           [:div {:id "root"
                  :class (str "p-2 flex-1 overflow-auto" (when (not= example-tab :result) " hidden"))
                  :dom/ref example-div}]

           (when (= :code example-tab)
             (<< [:div {:class "flex-1 flex flex-col"}
                  [:div.flex-1.relative
                   ;; FIXME: CodeMirror style messes up flexbox here without this
                   [:div.absolute.inset-0
                    (ed-js/editor {:value example-js})]]
                  [:div {:class "border-b border-t p-4"}
                   "This is " [:span.font-bold "unoptimized"] " JS code. :advanced optimizations will shrink this significantly. shadow-cljs output is also slighty more optimized than self-hosted"]]))]
          ]]))

  ;; FIXME: code is already present when using keyboard submit
  (event ::m/compile! [env e _]
    (sg/run-tx env (assoc e :code (.getValue @ed-cljs-ref))))

  (event ::m/select-tab! sg/tx))

(def source-ref (atom ""))

(ev/reg-event env/rt-ref ::m/select-tab!
  (fn [{:keys [db] :as env} {:keys [tab] :as e}]
    {:db (assoc db ::m/example-tab tab)}))

(ev/reg-event env/rt-ref ::m/compile-result!
  (fn [env {:keys [formatted-source ns]}]
    (update env :db assoc ::m/example-ns ns ::m/example-js formatted-source)))

(defn get-only-entry [obj]
  ;; FIXME: add least assert that there is actually only one entry
  ;; files contains an object with files : { "some-file.cljs" : {} ... }
  (gobj/get obj (aget (gobj/getKeys obj) 0)))

(ev/reg-event env/rt-ref ::m/gist-loaded!
  (fn [{:keys [db] :as env} {:keys [gist-id result]}]
    (let [code
          (-> result
              (gobj/get "files")
              (get-only-entry)
              (gobj/get "content"))]

      {:db (assoc db ::m/example-code code
                     ::m/example-result "Compiling ...")
       :cljs-compile code})))

(ev/reg-event env/rt-ref ::m/load-gist!
  (fn [{:keys [db] :as env} {:keys [gist-id] :as e}]
    {:db
     (assoc db ::m/gist-id gist-id ::m/example-code ";; Loading Gist ...")

     :gist-api
     {:request
      {:uri gist-id}

      :on-success
      {:e ::m/gist-loaded! :gist-id gist-id}}}))

(ev/reg-event env/rt-ref ::m/compile!
  (fn [{:keys [db] :as env} {:keys [code] :as e}]
    {:db (assoc db ::m/example-code code
                   ::m/example-ns nil)
     :cljs-compile code}))

(ev/reg-fx env/rt-ref :cljs-compile
  (fn [{:keys [transact!] :as env} code]
    (let [source-ref (atom "")
          compile-state-ref (::m/compile-state-ref env)]

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

        (fn handle-compile-result
          [{:keys [error ns value] :as result}]

          (js/console.log "compile-result" result)
          ;; FIXME: handle error

          ;; code that is not compiled by shadow-cljs directly relies on fragment registry
          ;; so we need to reset this, otherwise fragments aren't replaceable
          ;; only needed to filter out fragments compiled by self-hosted code

          (frag/reset-known-fragments!)

          (let [formatted-source
                (prettier/format
                  @source-ref
                  #js {:parser "babel"
                       :plugins #js [prettier-babel]})]

            ;; compiler may have gone async or not
            ;; transact! is only allowed if it has, so we just always go async
            (js/setTimeout
              (fn []
                (transact! {:e ::m/compile-result! :formatted-source formatted-source :ns ns}))
              0)
            ))))))

(def example-code
  (->> [";; The Playground will compile the code and call the example function"
        ""
        ";; Obligatory Hello World Example"
        ";; The result of the function call is rendered over there ->"
        ""
        "(defn example []"
        "  \"Hello World\")"]
       (str/join "\n")))

(defn ^:dev/after-load start []
  (if-let [[_ gist-id] (re-find #"\?id=(\w+)" js/document.location.search)]
    (sg/run-tx! env/rt-ref {:e ::m/load-gist! :gist-id gist-id})
    (sg/run-tx! env/rt-ref {:e ::m/compile! :code example-code}))

  (sg/render env/rt-ref dom-root (ui-root)))

(defn init []
  (transit/init! env/rt-ref)

  (local-eng/init! env/rt-ref)

  (ev/reg-fx env/rt-ref :gist-api
    (http-fx/make-handler
      {:on-error {:e ::m/request-error!}
       :base-url "https://api.github.com/gists/"
       :with-credentials false
       :request-format :json}))

  (boot/init (::m/compile-state-ref @env/rt-ref)
    {:path "bootstrap"}
    start))