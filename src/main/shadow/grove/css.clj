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

        ;; FIXME: should have a way to generate shorter class names like defstyled did
        class-id
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
          :class-id class-id
          :sel (str "." class-id))]


    (if false ;; -not (:ns &env)
      ;; expanding CLJ, just store in atom
      (do (swap! class-defs assoc class-id class-def)
          `(get-class ~class-id))

      ;; expanding CLJS, need to store data in analyzer metadata
      ;; since a local atom won't be cached and break in incremental builds
      ;; FIXME: eventually this needs to support self-host so ns-resolve is no go
      (do (when-some [compiler-env @(ns-resolve 'cljs.env '*compiler*)]
            (swap! compiler-env assoc-in [:cljs.analyzer/namespaces (-> &env :ns :name) ::classes class-id] class-def))
          ;; we emit a reference so that the classname doesn't need to be generated during macroexpansion
          ;; and advanced can take care of removing unused references for us
          ;; must emit via js* or a no-resolve var so the analyzer doesn't try to find it
          ;; js* just seems easier
          `(~'js* ~(str "(shadow.grove.css.defs." class-id ")"))))))

(comment

  (require 'clojure.pprint)

  @class-defs

  (clojure.pprint/pprint
    (macroexpand
      '(css :foo
         "yo" {:hello "world"})
      )))