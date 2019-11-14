(ns cljs-hacks
  (:require
    [cljs.analyzer]
    [cljs.compiler]))

;; testing impact of CLJS-3077

(in-ns 'cljs.analyzer)

(defmethod parse 'fn*
  [op env [_ & args :as form] name _]
  (let [named-fn? (symbol? (first args))
        [name meths] (if named-fn?
                       [(first args) (next args)]
                       [name (seq args)])
        ;; turn (fn [] ...) into (fn ([]...))
        meths (if (vector? (first meths))
                (list meths)
                meths)
        locals (:locals env)
        name-var (fn-name-var env locals name)
        env (if (some? name)
              (update-in env [:fn-scope] conj name-var)
              env)
        locals (if (and (some? locals)
                        named-fn?)
                 (assoc locals name name-var)
                 locals)
        form-meta (meta form)
        type (::type form-meta)
        proto-impl (::protocol-impl form-meta)
        proto-inline (::protocol-inline form-meta)
        menv (-> env
                 (cond->
                   (> (count meths) 1)
                   (assoc :context :expr))
                 ;; clear loop flag since method bodies won't be in a loop at first
                 ;; only tracking this to keep track of locals we need to capture
                 (dissoc :in-loop)
                 (merge {:protocol-impl proto-impl
                         :protocol-inline proto-inline}))
        methods (map #(disallowing-ns* (analyze-fn-method menv locals % type (nil? name))) meths)
        mfa (transduce (map :fixed-arity) max 0 methods)
        variadic (boolean (some :variadic? methods))
        locals (if named-fn?
                 (update-in locals [name] assoc
                   ;; TODO: can we simplify? - David
                   :fn-var true
                   :variadic? variadic
                   :max-fixed-arity mfa
                   :method-params (map :params methods))
                 locals)
        methods (if (some? name)
                  ;; a second pass with knowledge of our function-ness/arity
                  ;; lets us optimize self calls
                  (disallowing-ns* (analyze-fn-methods-pass2 menv locals type meths))
                  (vec methods))
        form (vary-meta form dissoc ::protocol-impl ::protocol-inline ::type)
        js-doc (when (true? variadic)
                 "@param {...*} var_args")
        children (if (some? name-var)
                   [:local :methods]
                   [:methods])
        inferred-ret-tag (let [inferred-tags (map (partial infer-tag env) (map :body methods))]
                           (when (apply = inferred-tags)
                             (first inferred-tags)))
        ast (merge {:op :fn
                    :env env
                    :form form
                    :name name-var
                    :methods methods
                    :variadic? variadic
                    :tag 'function
                    :inferred-ret-tag inferred-ret-tag
                    :recur-frames *recur-frames*
                    :in-loop (:in-loop env)
                    :loop-lets *loop-lets*
                    :jsdoc [js-doc]
                    :max-fixed-arity mfa
                    :protocol-impl proto-impl
                    :protocol-inline proto-inline
                    :children children}
              (when (some? name-var)
                {:local name-var}))]
    (let [variadic-methods (into []
                             (comp (filter :variadic?) (take 1))
                             methods)
          variadic-params (if (pos? (count variadic-methods))
                            (count (:params (nth variadic-methods 0)))
                            0)
          param-counts (into [] (map (comp count :params)) methods)]
      (when (< 1 (count variadic-methods))
        (warning :multiple-variadic-overloads env {:name name-var}))
      (when (not (or (zero? variadic-params) (== variadic-params (+ 1 mfa))))
        (warning :variadic-max-arity env {:name name-var}))
      (when (not= (distinct param-counts) param-counts)
        (warning :overload-arity env {:name name-var})))
    (analyze-wrap-meta ast)))

(in-ns 'cljs.compiler)

(defmethod emit* :fn
  [{variadic :variadic? :keys [name env methods max-fixed-arity recur-frames in-loop loop-lets]}]
  ;;fn statements get erased, serve no purpose and can pollute scope if named
  (when-not (= :statement (:context env))
    (let [recur-params (mapcat :params (filter #(and % @(:flag %)) recur-frames))
          loop-locals
          (->> (concat recur-params
                 ;; need to capture locals only if in recur fn or loop
                 (when (or in-loop (seq recur-params))
                   (mapcat :params loop-lets)))
               (map munge)
               seq)]
      (when loop-locals
        (when (= :return (:context env))
          (emits "return "))
        (emitln "((function (" (comma-sep (map munge loop-locals)) "){")
        (when-not (= :return (:context env))
          (emits "return ")))
      (if (= 1 (count methods))
        (if variadic
          (emit-variadic-fn-method (assoc (first methods) :name name))
          (emit-fn-method (assoc (first methods) :name name)))
        (let [name (or name (gensym))
              mname (munge name)
              maxparams (apply max-key count (map :params methods))
              mmap (into {}
                     (map (fn [method]
                            [(munge (symbol (str mname "__" (count (:params method)))))
                             method])
                       methods))
              ms (sort-by #(-> % second :params count) (seq mmap))]
          (when (= :return (:context env))
            (emits "return "))
          (emitln "(function() {")
          (emitln "var " mname " = null;")
          (doseq [[n meth] ms]
            (emits "var " n " = ")
            (if (:variadic? meth)
              (emit-variadic-fn-method meth)
              (emit-fn-method meth))
            (emitln ";"))
          (emitln mname " = function(" (comma-sep (if variadic
                                                    (concat (butlast maxparams) ['var_args])
                                                    maxparams)) "){")
          (when variadic
            (emits "var ")
            (emit (last maxparams))
            (emitln " = var_args;"))
          (emitln "switch(arguments.length){")
          (doseq [[n meth] ms]
            (if (:variadic? meth)
              (do (emitln "default:")
                  (let [restarg (munge (gensym))]
                    (emitln "var " restarg " = null;")
                    (emitln "if (arguments.length > " max-fixed-arity ") {")
                    (let [a (emit-arguments-to-array max-fixed-arity)]
                      (emitln restarg " = new cljs.core.IndexedSeq(" a ",0,null);"))
                    (emitln "}")
                    (emitln "return " n ".cljs$core$IFn$_invoke$arity$variadic("
                      (comma-sep (butlast maxparams))
                      (when (> (count maxparams) 1) ", ")
                      restarg ");")))
              (let [pcnt (count (:params meth))]
                (emitln "case " pcnt ":")
                (emitln "return " n ".call(this" (if (zero? pcnt) nil
                                                                  (list "," (comma-sep (take pcnt maxparams)))) ");"))))
          (emitln "}")
          (let [arg-count-js (if (= 'self__ (-> ms first val :params first :name))
                               "(arguments.length - 1)"
                               "arguments.length")]
            (emitln "throw(new Error('Invalid arity: ' + " arg-count-js "));"))
          (emitln "};")
          (when variadic
            (emitln mname ".cljs$lang$maxFixedArity = " max-fixed-arity ";")
            (emitln mname ".cljs$lang$applyTo = " (some #(let [[n m] %] (when (:variadic? m) n)) ms) ".cljs$lang$applyTo;"))
          (doseq [[n meth] ms]
            (let [c (count (:params meth))]
              (if (:variadic? meth)
                (emitln mname ".cljs$core$IFn$_invoke$arity$variadic = " n ".cljs$core$IFn$_invoke$arity$variadic;")
                (emitln mname ".cljs$core$IFn$_invoke$arity$" c " = " n ";"))))
          (emitln "return " mname ";")
          (emitln "})()")))
      (when loop-locals
        (emitln ";})(" (comma-sep loop-locals) "))")))))

(defmethod emit* :invoke
  [{f :fn :keys [args env] :as expr}]
  (let [info (:info f)
        fn? (and ana/*cljs-static-fns*
                 (not (:dynamic info))
                 (:fn-var info))
        protocol (:protocol info)
        tag      (ana/infer-tag env (first (:args expr)))
        proto? (and protocol tag
                    (or (and ana/*cljs-static-fns* protocol (= tag 'not-native))
                        (and
                          (or ana/*cljs-static-fns*
                              (:protocol-inline env))
                          (or (= protocol tag)
                              ;; ignore new type hints for now - David
                              (and (not (set? tag))
                                   (not ('#{any clj clj-or-nil clj-nil number string boolean function object array js} tag))
                                   (when-let [ps (:protocols (ana/resolve-existing-var env tag))]
                                     (ps protocol)))))))
        opt-not? (and (= (:name info) 'cljs.core/not)
                      (= (ana/infer-tag env (first (:args expr))) 'boolean))
        ns (:ns info)
        js? (or (= ns 'js) (= ns 'Math) (= 'function (:tag f)))
        goog? (when ns
                (or (= ns 'goog)
                    (when-let [ns-str (str ns)]
                      (= (get (string/split ns-str #"\.") 0 nil) "goog"))
                    (not (contains? (::ana/namespaces @env/*compiler*) ns))))

        keyword? (or (= 'cljs.core/Keyword (ana/infer-tag env f))
                     (let [f (ana/unwrap-quote f)]
                       (and (= (-> f :op) :const)
                            (keyword? (-> f :form)))))
        [f variadic-invoke]
        (if fn?
          (let [arity (count args)
                variadic? (:variadic? info)
                mps (:method-params info)
                mfa (:max-fixed-arity info)]
            (cond
              ;; if only one method, no renaming needed
              (and (not variadic?)
                   (= (count mps) 1))
              [f nil]

              ;; direct dispatch to variadic case
              (and variadic? (> arity mfa))
              [(update-in f [:info]
                 (fn [info]
                   (-> info
                       (assoc :name (symbol (str (munge info) ".cljs$core$IFn$_invoke$arity$variadic")))
                       ;; bypass local fn-self-name munging, we're emitting direct
                       ;; shadowing already applied
                       (update-in [:info]
                         #(-> % (dissoc :shadow) (dissoc :fn-self-name))))))
               {:max-fixed-arity mfa}]

              ;; direct dispatch to specific arity case
              :else
              (let [arities (map count mps)]
                (if (some #{arity} arities)
                  [(update-in f [:info]
                     (fn [info]
                       (-> info
                           (assoc :name (symbol (str (munge info) ".cljs$core$IFn$_invoke$arity$" arity)))
                           ;; bypass local fn-self-name munging, we're emitting direct
                           ;; shadowing already applied
                           (update-in [:info]
                             #(-> % (dissoc :shadow) (dissoc :fn-self-name)))))) nil]
                  [f nil]))))
          [f nil])]
    (emit-wrap env
      (cond
        opt-not?
        (emits "(!(" (first args) "))")

        proto?
        (let [pimpl (str (munge (protocol-prefix protocol))
                         (munge (name (:name info))) "$arity$" (count args))]
          (emits (first args) "." pimpl "(" (comma-sep (cons "null" (rest args))) ")"))

        keyword?
        (emits f ".cljs$core$IFn$_invoke$arity$" (count args) "(" (comma-sep args) ")")

        variadic-invoke
        (let [mfa (:max-fixed-arity variadic-invoke)]
          (emits f "(" (comma-sep (take mfa args))
            (when-not (zero? mfa) ",")
            "cljs.core.prim_seq.cljs$core$IFn$_invoke$arity$2(["
            (comma-sep (drop mfa args)) "], 0))"))

        (or fn? js? goog?)
        (emits f "(" (comma-sep args)  ")")

        :else
        (if (and ana/*cljs-static-fns* (#{:var :local :js-var} (:op f)))
          ;; higher order case, static information missing
          (let [fprop (str ".cljs$core$IFn$_invoke$arity$" (count args))]
            (if ana/*fn-invoke-direct*
              (emits "(" f fprop " ? " f fprop "(" (comma-sep args) ") : "
                f "(" (comma-sep args) "))")
              (emits "(" f fprop " ? " f fprop "(" (comma-sep args) ") : "
                f ".call(" (comma-sep (cons "null" args)) "))")))
          (emits f ".call(" (comma-sep (cons "null" args)) ")"))))))