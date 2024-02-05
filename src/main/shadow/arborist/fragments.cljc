(ns shadow.arborist.fragments
  (:require [clojure.string :as str]))

;; https://developer.mozilla.org/en-US/docs/Glossary/Void_element
(def void-tags
  #{:area
    :base
    :br
    :col
    :embed
    :hr
    :img
    :input
    :link
    :meta
    :param
    :source
    :track
    :wbr})

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

;; used to check if an element in a hiccup vector can be inlined as a string
(defn string-part? [part]
  (or (string? part)
      (number? part)
      (boolean? part)))

(defn const? [env thing]
  (or (string? thing)
      (number? thing)
      (boolean? thing)
      (keyword? thing)
      (= thing 'nil)
      (and (vector? thing) (every? #(const? env %) thing))
      (and (map? thing)
           (reduce-kv
             (fn [r k v]
               (if-not (and (const? env k) (const? env v))
                 (reduced false)
                 r))
             true
             thing))

      ;; treat foo/bar symbols as constants as they are assumed to be def'd vars
      ;; which shouldn't ever change during a lifecycle update
      ;; treat non-local symbols as constants as well since they shouldn't change either

      ;; (def some-class "foo bar")
      ;; (<< [:div {:class some-class} ...])
      ;; (<< [:div {:class styles/card} ...])
      ;; don't ever need to update :class

      (qualified-symbol? thing)
      (and (simple-symbol? thing)
           ;; FIXME: this should maybe resolve instead?
           (not (get-in env [:macro-env :locals thing])))))

;; if all children of an element can be turned into a single string
;; it is more performant doing that than splitting into X elements with potential upgrade paths
;; [:div "foo " bar " baz"] will be a very common occurrence. if we can infer that bar will be string-ish type
;; then we can turn the above into (str "foo " bar " baz") and instead have only a single child, which may even skip
;; the upgrade path and directly set textContent of the parent node

;; based on https://www.measurethat.net/Benchmarks/Show/3618/0/createtextnode-vs-textcontent-vs-innertext-vs-innerhtml

(defn string-ish? [env child]
  ;; adding a ^:text tag, as an option so that things we know are not strings
  ;; don't need to be tagged as ^string, as act solely as fragment option, not maybe have other unintended effects
  (or (:text (meta child))
      (when-some [tag (:tag (meta child))]
        (or (= tag 'string)
            (= tag 'numeric)))
      ;; FIXME: maybe some kind of analysis to detect more things that for sure return a string
      ;; (str ...) calls will be common
      (and (list? child) (= 'str (first child)))
      ;; FIXME: should definitely handle more than just locals
      (and (symbol? child)
           (when-some [var (get-in env [:macro-env :locals child])]
             (let [{:keys [tag]} var]
               ;; FIXME: other tags this should support?
               (or (= tag 'string)
                   (= tag 'numeric)
                   (:text (meta (:name var))))
               )))
      ))

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

    ;; FIXME: make this recognize shadow.css/css uses
    ;; either just check (css ...)
    ;; or the :tag shadow.css/css-id set by the shadow.css/css macro
    ;; since these are constants and cannot change it can skip checking
    ;; them in the update fn.
    ;; they are not const? since they may be locals and not to be passed
    ;; to the fragment functions somehow. this is fine, just update can skip
    ;; some work. but given that it compares two string its probably not needed
    ;; given how cheap it is?

    (merge
      extra
      {:op :code-ref
       :parent (:parent env)
       :src code
       :str-able? (string-ish? env code)
       :sym (if element-id
              (symbol (str "d" element-id)) ;; dynamic "elements"
              (gensym))
       :ref-id id})))

(declare analyze-node)

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

(defn combine-string-parts [children]
  (reduce
    (fn [children child]
      (cond
        (nil? children)
        [child]

        (string-part? child)
        (let [last-idx (dec (count children))
              last-val (nth children last-idx)]
          (if-not (string-part? last-val)
            (conj children child)
            (update children last-idx str child)))

        :else
        (conj children child)))
    nil
    children))

(defn can-interpolate-string?
  [children]
  (every?
    (fn [ast]
      (or (= :text (:op ast))
          (:str-able? ast)))
    children))

(defn as-text-content-part [{:keys [op] :as ast}]
  (case op
    :text
    (:text ast)
    :code-ref
    (:ref-id ast)))

(defn make-text-content [children]
  {:parts (mapv as-text-content-part children)})

(defn analyze-dom-element [{:keys [parent] :as env} [tag-kw attrs :as el]]
  (let [[attrs children]
        (if (and attrs (map? attrs))
          [attrs (subvec el 2)]
          [nil (subvec el 1)])

        children
        (combine-string-parts children)

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
                    (cond
                      (str/starts-with? (name attr-key) "on-")
                      (let [op
                            {:op :event-attr
                             :sym el-sym
                             :element-id id
                             :event (subs (name attr-key) 3)}]

                        (if (const? env attr-value)
                          (assoc op :value attr-value)
                          (assoc op :code (make-code env attr-value {}))))

                      (const? env attr-value)
                      {:op :static-attr
                       :sym el-sym
                       :element-id id
                       :attr attr-key
                       :value attr-value
                       ;; FIXME: maybe limit to known HTML tag attrs. shouldn't set things this way for web components?
                       :html-const (and (simple-keyword? attr-key)
                                        (string-part? attr-value))
                       :src attrs}

                      ;; :class (css ...) is constant, no need to ever update
                      ;; FIXME: shouldn't assume this is shadow.css/css only
                      (and (= :class attr-key)
                           (seq? attr-value)
                           (= 'css (first attr-value)))
                      {:op :static-attr
                       :sym el-sym
                       :element-id id
                       :attr attr-key
                       :value attr-value
                       :src attrs}

                      :else
                      (-> {:op :dynamic-attr
                           :sym el-sym
                           :element-id id
                           :attr attr-key
                           :value (make-code env attr-value {})
                           :src attrs}
                          (cond->
                            ;; special case for (let [$cls (css ...)] ...)
                            ;; so it can skip over updating those since they never change
                            (and (= :class attr-key)
                                 (simple-symbol? attr-value)
                                 (= 'shadow.css/css-id (get-in env [:macro-env :locals attr-value :tag])))
                            (assoc :constant true) ;; no need to update
                            ))
                      )))
             (into []))]

    (when (and (contains? void-tags tag)
               (seq children))
      (throw (ex-info (str tag " can't have child elements") {:tag tag})))

    (let [el
          {:op :element
           :parent parent
           :element-id id
           :sym el-sym
           :tag tag
           :src el
           :attrs attr-ops}

          child-env
          (assoc env :parent [:element el-sym])

          children
          (into [] (map #(analyze-node child-env %)) children)]

      ;; potentially optimize children to skip needlessly complex string handling
      ;; when a simple textContent assignment will do
      (cond
        ;; multiple text children have already been collapsed into one via combine above
        (and (= 1 (count children))
             (= :text (:op (first children))))
        (assoc el :text-content {:static true :text (:text (first children))})

        ;; [:div "foo " ^string x " bar"]
        ;; needs to check if there are actually any children
        ;; otherwise setting textContent to ""
        (and (seq children) (can-interpolate-string? children))
        (assoc el :text-content (make-text-content children))

        :else
        (assoc el :children children)))))

(defn analyze-element [env el]
  (assert (pos? (count el)))

  (let [tag-kw (nth el 0)]
    (cond
      (not (keyword? tag-kw))
      (throw (ex-info "only keywords allowed, use (component {...}) for components"
               (merge (meta el) {:type ::input-error})))

      ;; automatic switch to svg by turning
      ;; [:svg ...] into (svg [:svg ...])
      (and (str/starts-with? (name tag-kw) "svg")
           (not (::svg env)))
      (make-code env
        (with-meta
          `(shadow.arborist/svg ~el)
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

;; build impl using <template> tags and navigating that DOM
;; trouble with that is that some fragments may yield a different DOM structure than expected

;;   <table><tr>...
;; will actually yield a slighty different DOM
;;   <table><tbody><tr>...

;; so it inserts a tbody. code is expecting to get a tr via table.firstChild, but gets a tbody

;; performance wise it makes almost no difference vs just constructing elements via document.createElement
;; so instead of implementing the HTML spec, we just go back to createElement for now

#_(defn make-build-impl [ast sym->idx]
    (let [env-sym (with-meta (gensym "env") {:tag 'not-native})
          vals-sym (with-meta (gensym "vals") {:tag 'array})
          tpl-sym (gensym "tpl_")

          ;; https://www.measurethat.net/Benchmarks/Show/15652/0/childnodes-vs-children-vs-firstchildnextsibling-vs-firs
          ;; based on this it is much faster to walk elements using
          ;; .firstChild/.nextSibling vs array access over .childNodes
          ;; although in a real benchmark using fragments it makes no noticeable difference
          ;; using child traversal since the code a slightly more pleasant to look at

          {:keys [bindings mutations] :as result}
          (reduce
            (fn step-fn [{:keys [prev] :as env} {:keys [op sym parent] :as ast}]
              (let [[parent-type parent-sym] parent]
                (case op
                  :element
                  (-> env
                      (update :bindings conj sym
                        (cond
                          prev
                          `(.-nextSibling ~prev)
                          parent-sym
                          `(.-firstChild ~parent-sym)
                          :else
                          `(.-firstChild ~tpl-sym)))
                      (dissoc :prev)
                      (reduce-> step-fn (:attrs ast))
                      (reduce-> step-fn (:children ast))
                      (assoc :prev sym))

                  :text
                  (-> env
                      (update :bindings conj sym
                        (cond
                          prev
                          `(.-nextSibling ~prev)
                          parent-sym
                          `(.-firstChild ~parent-sym)
                          :else
                          `(.-firstChild ~tpl-sym)))
                      (assoc :prev sym))

                  :code-ref
                  (let [anchor-sym (gensym "anchor_")]
                    (-> env
                        (assoc :prev anchor-sym)
                        (update :bindings conj sym
                          (with-loc ast
                            `(managed-create
                               ~env-sym
                               (aget ~vals-sym ~(:ref-id ast)))))
                        (update :bindings conj anchor-sym
                          (cond
                            prev
                            `(.-nextSibling ~prev)
                            parent-sym
                            `(.-firstChild ~parent-sym)
                            :else
                            `(.-firstChild ~tpl-sym)))

                        (cond->
                          parent-sym
                          (-> (update :mutations conj (with-loc ast `(managed-insert ~sym ~parent-sym ~anchor-sym)))
                              (update :mutations conj `(.remove ~anchor-sym)))
                          )))

                  :static-attr
                  (-> env
                      (cond->
                        (not (:html-const ast))
                        (update :mutations conj (with-loc ast `(set-attr ~env-sym ~(:sym ast) ~(:attr ast) nil ~(:value ast))))))

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

      `(fn [~env-sym ~tpl-sym ~vals-sym]
         (let [~@bindings]
           ~@mutations
           (cljs.core/array ~@return)))))

(defn make-build-impl [ast sym->idx]
  (let [frag-sym (with-meta (gensym "frag") {:tag 'not-native})
        env-sym (with-meta (gensym "env") {:tag 'not-native})
        vals-sym (with-meta (gensym "vals") {:tag 'array})
        element-fn-sym (with-meta (gensym "element-fn") {:tag 'function})

        {:keys [bindings mutations] :as result}
        (reduce
          (fn step-fn [env {:keys [op sym parent] :as ast}]
            (let [[parent-type parent-sym] parent]
              (case op
                :element
                (let [{:keys [tag attrs children text-content]} ast]
                  (-> env
                      (update :bindings conj sym (with-loc ast `(~element-fn-sym ~tag)))
                      (cond->
                        (and parent-sym (= parent-type :element))
                        (update :mutations conj
                          (with-loc ast
                            `(append-child ~parent-sym ~sym)))

                        text-content
                        (update :mutations conj
                          (with-loc ast
                            `(set! ~sym ~'-textContent
                               ~(or (:text text-content)
                                    `(str ~@(map (fn [x] (if (string? x) x `(aget ~vals-sym ~x))) (:parts text-content)))
                                    )))))

                      (reduce-> step-fn attrs)
                      (reduce-> step-fn children)))

                :text
                (-> env
                    (update :bindings conj sym `(create-text ~env-sym ~(:text ast)))
                    (cond->
                      parent-sym
                      (update :mutations conj `(append-child ~parent-sym ~sym))))

                :code-ref
                (-> env
                    (update :bindings conj sym
                      (with-loc ast
                        `(managed-create
                           ~env-sym
                           (aget ~vals-sym ~(:ref-id ast)))))
                    (cond->
                      parent-sym
                      (update :mutations conj (with-loc ast `(managed-append ~parent-sym ~sym)))))

                :event-attr
                (update env :mutations conj
                  (with-loc ast
                    (if (:code ast)
                      `(frag-add-updating-event-listener ~frag-sym
                         ~(:sym ast)
                         ~(:event ast)
                         ~(-> ast :code :ref-id))
                      `(frag-add-static-event-listener ~frag-sym
                         ~(:sym ast)
                         ~(:event ast)
                         ~(:value ast))
                      )))

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

    `(fn [~frag-sym ~env-sym ~vals-sym ~element-fn-sym]
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
              (let [{:keys [attrs children text-content]} ast]

                (-> mutations
                    (cond->
                      (and text-content (not (:static text-content)))
                      (conj
                        (with-loc ast
                          ;; FIXME: how bad is it that strings are repeated here?
                          ;; might be worth storing them somewhere so the emitted JS doesn't contain strings twice
                          ;; but then the lookup might be more expensive than just repeating it
                          ;; I think repeating is fine in a gzip context
                          ;; FIXME: should this even check for changes at all?
                          ;; 3 array lookups instead of 1
                          ;; vs just constructing the string and letting the browser compare?
                          ;; probably doesn't matter either way
                          `(when-not (and ~@(->> (:parts text-content)
                                                 (remove string?)
                                                 (map (fn [id] `(= (aget ~oldv-sym ~id)
                                                                   (aget ~newv-sym ~id))))))
                             (set! (aget ~exports-sym ~(get sym->idx sym)) ~'-textContent
                               (str ~@(map (fn [x] (if (string? x) x `(aget ~newv-sym ~x))) (:parts text-content)))
                               )))))
                    (reduce-> step-fn attrs)
                    (reduce-> step-fn children)))

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

              (:static-attr :event-attr)
              mutations

              :dynamic-attr
              (if (:constant ast)
                mutations ;; no need to update constant attributes
                (let [ref-id (-> ast :value :ref-id)
                      idx (get sym->idx sym)
                      form
                      `(update-attr ~env-sym ~exports-sym ~idx ~(:attr ast) (aget ~oldv-sym ~ref-id) (aget ~newv-sym ~ref-id))]
                  (conj mutations form)))))
          []
          ast)]

    (if (empty? mutations)
      `shadow.arborist.fragments/noop
      `(fn [~this-sym ~env-sym ~exports-sym ~oldv-sym ~newv-sym]
         ~@mutations
         ;; trying to avoid turning any mutation into expr and used as return value
         ;; just generates useless extra code we don't need
         ;; wish there was a :no-return or similar fn annotation in the compiler
         ;; closure :advanced removes "return undefined;" entirely, so this is fine
         js/undefined))))

(defn make-mount-impl [ast sym->idx]
  (let [exports-sym (gensym "exports")
        parent-sym (gensym "parent")
        anchor-sym (gensym "anchor")
        first-op (:op (first ast))]

    (if (and (= 1 (count ast)) (or (= :text first-op) (= :element first-op)))
      ;; for fragments with a single DOM root the insert impl is always the same
      ;; instead of generating the same code over and over just use a dedicated function
      `shadow.arborist.fragments/frag-single-dom-mount
      ;; multiple root elements create custom fn
      (let [mount-calls
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
           ~@mount-calls
           js/undefined)))))

(defn make-destroy-impl [ast sym->idx]
  (let [exports-sym (gensym "exports")
        env-sym (gensym "env")
        oldv-sym (gensym "oldv")
        dom-remove-sym (with-meta (gensym "dom_remove") {:tag 'boolean})]

    ;; FIXME: can we frag-single-dom-destroy easily?
    ;; there may be a single dom root node, but nested code-refs or attributes that need cleanup
    (let [destroy-calls
          (reduce
            (fn step-fn [calls {:keys [op sym] :as ast}]
              (case op
                (:text :element)
                (-> calls
                    (cond->
                      ;; can skip removing nodes when the parent is already removed
                      (nil? (:parent ast))
                      (conj `(when ~dom-remove-sym (dom-remove (aget ~exports-sym ~(get sym->idx sym))))))
                    (reduce-> step-fn (:attrs ast))
                    (reduce-> step-fn (:children ast)))

                :code-ref
                (-> calls
                    (conj `(managed-remove
                             (aget ~exports-sym ~(get sym->idx sym))
                             ~(if (:parent ast)
                                false
                                dom-remove-sym)))
                    (reduce-> step-fn (:children ast)))

                ;; only clean up qualified keywords
                :static-attr
                (if-not (qualified-keyword? (:attr ast))
                  calls
                  (-> calls
                      (conj
                        `(clear-attr ~env-sym ~exports-sym ~(get sym->idx sym) ~(:attr ast) ~(:value ast))
                        )))

                :event-attr
                calls

                :dynamic-attr
                (if-not (qualified-keyword? (:attr ast))
                  calls
                  (-> calls
                      (conj
                        `(clear-attr ~env-sym ~exports-sym ~(get sym->idx sym) ~(:attr ast) (aget ~oldv-sym ~(-> ast :value :ref-id)))
                        )))

                calls))
            []
            ast)]

      `(fn [~env-sym ~exports-sym ~oldv-sym ~dom-remove-sym]
         ~@destroy-calls
         js/undefined))))

(defn make-template-string [{:keys [op] :as ast}]
  (case op
    :element
    (let [{:keys [tag attrs children]} ast]
      (str "<" (name tag) " "
           (->> attrs
                (filter :html-const)
                (map (fn [{:keys [attr value]}]
                       (when value
                         (str (name attr)
                              (when-not (true? value)
                                (str "=" (pr-str (str value))))))))
                (str/join " "))
           ">"
           (when-not (contains? void-tags tag)
             (str (->> children
                       (map make-template-string)
                       (str/join ""))
                  "</" (name tag) ">"))
           ))

    ;; FIXME: might be able to get away without code placeholders
    ;; and smarter navigation through the constructed nodes
    ;; currently accessing everything by index
    ;; these will always be cloned and then immediately removed, so wasted work
    :code-ref
    (str "<!-- #" (:sym ast) " -->")

    :text
    (:text ast)

    ;; else
    ""
    ))

(def shadow-analyze-top
  #?(:cljs
     nil
     :clj
     (try
       (find-var 'shadow.build.compiler/*analyze-top*)
       (catch Exception e
         nil))))

(defn make-fragment [macro-env macro-form body]
  (let [env
        {:code-ref (atom {})
         :el-seq-ref (atom -1) ;; want 0 to be first id
         :macro-env macro-env
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
                    ;; nodes without parent need to be exported as we need to access them for dom-insert
                    ;; nodes with updating textContent need to be exported, since we need the reference for updates
                    (or (nil? parent)
                        (and (:text-content ast) (not (:static (:text-content ast)))))
                    (assoc sym (count sym->idx)))
                  (reduce-> step-fn (:attrs ast))
                  (reduce-> step-fn (:children ast)))

              :code-ref
              (assoc sym->idx sym (count sym->idx))

              :event-attr
              sym->idx

              ;; need references to static qualified keywords
              ;; so they can get cleared properly
              :static-attr
              (if (or (simple-keyword? (:attr ast))
                      (contains? sym->idx sym))
                sym->idx
                (assoc sym->idx sym (count sym->idx)))

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
        `(shadow.arborist.fragments/FragmentCode.
           #_~(->> ast
                   (map make-template-string)
                   (str/join ""))
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
      (if-let [analyze-top (and (not (false? (::optimize macro-env)))
                                shadow-analyze-top
                                @shadow-analyze-top)]
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

(defmacro html [& body]
  (make-fragment &env &form body))

(defmacro svg [& body]
  (make-fragment (assoc &env ::svg true) &form body))

(comment
  (require 'clojure.pprint)
  (tap>
    (make-fragment
      {:locals {'foox {}}}
      nil
      '["before" [:div {:dyn foo} 1 [:div {:foo "bar"} 2]] (yo) [:div [:div 3]] "after"]))


  (clojure.pprint/pprint
    (make-fragment
      {}
      nil
      '[(foo 1 2 3)])))