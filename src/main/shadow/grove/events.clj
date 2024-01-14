(ns shadow.grove.events
  (:require
    [cljs.env :as env]
    [cljs.analyzer :as ana]
    [clojure.string :as str]
    [shadow.lazy :as sl]))

(defn module-for-ns [compiler-env ns]
  (get-in compiler-env [::sl/ns->mod ns] ::no-mods))

;; the point of this is getting the side-effect of registering the event handler to a controlled place
;; instead of having them happen at namespace loading time

;; and also having actual defns that can be tinkered with from the REPL
;; having the user write this by hand kinda sucks

;;   (defn my-ev-handler [env ev] ...)
;;   (ev/reg-event env/rt-ref ::my-ev my-ev-handler)

;; also gets rid of the constant references to rt-ref which would maybe allow isolating that more

;; could instead create a defevent macro but that would require taking the rt-ref argument
;; and still execute the register side-effect on-load
;; and maybe not register with Cursive etc for find-usages

;; anonymous functions suck because you can never reference them directly anywhere
;; (ev/reg-event env/rt-ref ::my-ev (fn [env ev] ...))

;; with defn they can easily compose and one event handler can easily directly call another

(defmacro register-events! [rt-ref-sym]
  (let [env
        &env

        current-ns
        (ana/gets env :ns :name)

        ns-str
        (str current-ns)

        ;; FIXME: should this really be enforced here?
        ;; only allowing "magic" registering of events that belong to the actual project
        ;; libraries should just call reg-event directly when initializing
        ns-prefix
        (subs ns-str 0 (inc (str/last-index-of ns-str ".")))

        compiler-env
        @env/*compiler*

        our-mod
        (module-for-ns compiler-env current-ns)]

    ;; make it a hard error when the namespace calling this isn't marked with :dev/always
    ;; otherwise it'll have unpredictable caching issues

    (when-not (get-in compiler-env [::ana/namespaces current-ns :meta :dev/always])
      (throw (ana/error env "Namespace using register-events! must use :dev/always meta")))

    `(do
       ~@(for [{ns-name :name :keys [defs]} (vals (::ana/namespaces compiler-env))
               :when (let [ns-mod (module-for-ns compiler-env ns-name)]
                       (and (not= ns-name current-ns)
                            (= our-mod ns-mod)
                            (str/starts-with? (name ns-name) ns-prefix)))

               {fq-name :name def-meta :meta :as def} (vals defs)

               :let [ev-handle (get def-meta ::handle)]
               :when ev-handle
               ev-id (cond
                       ;; {::ev/handle ::some/event} specific event to handle
                       (keyword? ev-handle)
                       [ev-handle]

                       ;; {::ev/handle true} equals event name matches defn name
                       (true? ev-handle)
                       [(keyword (namespace fq-name) (name fq-name))]

                       ;; multiple events
                       ;; {::ev/handle [::some/event ::other/event]}
                       ;; FIXME: validate seq of actual keywords
                       (seq ev-handle)
                       ev-handle

                       ;; FIXME: this can't throw for the other ns since that is already compiled
                       ;; but can we make it look it came from there? might require mods in shadow-cljs
                       :else
                       (throw (ana/error env (str "event handler used invalid handles value: " ev-handle))))]

           ;; add a reg-event call for every event handler found so user can skip it
           ;; FIXME: should maybe have a secondary reg-event in dev that takes additional data
           ;; so that runtime errors can accurately point to the proper defn?
           ;; FIXME: could maybe do additional validation here
           ;; could also use some additional metadata maybe
           `(reg-event ~rt-ref-sym ~ev-id ~fq-name)
           ))))
