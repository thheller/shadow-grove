(ns shadow.grove
  (:require [clojure.spec.alpha :as s]))

;; just for convenience, less imports for the user

(defmacro defc [& args]
  (with-meta `(shadow.grove.components/defc ~@args) (meta &form)))

(defmacro << [& body]
  (with-meta `(shadow.arborist.fragments/html ~@body) (meta &form)))

;; I prefer << but <> looks more familiar to reagent :<>
;; costs nothing to have both, let the user decide
(defmacro <> [& body]
  (with-meta `(shadow.arborist.fragments/html ~@body) (meta &form)))

(defmacro fragment [& body]
  (with-meta `(shadow.arborist.fragments/html ~@body) (meta &form)))

(defmacro html [& body]
  (with-meta `(shadow.arborist.fragments/html ~@body) (meta &form)))

(defmacro svg [& body]
  (with-meta `(shadow.arborist.fragments/svg ~@body) (meta &form)))

(defmacro css [& body]
  (with-meta `(shadow.css/css ~@body) (meta &form)))

(defmacro dev-only [& body]
  (when (= :dev (:shadow.build/mode &env))
    `(do ~@body)))

(defmacro dev-log [& args]

  (when (= :dev (:shadow.build/mode &env))
    (let [{:keys [line column file]} (meta &form)]
      `(when-not (nil? shadow.grove/dev-log-handler)
         (shadow.grove/dev-log-handler {:ns ~(str *ns*) :line ~line :column ~column :file ~file} [~@args])))))

(s/def ::deftx-args
  (s/cat
    :tx-name simple-symbol?
    :args vector? ;; FIXME: core.specs for destructure help
    :process-args vector?
    :body (s/* any?)))

(s/fdef deftx :args ::deftx-args)

(defmacro deftx [& args]
  (let [{:keys [tx-name args process-args body]}
        (s/conform ::deftx-args args)

        arg-syms
        (vec (take (count args) (repeatedly #(gensym "arg"))))]

    `(defn ~tx-name ~arg-syms
       (with-meta
         {:e ~(keyword (str *ns*) (str tx-name))
          :args ~arg-syms}
         ;; carry process fn in metadata so it doesn't need to be registered
         ;; but also isn't part of event when serialized (e.g. devtools transfer)
         {::tx (fn ~tx-name ~process-args
                 (let ~(reduce-kv
                         (fn [bindings idx arg-sym]
                           (conj bindings (nth args idx) (nth arg-syms idx)))
                         []
                         arg-syms)
                   ~@body))}))))

(comment
  (macroexpand-1
    '(deftx foo
       [a {:keys [b]}]
       [env ev e]
       :yo)))
