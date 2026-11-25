(ns shadow.grove
  (:require
    [clojure.spec.alpha :as s]
    [shadow.grove.components :as comp]))

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

(s/def ::each-args
  (s/cat
    :coll any?
    :key-fn (s/? (complement vector?))
    :args vector?
    :body (s/* any?)))

(s/fdef each :args ::each-args)

(defmacro each
  "(each coll [item idx] body)         like simple-seq
   (each coll key-fn [item idx key] body) like keyed-seq

   body is the render fn, without the fn. unlike an inline (fn ...) passed to
   keyed-seq, items only render again if their data or idx changed, or one of the
   outer locals the body uses is not = to what it was last render.

   keyed: if the body never uses idx, a moved item doesn't render again."
  [& args]
  (let [{:keys [coll key-fn args body]}
        (s/conform ::each-args args)

        ;; all locals at the call site, find-used-bindings skips the fn args and
        ;; names the body binds itself (let, loop, fn). anything else it misses
        ;; only adds a dep, which only means extra renders.
        deps
        (->> (comp/find-used-bindings #{} (:locals &env) (list* 'fn args body))
             (sort-by str)
             (vec))

        ;; idx is the second arg. unused if not bound, bound to _ or never used by the
        ;; body. & before it means it ends up in a rest arg, count that as used.
        idx-sym (second args)
        idx-free?
        (and (not= '& (first args))
             (or (nil? idx-sym)
                 (= '_ idx-sym)
                 (and (simple-symbol? idx-sym)
                      (not= '& idx-sym)
                      ;; the fn with idx replaced by _, so idx counts as an outer local
                      ;; and other args shadow it as they would
                      (empty? (comp/find-used-bindings #{} {idx-sym {}}
                                (list* 'fn (assoc args 1 '_) body))))))

        {:keys [line column]} (meta &form)

        ;; unique per call site, also per compile so hot reload renders again
        site
        (str (gensym (str *ns* "/each@" line ":" column "_")))

        deps-arr
        (when (seq deps)
          `(cljs.core/array ~@deps))]

    (with-meta
      (if (nil? key-fn)
        `(shadow.arborist.collections/simple-each ~coll ~site ~deps-arr (fn ~args ~@body))
        `(shadow.arborist.collections/keyed-each ~coll ~key-fn ~site ~deps-arr ~idx-free? (fn ~args ~@body)))
      (meta &form))))

(s/def ::deftx-args
  (s/cat
    :tx-name simple-symbol?
    :args vector? ;; FIXME: core.specs for destructure help
    :base-ev (s/? map?)
    :process-args vector?
    :body (s/* any?)))

(s/fdef deftx :args ::deftx-args)

(defmacro deftx [& args]
  (let [{:keys [tx-name base-ev args process-args body]}
        (s/conform ::deftx-args args)

        arg-syms
        (vec (take (count args) (repeatedly #(gensym "arg"))))

        ev
        (assoc base-ev
          :e (keyword (str *ns*) (str tx-name))
          :args arg-syms)]

    `(defn ~tx-name ~arg-syms
       (with-meta
         ~ev
         ;; carry process fn in metadata so it doesn't need to be registered
         ;; but also isn't part of event when serialized (e.g. devtools transfer)
         {::tx (fn ~tx-name ~process-args
                 ;; idea here was to delay any destructuring until event is actually handled
                 ;; to avoid doing as much work as possible in event construction
                 ;; but that means the optional base event map cannot use the bindings
                 ;; FIXME: it might want to?
                 (let ~(reduce-kv
                         (fn [bindings idx arg-sym]
                           (conj bindings (nth args idx) (nth arg-syms idx)))
                         []
                         arg-syms)
                   ~@body))}))))

(comment
  (macroexpand-1
    '(deftx foo [a {:keys [b]}]
       {:e/debounce 50}
       [env ev e]
       :yo)))
