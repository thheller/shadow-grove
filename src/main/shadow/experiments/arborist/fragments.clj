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
                       :el el-sym
                       :element-id id
                       :attr attr-key
                       :value attr-value
                       :src attrs}

                      {:op :dynamic-attr
                       :el el-sym
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
      (make-code env `(shadow.experiments.arborist/svg ~el) {:element-id (next-el-id env)})

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

(defn make-build-impl [ast]
  (let [this-sym (gensym "this")
        env-sym (with-meta (gensym "env") {:tag 'not-native})
        vals-sym (with-meta (gensym "vals") {:tag 'array})
        element-ns-sym (gensym "element-ns")

        {:keys [bindings mutations return] :as result}
        (reduce
          (fn step-fn [env {:keys [op sym parent] :as ast}]
            (let [[parent-type parent-sym] parent]
              (case op
                :element
                (-> env
                    (update :bindings conj sym (with-loc ast `(create-element ~env-sym ~element-ns-sym ~(:tag ast))))
                    (update :return conj sym)
                    (cond->
                      (and parent-sym (= parent-type :element))
                      (update :mutations conj (with-loc ast `(append-child ~parent-sym ~sym)))

                      (and parent-sym (= parent-type :component))
                      (update :mutations conj (with-loc ast `(component-append ~parent-sym ~sym))))
                    (reduce-> step-fn (:children ast))
                    )

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
                    (update :return conj sym)
                    (cond->
                      parent-sym
                      (update :mutations conj `(append-child ~parent-sym ~sym))))

                :code-ref
                (-> env
                    (update :bindings conj sym (with-loc ast `(managed-create ~env-sym (aget ~vals-sym ~(:ref-id ast)))))
                    (cond->
                      parent-sym
                      (update :mutations conj (with-loc ast `(managed-append ~parent-sym ~sym))))
                    (update :return conj sym))

                :static-attr
                (-> env
                    (update :mutations conj (with-loc ast `(set-attr ~env-sym ~(:el ast) ~(:attr ast) nil ~(:value ast)))))

                :dynamic-attr
                (update env :mutations conj (with-loc ast `(set-attr ~env-sym ~(:el ast) ~(:attr ast) nil (aget ~vals-sym ~(-> ast :value :ref-id)))))
                )))
          {:bindings [element-ns-sym `(::element-ns ~env-sym)]
           :mutations []
           :return []}
          ast)]

    `(fn [~env-sym ~vals-sym]
       (let [~@bindings]
         ~@mutations
         (cljs.core/array ~@return)))))

(defn make-update-impl [ast]
  (let [this-sym (gensym "this")
        env-sym (gensym "env")
        nodes-sym (gensym "nodes")
        oldv-sym (gensym "oldv")
        newv-sym (gensym "newv")

        {:keys [bindings mutations return nodes] :as result}
        (reduce
          (fn step-fn [env {:keys [op sym element-id] :as ast}]
            (case op
              :element
              (-> env
                  (assoc-in [:sym->id sym] element-id)
                  (reduce-> step-fn (:children ast)))

              #_#_:component
                  (-> env
                      (update :mutations conj
                        (with-loc ast
                          `(component-update
                             ~env-sym
                             ~nodes-sym
                             ~(:element-id ast)
                             (aget ~oldv-sym ~(-> ast :component :ref-id))
                             (aget ~newv-sym ~(-> ast :component :ref-id))
                             ~@(if-not (-> ast :attrs)
                                 [{} {}]
                                 [`(aget ~oldv-sym ~(-> ast :attrs :ref-id))
                                  `(aget ~newv-sym ~(-> ast :attrs :ref-id))]))))
                      (reduce-> step-fn (:children ast)))

              :text
              env

              :code-ref
              (-> env
                  (update :mutations conj
                    (let [ref-id (:ref-id ast)]
                      (with-loc ast
                        `(update-managed
                           ~env-sym
                           ~nodes-sym
                           ~(:element-id ast)
                           (aget ~oldv-sym ~ref-id)
                           (aget ~newv-sym ~ref-id))))))

              :static-attr
              env

              :dynamic-attr
              (let [ref-id (-> ast :value :ref-id)
                    form
                    `(update-attr ~env-sym ~nodes-sym ~element-id ~(:attr ast) (aget ~oldv-sym ~ref-id) (aget ~newv-sym ~ref-id))]

                (-> env
                    (update :mutations conj form))))
            )
          {:mutations []
           :sym->id {}}
          ast)]


    `(fn [~env-sym ~nodes-sym ~oldv-sym ~newv-sym]
       ~@mutations)))

(defn make-mount-impl [ast]
  (let [this-sym (gensym "this")
        nodes-sym (gensym "nodes")
        parent-sym (gensym "parent")
        anchor-sym (gensym "anchor")

        mount-calls
        (->> ast
             (map (fn [{:keys [op element-id]}]
                    (case op
                      (:text :element)
                      `(dom-insert-before ~parent-sym (aget ~nodes-sym ~element-id) ~anchor-sym)
                      :code-ref
                      `(managed-insert (aget ~nodes-sym ~element-id) ~parent-sym ~anchor-sym)
                      nil)))
             (remove nil?))]

    `(fn [~nodes-sym ~parent-sym ~anchor-sym]
       ~@mount-calls)))

(defn make-destroy-impl [ast]
  (let [this-sym (gensym "this")
        nodes-sym (gensym "nodes")

        destroy-calls
        (reduce
          (fn step-fn [calls {:keys [op sym element-id] :as ast}]
            (case op
              (:text :element)
              (-> calls
                  (cond->
                    ;; can skip removing nodes when the parent is already removed
                    (nil? (:parent ast))
                    (conj `(dom-remove (aget ~nodes-sym ~element-id))))
                  (reduce-> step-fn (:children ast)))

              :code-ref
              (-> calls
                  (conj `(managed-remove (aget ~nodes-sym ~element-id)))
                  (reduce-> step-fn (:children ast)))

              calls))
          []
          ast)]

    `(fn [~nodes-sym]
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

        code-snippets
        (->> @(:code-ref env)
             (sort-by val)
             (map (fn [[snippet id]]
                    snippet))
             (into []))

        ;; for ns vars only, must not be used as string id
        code-id
        (gensym "fragment__")

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
        `(->FragmentCode
           ~(make-build-impl ast)
           ~(make-mount-impl ast)
           ~(make-update-impl ast)
           ~(make-destroy-impl ast))]

    ;; (clojure.pprint/pprint ast)

    ;; skip fragment if someone did `(<< (something))`, no point in wrapping, just call `(something)`
    (if (and (= 1 (count ast))
             (= :code-ref (:op (first ast))))
      (first body)
      (if-let [analyze-top (and (not (false? (::optimize macro-env))) shadow-analyze-top @shadow-analyze-top)]
        ;; optimal variant, best performance, requires special support from compiler
        (do (analyze-top (with-meta `(def ~code-id ~fragment-code) (meta macro-form)))
            `(fragment-node (cljs.core/array ~@code-snippets) ~ns-hint ~code-id))

        ;; fallback, probably good enough, registers fragments to maintain identity
        `(fragment-node
           (cljs.core/array ~@code-snippets)
           ~ns-hint
           (~'js* "(~{} || ~{})"
             (cljs.core/unchecked-get known-fragments ~frag-id)
             (cljs.core/unchecked-set known-fragments ~frag-id ~fragment-code)))))))


(comment
  (require 'clojure.pprint)
  (do ;; clojure.pprint/pprint
    (make-fragment
      {}
      nil
      '["before" [:div {:dyn (foo)} 1 [:div {:foo "bar"} 2]] (yo) [:div [:div 3]] "after"])))

(comment

  (make-fragment
    {}
    nil
    '[(foo 1 2 3)]))