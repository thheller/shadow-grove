(ns shadow.grove.css
  (:require
    [clojure.string :as str]
    [shadow.grove.css.specs :as s]))

(def class-defs
  (atom {}))

(defn get-class [class]
  ;; FIXME: need to actually generate classnames for CLJ
  (get @class-defs class class))

(defmacro css
  "generates css classnames

   using a subset of Clojure/EDN to define css rules, no dynamic code allowed whatsoever"
  [& body]
  (let [conformed
        (s/conform! body)

        {:keys [line column]}
        (meta &form)

        ns-str
        (str *ns*)

        ;; this must generate a unique identifier right here
        ;; using only information that can be taken from the css form
        ;; itself. It must not look at any other location and the id
        ;; generated must be deterministic.

        ;; this unfortunately makes it pretty much unusable in the REPL
        ;; this is fine since there is no need for CSS in the REPL
        ;; but may end up emitting invalid references in code
        ;; which again is fine in JS since it'll just be undefined
        css-id
        (str (-> ns-str
                 (str/replace #"\." "_")
                 (munge))
             "__"
             "L" line
             "_"
             "C" column)

        class-def
        (assoc conformed
          :ns (symbol ns-str)
          :line line
          :column column
          :css-id css-id)]


    (if-not (:ns &env)
      ;; expanding CLJ, just store in atom
      (do (swap! class-defs assoc css-id class-def)
          `(get-class ~css-id))

      ;; expanding CLJS, need to store data in analyzer metadata
      ;; since a local atom won't be cached and break in incremental builds
      ;; FIXME: eventually this needs to support self-host so ns-resolve is no go
      (do (when-some [compiler-env @(ns-resolve 'cljs.env '*compiler*)]
            (swap! compiler-env assoc-in [:cljs.analyzer/namespaces (-> &env :ns :name) ::classes css-id] class-def))
          ;; we emit a reference so that the classname doesn't need to be generated during macroexpansion
          ;; and advanced can take care of removing unused references for us
          ;; must emit via js* or a no-resolve var so the analyzer doesn't try to find it
          ;; js* just seems easier
          `(~'js* ~(str "(shadow.grove.css.defs." css-id ")"))))))

(comment

  (require 'clojure.pprint)

  @class-defs

  (clojure.pprint/pprint
    (macroexpand
      '(css :foo
         "yo" {:hello "world"})
      )))