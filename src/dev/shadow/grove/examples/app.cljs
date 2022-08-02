(ns shadow.grove.examples.app
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
    [shadow.arborist :as sa]
    [shadow.arborist.fragments :as frag]
    [shadow.grove :as sg :refer (<< defc css)]
    [shadow.grove.events :as ev]
    [shadow.grove.transit :as transit]
    [shadow.grove.http-fx :as http-fx]
    [shadow.grove.local :as local-eng]
    [shadow.grove.examples.env :as env]
    [shadow.grove.examples.model :as m]
    [shadow.grove.examples.js-editor :as ed-js]
    [shadow.grove.examples.css-editor :as ed-css]
    [shadow.grove.examples.cljs-editor :as ed-cljs]
    [shadow.grove.examples.html-editor :as ed-html]
    [shadow.grove.runtime :as gr]
    [shadow.css.build :as cb])
  (:import [goog.string StringBuffer]))

(defonce dom-root
  (js/document.getElementById "app"))

(defc ui-root []
  (bind {::m/keys [example-tab example-src-tab example-code example-html example-js example-ns example-css] :as data}
    (sg/query-root
      [::m/example-code
       ::m/example-tab
       ::m/example-src-tab
       ::m/example-html
       ::m/example-ns
       ::m/example-js
       ::m/example-css]))

  (bind ed-cljs-ref
    (sg/ref))

  (bind ed-html-ref
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
              (let [rt-ref (sg/prepare {} (atom {}) ::example)]
                (sg/render rt-ref @example-div (example-fn))
                (fn example-cleanup []
                  (sg/unmount-root @example-div)))

              :else
              (js/console.log "TBD, no example or render?")))))))

  (render
    (<< [:div {:class (css :inset-0 :fixed :flex :flex-col)}
         [:div {:class (css :border-b :border-t :p-2)}
          [:div {:class (css :text-xl :font-bold)} "shadow-grove Playground"]]

         [:div {:class (css :flex :flex-1 :overflow-hidden)}
          [:div {:class (css :flex-1 :text-sm :flex :flex-col)}
           [:div {:class (css :font-bold :py-4 :border-b)}
            [:div
             {:class (css :inline :p-4 :cursor-pointer :border-r)
              :on-click {:e ::m/select-src! :tab :cljs}}
             "CLJS"]
            [:div
             {:class (css :inline :p-4 :cursor-pointer :border-r)
              :on-click {:e ::m/select-src! :tab :html}}
             "HTML Conversion"]]

           (case example-src-tab
             :html
             (<< [:div {:class (css :flex-1 :flex :flex-col)}
                  [:div {:class (css :flex-1 :relative)}
                   [:div {:class (css :absolute :inset-0)}
                    (ed-html/editor
                      {:editor-ref ed-html-ref
                       :value example-html
                       :submit-event {:e ::m/convert!}})]]

                  [:div {:class (css :border-t :p-4)}
                   [:button
                    {:class (css :px-4 :border :shadow)
                     :on-click ::m/convert!}
                    "Convert"]
                   " or ctrl+enter or shift+enter to convert + eval"]])

             (<< [:div {:class (css :flex-1 :flex :flex-col)}
                  [:div {:class (css :flex-1 :relative)}
                   [:div {:class (css :absolute :inset-0)}
                    (ed-cljs/editor
                      {:editor-ref ed-cljs-ref
                       :value example-code
                       :submit-event {:e ::m/compile!}})]]

                  [:div {:class (css :border-t :p-4)}
                   [:button
                    {:class (css :px-4 :border :shadow)
                     :on-click ::m/compile!}
                    "Eval"]
                   " or ctrl+enter or shift+enter to eval"]]))]

          [:div {:class (css :flex-1 :border-l-2 :flex :flex-col)}
           [:div {:class (css :font-bold :py-4 :border-b)}
            [:div
             {:class (css :inline :p-4 :cursor-pointer :border-r)
              :on-click {:e ::m/select-tab! :tab :result}}
             "Example Result"]
            [:div
             {:class (css :inline :p-4 :cursor-pointer :border-r)
              :on-click {:e ::m/select-tab! :tab :code}}
             "Generated JS"]
            [:div
             {:class (css :inline :p-4 :cursor-pointer :border-r)
              :on-click {:e ::m/select-tab! :tab :css}}
             "Generated CSS"]]

           ;; the page css is also generated by shadow.css
           ;; can just include the styles here since everything is namespaced
           ;; and won't conflict. and we inherit the base style preflight stuff
           [:style example-css]

           ;; hiding/showing to avoid unmounting/mounting examples when switching tabs
           ;; they might use local state which would get lost

           [:div {:id "root"
                  :class [(css :p-2 :flex-1 :overflow-auto)
                          (when (not= example-tab :result)
                            (css :hidden))]
                  :dom/ref example-div}]

           (case example-tab
             :css
             (<< [:div {:class (css :flex-1 :flex :flex-col)}
                  [:div {:class (css :flex-1 :relative)}
                   [:div {:class (css :absolute :inset-0)}
                    (ed-css/editor {:value example-css})]]
                  [:div {:class (css :border-b :border-t :p-4)}
                   "This is " [:span {:class (css :font-bold)} "unoptimized"] " CSS."]])
             :code
             (<< [:div {:class (css :flex-1 :flex :flex-col)}
                  [:div {:class (css :flex-1 :relative)}
                   ;; FIXME: CodeMirror style messes up flexbox here without this
                   [:div {:class (css :absolute :inset-0)}
                    (ed-js/editor {:value example-js})]]
                  [:div {:class (css :border-b :border-t :p-4)}
                   "This is "
                   [:span {:class (css :font-bold)} "unoptimized"]
                   " JS code. :advanced optimizations will shrink this significantly. shadow-cljs output is also slighty more optimized than self-hosted"]])
             ;; other tabs
             nil)]
          ]]))

  ;; FIXME: code is already present when using keyboard submit
  (event ::m/convert! [env e _]
    (sg/run-tx env (assoc e :code (.getValue @ed-html-ref))))

  (event ::m/compile! [env e _]
    (sg/run-tx env (assoc e :code (.getValue @ed-cljs-ref)))))

(def source-ref (atom ""))

(ev/reg-event env/rt-ref ::m/select-tab!
  (fn [env {:keys [tab] :as e}]
    (assoc-in env [:db ::m/example-tab] tab)))

(ev/reg-event env/rt-ref ::m/select-src!
  (fn [env {:keys [tab] :as e}]
    (assoc-in env [:db ::m/example-src-tab] tab)))

(ev/reg-event env/rt-ref ::m/compile-result!
  (fn [env {:keys [formatted-source ns]}]


    (let [{:keys [outputs] :as result}
          (-> env/css-base
              (cb/index-source (get-in env [:db ::m/example-code]))
              (cb/generate {:example {:include ['*]}}))

          css
          (->> outputs
               (map :css)
               (str/join "\n"))]

      (doseq [{:keys [warnings]} outputs
              warn warnings]
        (js/console.warn "CSS" (name (:warning-type warn)) (:alias warn) warn))

      (update env :db assoc
        ::m/example-ns ns
        ::m/example-js formatted-source
        ::m/example-css css))))

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

      (-> env
          (update :db assoc
            ::m/example-code code
            ::m/example-result "Compiling ...")
          (ev/queue-fx :cljs-compile code)))))

(ev/reg-event env/rt-ref ::m/load-gist!
  (fn [{:keys [db] :as env} {:keys [gist-id] :as e}]
    (-> env
        (update :db assoc
          ::m/gist-id gist-id
          ::m/example-code ";; Loading Gist ...")
        (ev/queue-fx :gist-api
          {:request
           {:uri gist-id}

           :on-success
           {:e ::m/gist-loaded! :gist-id gist-id}}))))

(ev/reg-event env/rt-ref ::m/compile!
  (fn [{:keys [db] :as env} {:keys [code] :as e}]
    (-> env
        (update :db assoc
          ::m/example-code code
          ::m/example-ns nil)
        (ev/queue-fx :cljs-compile code))))

(defn printi [sb indent arg]
  (.append sb (str (str/join "" (repeat indent " ")) arg)))

(defn sb-trim-right [sb]
  (let [all (str/trimr (.toString sb))]
    (.set sb all)
    sb))

(defn vec-conj [x y]
  (if (nil? x)
    [y]
    (conj x y)))

(defn convert-class [sb indent class]
  ;; "px-4 sm:foo sm:bar"
  ;; should put each sm: into the same group

  (.append sb "(css ")
  (let [parts
        (reduce
          (fn [m val]
            (let [idx (str/index-of val ":")]
              (if-not idx
                (update m "_" conj (str ":" val))
                (let [[prefix suffix] (str/split val #":" 2)]
                  (update m (str ":" prefix) vec-conj (str ":" suffix))
                  )))

            )
          {"_" []}
          (str/split class #"\s+"))]

    (.append sb
      (->> (get parts "_")
           (str/join " ")))

    (doseq [group (-> parts (dissoc "_") (keys) (sort))]
      (.append sb " [")
      (.append sb group)
      (.append sb " ")
      (.append sb
        (->> (get parts group)
             (str/join " ")))
      (.append sb "]")))

  (.append sb ")"))

(defn convert-html* [sb ^js node indent]
  (case (.-nodeType node)
    1 ;; element
    (do (printi sb indent "[")
        (.append sb (str ":" (str/lower-case (.-tagName node)) " "))

        (let [attrs (array-seq (.getAttributeNames node))]

          (when (seq attrs)
            (.append sb "{")
            (dotimes [x (count attrs)]
              (when-not (zero? x)
                (.append sb " "))
              (let [name (nth attrs x)]
                (when (not (str/starts-with? name "x-"))
                  (.append sb (str ":" name " "))
                  (let [val (.getAttribute node name)]
                    (if (= "class" name)
                      (convert-class sb indent val)
                      (.append sb (pr-str val)))))))
            (.append sb "}")))

        (.append sb "\n")

        (doseq [node (array-seq (.-childNodes node))]
          (convert-html* sb node (inc indent)))
        (sb-trim-right sb)
        (.append sb "]\n"))
    3 ;; text
    (let [content (.-textContent node)]
      ;; skip non-significant whitespace sections
      (when (re-find #"\S" content)
        (printi sb indent
          ;; collapse leading/trailing whitespace
          (-> content
              (str/replace #"^\s+" " ")
              (str/replace #"\s+$" " ")
              (pr-str)))
        (.append sb "\n")))

    nil))

(defn convert-html [source]
  (let [el (doto (js/document.createElement "div")
             (set! -innerHTML source))]
    (str "(ns converted.html\n"
         "  (:require [shadow.grove :refer (<< defc css)]))\n"
         "\n"
         "(defn example []\n"
         "  (<< "
         (let [sb (StringBuffer.)
               nodes (array-seq (.-childNodes el))]
           (dotimes [x (count nodes)]
             (let [node (nth nodes x)]
               (convert-html* sb node 6)))

           (str/trim (.toString sb)))
         "))\n")))

(ev/reg-event env/rt-ref ::m/convert!
  (fn [{:keys [db] :as env} {:keys [code] :as e}]
    (let [converted (convert-html code)]
      (-> env
          (update :db assoc
            ::m/example-src-tab :cljs
            ::m/example-code converted
            ::m/example-html code
            ::m/example-ns nil)
          (ev/queue-fx :cljs-compile converted)))))

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
        "(ns hello.world"
        "  (:require [shadow.grove :as sg :refer (<< defc css)]))"
        ""
        "(def example-class"
        "  ;; define css via tailwind style aliases"
        "  (css :text-5xl :py-8 :font-bold :text-red-500"
        "    ;; or just CSS directly"
        "    {:text-align \"center\"}"
        "    ;; pseudo-class support"
        "    [\"&:hover\" :text-green-700]))"
        ""
        "(defn example []"
        "  (<< [:div {:class example-class} \"Hello World\"]))"]
       (str/join "\n")))

(defn ^:dev/after-load start []
  (if-let [[_ gist-id] (re-find #"\?id=(\w+)" js/document.location.search)]
    (sg/run-tx! env/rt-ref {:e ::m/load-gist! :gist-id gist-id})
    (sg/run-tx! env/rt-ref {:e ::m/compile! :code example-code}))

  (sg/render env/rt-ref dom-root (ui-root)))

(defn init []
  (transit/init! env/rt-ref)

  (ev/reg-fx env/rt-ref :gist-api
    (http-fx/make-handler
      {:on-error {:e ::m/request-error!}
       :base-url "https://api.github.com/gists/"
       :with-credentials false
       :request-format :json}))

  (boot/init (::m/compile-state-ref @env/rt-ref)
    {:path "bootstrap"}
    start))