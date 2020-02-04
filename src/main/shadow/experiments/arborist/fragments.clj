(ns shadow.experiments.arborist.fragments
  (:require [clojure.string :as str]))

(defn parse-tag [spec]
  (let [spec (name spec)
        fdot (.indexOf spec ".")
        fhash (.indexOf spec "#")]
    (cond
      (and (= -1 fdot) (= -1 fhash))
      [spec nil nil]

      (= -1 fhash)
      [(subs spec 0 fdot)
       nil
       (str/replace (subs spec (inc fdot)) #"\." " ")]

      (= -1 fdot)
      [(subs spec 0 fhash)
       (subs spec (inc fhash))
       nil]

      (> fhash fdot)
      (throw (str "cant have id after class?" spec))

      :else
      [(subs spec 0 fhash)
       (subs spec (inc fhash) fdot)
       (str/replace (.substring spec (inc fdot)) #"\." " ")])))

(defn const? [thing]
  (or (string? thing)
      (number? thing)
      (boolean? thing)
      (keyword? thing)
      (= thing 'nil)
      (and (vector? thing) (every? const? thing))
      (and (map? thing)
           (reduce-kv
             (fn [r k v]
               (if-not (and (const? k) (const? v))
                 (reduced false)
                 r))
             true
             thing))))

(defn next-el-id [{:keys [el-seq-ref] :as env}]
  (swap! el-seq-ref inc))

(defn make-code [{:keys [code-ref] :as env} code {:keys [element-id] :as extra}]
  ;; FIXME: de-duping code may eliminate wanted side effects?
  ;; (<< [:div (side-effect) (side-effect)])
  ;; this is probably fine since the side-effect should be done elsewhere anyways
  (let [id (or (get @code-ref code)
               (let [next-id (count @code-ref)]
                 (swap! code-ref assoc code next-id)
                 next-id))]

    (merge
      extra
      {:op :code-ref
       :parent (:parent env)
       :sym (if element-id
              (symbol (str "d" element-id)) ;; dynamic "elements"
              (gensym))
       :ref-id id})))

(declare analyze-node)

(defn analyze-component [env [component attrs :as el]]
  ;; FIXME: decide if [some-component ...] should be allowed alongside [:div ...]
  ;; probably going to remove [some-component arg1 arg2] since it makes guessing if there are attributes
  ;; annoying and calling (some-component arg1 arg2) makes it clear that its a regular fn call
  ;; the only place where the vector style makes sense is for passing "slot" elements
  ;; [some-component {:props 1} [:h1 "hello"] ...] but that is enough of a special case
  ;; to warrant special syntax, this isn't too bad and avoids all ambiguities when parsing hiccup
  ;; [:> (some-component {:props 1})
  ;;   [:h1 "hello"]
  ;;   [:p "world]]
  ;; multi slot?
  ;; [:> (some-component {:props 1})
  ;;   [:slot/title
  ;;     [:h1 "hello"]]
  ;;   [:slot/body
  ;;     [:p "world"]]]

  ;; so the only remaining cause is [some-kw ...] if someone wants to dynamically switch between :div :button or so
  ;; but that can also by covered by [:> (some-helper :div {:props 1}) ...]
  (throw (ex-info "only keywords allowed, use (component {...}) for components"
           (merge (meta el) {:type ::input-error})))

  (assert (>= (count el) 1))
  (let [id (next-el-id env)
        el-sym (symbol (str "c" id))

        [attrs children]
        (if (and attrs (map? attrs))
          [attrs (subvec el 2)]
          [nil (subvec el 1)])

        child-env
        (assoc env :parent [:component el-sym])]

    {:op :component
     :parent (:parent env)
     :component (make-code env component {})
     :attrs (when attrs (make-code env attrs {}))
     :element-id id
     :sym el-sym
     :src el
     :children (into [] (map #(analyze-node child-env %)) children)}))

(defn with-loc [{:keys [src] :as ast} form]
  (if-not src
    form
    (let [m (meta src)]
      (if-not m
        form
        (with-meta form m)))))

(defn maybe-css-join [{:keys [class] :as attrs} html-class]
  (if-not class
    (assoc attrs :class html-class)
    (assoc attrs :class `(css-join ~html-class ~class))))

(defn analyze-dom-element [{:keys [parent] :as env} [tag-kw attrs :as el]]
  (let [[attrs children]
        (if (and attrs (map? attrs))
          [attrs (subvec el 2)]
          [nil (subvec el 1)])

        [tag html-id html-class]
        (parse-tag tag-kw)

        id (next-el-id env)

        el-sym (symbol (str "el" id "_" tag))

        ;; FIXME: try to do this via fspec, the errors don't show the correct line
        _ (when (and html-id (:id attrs))
            (throw (ex-info "cannot have :id attribute AND el#id"
                     (merge (meta el)
                       {:type ::input-error
                        :tag-kw tag-kw
                        :attrs attrs}))))

        attrs
        (-> attrs
            (cond->
              html-id
              (assoc :id html-id)

              html-class
              (maybe-css-join html-class)))

        tag (keyword (namespace tag-kw) tag)

        attr-ops
        (->> attrs
             (map (fn [[attr-key attr-value]]
                    (if (const? attr-value)
                      {:op :static-attr
                       :sym el-sym
                       :element-id id
                       :attr attr-key
                       :value attr-value
                       :src attrs}

                      {:op :dynamic-attr
                       :sym el-sym
                       :element-id id
                       :attr attr-key
                       :value (make-code env attr-value {})
                       :src attrs}
                      )))
             (into []))

        child-env
        (assoc env :parent [:element el-sym])]

    {:op :element
     :parent parent
     :element-id id
     :sym el-sym
     :tag tag
     :src el
     :children
     (-> []
         (into attr-ops)
         (into (map #(analyze-node child-env %)) children))}))

(defn analyze-element [env el]
  (assert (pos? (count el)))

  (let [tag-kw (nth el 0)]
    (cond
      (not (keyword? tag-kw))
      (analyze-component env el)

      #_#_(= :> tag-kw)
          (analyze-slotted env el)

      ;; automatic switch to svg by turning
      ;; [:svg ...] into (svg [:svg ...])
      (and (str/starts-with? (name tag-kw) "svg")
           (not (::svg env)))
      (make-code env
        (with-meta
          `(shadow.experiments.arborist/svg ~el)
          (meta el))
        {:element-id (next-el-id env)})

      ;; FIXME: could analyze completely static elements and emit actual HTML strings and use DocumentFragment at runtime
      ;; that could potentially be faster with lots of completely static elements but that probably won't be too common
      :else
      (analyze-dom-element env el))))

(defn analyze-text [env node]
  {:op :text
   :element-id (next-el-id env)
   :parent (:parent env)
   :sym (gensym "text_")
   :text node})

(defn analyze-node [env node]
  (cond
    (vector? node)
    (analyze-element env node)

    (string? node)
    (analyze-text env node)

    (number? node)
    (analyze-text env (str node))

    :else
    (make-code env node {:element-id (next-el-id env)})))

(defn reduce-> [init reduce-fn coll]
  (reduce reduce-fn init coll))

(defn make-build-impl [ast sym->idx]
  (let [this-sym (gensym "this")
        env-sym (with-meta (gensym "env") {:tag 'not-native})
        vals-sym (with-meta (gensym "vals") {:tag 'array})
        element-fn-sym (with-meta (gensym "element-fn") {:tag 'function})

        {:keys [bindings mutations] :as result}
        (reduce
          (fn step-fn [env {:keys [op sym parent] :as ast}]
            (let [[parent-type parent-sym] parent]
              (case op
                :element
                (-> env
                    (update :bindings conj sym (with-loc ast `(~element-fn-sym ~(:tag ast))))
                    (cond->
                      (and parent-sym (= parent-type :element))
                      (update :mutations conj (with-loc ast `(append-child ~parent-sym ~sym)))

                      (and parent-sym (= parent-type :component))
                      (update :mutations conj (with-loc ast `(component-append ~parent-sym ~sym))))
                    (reduce-> step-fn (:children ast)))

                #_#_:component
                    (-> env
                        (update :bindings conj sym
                          (with-loc ast
                            `(component-create ~env-sym
                               (aget ~vals-sym ~(-> ast :component :ref-id))
                               ~(if-not (:attrs ast)
                                  {}
                                  `(aget ~vals-sym ~(-> ast :attrs :ref-id))))))
                        (update :return conj sym)
                        (cond->
                          parent-sym
                          (update :mutations conj (with-loc ast `(managed-append ~parent-sym ~sym))))
                        (reduce-> step-fn (:children ast)))

                :text
                (-> env
                    (update :bindings conj sym `(create-text ~env-sym ~(:text ast)))
                    (cond->
                      parent-sym
                      (update :mutations conj `(append-child ~parent-sym ~sym))))

                :code-ref
                (-> env
                    (update :bindings conj sym (with-loc ast `(managed-create ~env-sym (aget ~vals-sym ~(:ref-id ast)))))
                    (cond->
                      parent-sym
                      (update :mutations conj (with-loc ast `(managed-append ~parent-sym ~sym)))))

                :static-attr
                (-> env
                    (update :mutations conj (with-loc ast `(set-attr ~env-sym ~(:sym ast) ~(:attr ast) nil ~(:value ast)))))

                :dynamic-attr
                (update env :mutations conj (with-loc ast `(set-attr ~env-sym ~(:sym ast) ~(:attr ast) nil (aget ~vals-sym ~(-> ast :value :ref-id)))))
                )))
          {:bindings []
           :mutations []}
          ast)

        return
        (->> sym->idx
             (map identity)
             (sort-by second)
             (map first))]

    `(fn [~env-sym ~vals-sym ~element-fn-sym]
       (let [~@bindings]
         ~@mutations
         (cljs.core/array ~@return)))))

(defn make-update-impl [ast sym->idx]
  (let [this-sym (gensym "this")
        env-sym (gensym "env")
        exports-sym (gensym "exports")
        oldv-sym (gensym "oldv")
        newv-sym (gensym "newv")

        mutations
        (reduce
          (fn step-fn [mutations {:keys [op sym] :as ast}]
            (case op
              :element
              (reduce-> mutations step-fn (:children ast))

              #_#_:component
                  (-> env
                      (update :mutations conj
                        (with-loc ast
                          `(component-update
                             ~env-sym
                             ~exports-sym
                             ~(:element-id ast)
                             (aget ~oldv-sym ~(-> ast :component :ref-id))
                             (aget ~newv-sym ~(-> ast :component :ref-id))
                             ~@(if-not (-> ast :attrs)
                                 [{} {}]
                                 [`(aget ~oldv-sym ~(-> ast :attrs :ref-id))
                                  `(aget ~newv-sym ~(-> ast :attrs :ref-id))]))))
                      (reduce-> step-fn (:children ast)))

              :text
              mutations

              :code-ref
              (conj mutations
                (let [ref-id (:ref-id ast)]
                  (with-loc ast
                    `(update-managed
                       ~this-sym
                       ~env-sym
                       ~exports-sym
                       ~(get sym->idx sym)
                       (aget ~oldv-sym ~ref-id)
                       (aget ~newv-sym ~ref-id)))))

              :static-attr
              mutations

              :dynamic-attr
              (let [ref-id (-> ast :value :ref-id)
                    form
                    `(update-attr ~env-sym ~exports-sym ~(get sym->idx sym) ~(:attr ast) (aget ~oldv-sym ~ref-id) (aget ~newv-sym ~ref-id))]
                (conj mutations form))))
          []
          ast)]


    `(fn [~this-sym ~env-sym ~exports-sym ~oldv-sym ~newv-sym]
       ~@mutations)))

(defn make-mount-impl [ast sym->idx]
  (let [this-sym (gensym "this")
        exports-sym (gensym "exports")
        parent-sym (gensym "parent")
        anchor-sym (gensym "anchor")

        mount-calls
        (->> ast
             (map (fn [{:keys [op sym]}]
                    (case op
                      (:text :element)
                      `(dom-insert-before ~parent-sym (aget ~exports-sym ~(get sym->idx sym)) ~anchor-sym)
                      :code-ref
                      `(managed-insert (aget ~exports-sym ~(get sym->idx sym)) ~parent-sym ~anchor-sym)
                      nil)))
             (remove nil?))]

    `(fn [~exports-sym ~parent-sym ~anchor-sym]
       ~@mount-calls)))

(defn make-destroy-impl [ast sym->idx]
  (let [this-sym (gensym "this")
        exports-sym (gensym "exports")

        destroy-calls
        (reduce
          (fn step-fn [calls {:keys [op sym] :as ast}]
            (case op
              (:text :element)
              (-> calls
                  (cond->
                    ;; can skip removing nodes when the parent is already removed
                    (nil? (:parent ast))
                    (conj `(dom-remove (aget ~exports-sym ~(get sym->idx sym)))))
                  (reduce-> step-fn (:children ast)))

              :code-ref
              (-> calls
                  (conj `(managed-remove (aget ~exports-sym ~(get sym->idx sym))))
                  (reduce-> step-fn (:children ast)))

              calls))
          []
          ast)]

    `(fn [~exports-sym]
       ~@destroy-calls)))

(def shadow-analyze-top
  (try
    (find-var 'shadow.build.compiler/*analyze-top*)
    (catch Exception e
      nil)))

(defn make-fragment [macro-env macro-form body]
  (let [env
        {:code-ref (atom {})
         :el-seq-ref (atom -1) ;; want 0 to be first id
         ;; :parent (gensym "root")
         ::svg (::svg macro-env)}

        ast
        (mapv #(analyze-node env %) body)

        ;; collects all symbols that need to be "exported" into the array
        ;; so that mount/update/destroy can reference them by index later
        ;; this is done to avoid large arrays for fragments with many static elements
        ;; don't need to track elements that can't change
        sym->idx
        (reduce
          (fn step-fn [sym->idx {:keys [op parent sym] :as ast}]
            (case op
              (:text :element)
              (-> sym->idx
                  (cond->
                    (nil? parent)
                    (assoc sym (count sym->idx)))
                  (reduce-> step-fn (:children ast)))

              :code-ref
              (assoc sym->idx sym (count sym->idx))

              :static-attr
              sym->idx

              :dynamic-attr
              (if (contains? sym->idx sym)
                sym->idx
                (assoc sym->idx sym (count sym->idx)))))
          {}
          ast)

        code-snippets
        (->> @(:code-ref env)
             (sort-by val)
             (map (fn [[snippet id]]
                    snippet))
             (into []))

        ;; for ns vars only, must not be used as string id
        code-id
        (if-let [{:keys [line column]} (meta macro-form)]
          ;; if we have a source location use it as the var name
          ;; avoids creating too many ns vars when hot-reloading code
          ;; with many fragments there is a good chance not all of their locations change
          ;; thus re-using a name. gensym is random which isn't a huge problem
          ;; but after many hot-reloads you can end up with hundreds of them
          (symbol (str "fragment_l" line "_c" column))
          ;; fallback if no source location is available for some reason
          ;; shouldn't happen but no big deal if it does
          (gensym "fragment__"))

        ;; if [:svg...] is encountered it'll be turned to (svg [:svg...])
        ;; ns-hint tells the FragmentNode to modify the env in as-managed
        ;; it sets ::element-ns if not nil
        ;; once inside svg it'll stay svg so that conditionals don't accidentally start emitting html again
        ;; [:svg
        ;;  (when some-thing
        ;;    (<> [:g ...]))]
        ;; this would be a mistake otherwise and require (svg [:g ...]) but we can be smart
        ;; about this without too much additional cost so that the user doesn't need to worry about this
        ;; FIXME: need foreignObject support to actually switch back to HTML inside an svg
        ;; can't use nil for that since the logic will keep the current for nil
        ;; FIXME: mathml? still don't know what that is ... but seems to be a standard?
        ns-hint
        (when (::svg macro-env)
          `svg-ns)

        ;; this needs to be unique enough to not have collisions when using caching
        ;; just code-id isn't unique enough since multiple namespaces may end up with
        ;; fragment__123 when using incremental compiles and caching. adding the ns
        ;; ensures that can't happen because when a ns is changed all its ids will as well

        ;; closure will shorten this in :advanced by using the fragment-id generator
        ;; so length does not matter
        frag-id
        `(fragment-id ~(str *ns* "/" code-id))


        fragment-code
        `(shadow.experiments.arborist.fragments/FragmentCode.
           ~(make-build-impl ast sym->idx)
           ~(make-mount-impl ast sym->idx)
           ~(make-update-impl ast sym->idx)
           ~(make-destroy-impl ast sym->idx))]

    ;;(clojure.pprint/pprint ast)
    ;; (clojure.pprint/pprint sym->idx)

    ;; skip fragment if someone did `(<< (something))`, no point in wrapping, just return (something)
    ;; auto-upgrades (<< [:svg ...]) to (svg [:svg ...]) for simplicity
    (if (and (= 1 (count ast))
             (= :code-ref (:op (first ast))))
      (-> @(:code-ref env) first key)
      (if-let [analyze-top (and (not (false? (::optimize macro-env))) shadow-analyze-top @shadow-analyze-top)]
        ;; optimal variant, best performance, requires special support from compiler
        (do (analyze-top (with-meta `(def ~code-id ~fragment-code) (meta macro-form)))
            `(fragment-init (cljs.core/array ~@code-snippets) ~ns-hint ~code-id))

        ;; fallback, probably good enough, registers fragments to maintain identity
        `(fragment-init
           (cljs.core/array ~@code-snippets)
           ~ns-hint
           (~'js* "(~{} || ~{})"
             (cljs.core/unchecked-get known-fragments ~frag-id)
             (cljs.core/unchecked-set known-fragments ~frag-id ~fragment-code)))))))


(comment
  (require 'clojure.pprint)
  (clojure.pprint/pprint
    (make-fragment
      {}
      nil
      '["before" [:div {:dyn (foo)} 1 [:div {:foo "bar"} 2]] (yo) [:div [:div 3]] "after"]))


  (clojure.pprint/pprint
    (make-fragment
      {}
      nil
      '[(foo 1 2 3)])))