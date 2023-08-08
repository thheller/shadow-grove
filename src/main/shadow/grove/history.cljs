(ns shadow.grove.history
  (:require
    [clojure.string :as str]
    [shadow.grove :as sg]
    [shadow.grove.runtime :as rt]
    [shadow.grove.events :as ev]
    [shadow.arborist.attributes :as attr]))

(defn init!
  "Use to intercept clicks on 'internal' links and HTML `History` modifications to:
   1. update the state of `History` (e.g. update the URL),
   2. trigger the `:ui/route!` event (for which the handler is defined by the user).

   `config` map:
   * `:start-token`  – replaces the \"/\" URL on init. Defaults to `\"/\"`.
                       Should start – but not end – with `/`.
   * `:path-prefix`  – a common URL base to use. Defaults to `\"\"`.
                       Should start – but not end – with `/`.
   * `:use-fragment` – pass `true` if you want to use hash-based URLs. 
                       Defaults to `false`.
   * `:root-el`      - optional DOM node to set the click listener on.
                       Defaults to `document.body`.

   ### Usage
   - This function should be called before component env is initialised (e.g.
     before [[shadow.grove/render]]). *Note*: the `:ui/route!` event
     will be triggered on call.
   - Set the `:ui/href` attribute on anchors for internal links. (Unlike regular 
     `href`, will handle of `path-prefix` and `use-fragment`.)
   - Register handler for the event `{:e :ui/route! :token token :tokens tokens}`.
     - `token` is \"/a/b\"
     - `tokens` is `[\"a\", \"b\"]`.
   - Intercepts only pure clicks with main mouse button (no keyboard modifiers).
   - The `:ui/redirect!` *fx* handler will be registered and can be 'called' with:
     * `:token` – URL to redirect to (a string starting with `/`)
     * `:title` – optional. `title` arg for `history.pushState`
   
   ---
   Example:
   ```clojure
    ;; in a component
    [:a {:ui/href (str \"/\" id)} \"internal link\"]

    ;; handle clicks
    (sg/reg-event rt-ref :ui/route!
      (fn [env {:keys [token tokens]}]
        (let [ident (db/make-ident ::entity (first tokens))]
          (-> env
              (assoc-in [:db ::main-root] ident)
              (sg/queue-fx :ui/set-window-title! {:title (get-title ident)})))))

    ;; calling init
    (defn ^:dev/after-load start []
      (sg/render rt-ref root-el (ui-root)))

    (defn init []
      (history/init! rt-ref {})
      (start))
   ```"  
  [rt-ref
   {:keys [start-token path-prefix use-fragment root-el]
    :or {start-token "/"
         path-prefix ""
         use-fragment false}
    :as config}]

  {:pre [(or (= "" path-prefix)
             (and (string? path-prefix)
                  (str/starts-with? path-prefix "/")
                  (not (str/ends-with? path-prefix "/"))))

         (or (= "/" start-token)
             (and (str/starts-with? start-token "/")
                  (not (str/ends-with? start-token "/"))))
         ]}

  (let [get-token
        (fn []
          (if-not use-fragment
            (let [path js/window.location.pathname]
              (cond
                (= path path-prefix)
                "/"

                (str/starts-with? path path-prefix)
                (subs path (count path-prefix))

                :else
                (throw (ex-info "path did not match path prefix" {:path path :path-prefix path-prefix}))
                ))
            (let [hash js/window.location.hash]
              ;; always start everything with a / even when using hash
              ;; is "" when url doesn't have a hash, otherwise #foo
              (if (= hash "")
                "/"
                (subs js/window.location.hash (+ 1 (count path-prefix)))))))

        trigger-route!
        (fn trigger-route!
          ([]
           (trigger-route! (get-token)))
          ([token]
           ;; token must start with /, strip it to get tokens vector
           (let [tokens (str/split (subs token 1) #"/")]
             (sg/run-tx! rt-ref {:e :ui/route! :token token :tokens tokens}))))

        first-token
        (get-token)]

    (attr/add-attr :ui/href
      (fn [env node oval nval]
        (when nval
          (when-not (str/starts-with? nval "/")
            (throw (ex-info (str ":ui/href must start with / got " nval)
                     {:val nval})))

          (set! node -href
            (if use-fragment
              (str "#" path-prefix nval)
              (str path-prefix
                   (if-not (str/ends-with? path-prefix "/")
                     nval
                     (subs nval 1))))))))

    (ev/reg-fx rt-ref :ui/redirect!
      (fn [{:keys [transact!] :as env} {:keys [token title]}]
        {:pre [(str/starts-with? token "/")]}

        (js/window.history.pushState
          nil
          (or title js/document.title)
          (str path-prefix token))

        (let [tokens (str/split (subs token 1) #"/")]
          ;; FIXME: there needs to be cleaner way to start another tx from fx
          ;; currently forcing them to be async so the initial tx can conclude
          (js/setTimeout #(transact! {:e :ui/route! :token token :tokens tokens}) 0)
          )))

    ;; immediately trigger initial route when this is initialized
    ;; don't wait for first env-init, thats problematic with multiple roots
    (trigger-route!
      (if (and (= "/" first-token) (seq start-token))
        start-token
        first-token))

    (swap! rt-ref
      (fn [rt]
        (-> rt
            (assoc ::config config)
            (update ::rt/env-init conj
              (fn [env]
                ;; fragment uses hashchange event so we can skip checking clicks
                (when-not use-fragment
                  (.addEventListener (or root-el js/document.body) "click"
                    (fn [^js e]
                      (when (and (zero? (.-button e))
                                 (not (or (.-shiftKey e) (.-metaKey e) (.-ctrlKey e) (.-altKey e))))
                        (when-let [a (some-> e .-target (.closest "a"))]

                          (let [href (.getAttribute a "href")
                                a-target (.getAttribute a "target")]

                            (when (and href (seq href) (str/starts-with? href path-prefix) (nil? a-target))
                              (.preventDefault e)

                              (js/window.history.pushState nil js/document.title href)

                              (trigger-route!)
                              )))))))

                (when (and (= "/" first-token) (seq start-token))
                  (js/window.history.replaceState
                    nil
                    js/document.title
                    (str (when use-fragment "#") path-prefix start-token)))

                (js/window.addEventListener "popstate"
                  (fn [e]
                    (trigger-route!)))

                (when use-fragment
                  (js/window.addEventListener "hashchange"
                    (fn [e]
                      (trigger-route!))))

                env)))))))