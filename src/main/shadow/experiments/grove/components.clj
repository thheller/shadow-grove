(ns shadow.experiments.grove.components
  (:require [clojure.spec.alpha :as s]
            [clojure.set :as set]))

(defn r-> [init rfn coll]
  (reduce rfn init coll))

(defn rkv-> [init rfn coll]
  (reduce-kv rfn init coll))

(def set-conj (fnil conj #{}))

;; FIXME: this will mistakenly match "shadow" bindings
;; (defc some-component [env props state]
;;   [x (let [env (:thing props)]
;;        (uses env))])
;; would make env a dependency of some-hook although it isn't
;; should be rather unlikely though so doesn't matter for now

(defn find-fn-args [args]
  {:pre [(vector? args)]}
  (reduce
    (fn [names arg]
      (cond
        (symbol? arg)
        (conj names arg)

        (and (map? arg) (:as arg))
        (conj names (:as arg))

        (vector? arg)
        (throw (ex-info "too lazy for vector removal right now" {:arg arg}))
        ))
    #{}
    args))

(defn find-used-bindings [used bindings form]
  (cond
    (contains? bindings form)
    (conj used form)

    (map? form)
    (reduce-kv
      (fn [used key value]
        (-> used
            (find-used-bindings bindings key)
            (find-used-bindings bindings value)))
      used
      form)

    ;; attempt to at least filter out (fn [some thing] ...)
    (and (seq? form) (= 'fn (first form)))
    (let [[_ maybe-vec & more] form

          shadows
          (if (symbol? maybe-vec)
            (find-fn-args (first more))
            (find-fn-args maybe-vec))

          used
          (reduce #(find-used-bindings %1 bindings %2) used form)

          res
          (set/difference used shadows)]

      res)

    (coll? form)
    (reduce #(find-used-bindings %1 bindings %2) used form)

    :else
    used))

(defn let-bindings [{:keys [comp-sym bindings]} deps]
  (->> deps
       (map (fn [sym]
              (or (get bindings sym)
                  (throw (ex-info (str "can't find let binding: " sym) {:sym sym :bindings bindings :deps deps})))))
       (mapcat (fn [{:keys [name type idx]}]
                 [name
                  (case type
                    :arg
                    `(get-arg ~comp-sym ~idx)
                    #_#_:state
                        `(get-state ~comp-sym)
                    :hook
                    `(get-hook-value ~comp-sym ~idx)
                    )]))
       (vec)))

(defn bindings-indexes-of-type [{:keys [bindings] :as state} deps type]
  (->> deps
       (map #(get bindings %))
       (filter #(= type (:type %)))
       (map :idx)
       (set)))

(defn update-affects [{:keys [bindings] :as state} deps hook-idx]
  (reduce
    (fn [state dep-sym]
      (let [{:keys [type idx] :as binding} (get bindings dep-sym)]
        (update-in state [(case type :hook :hooks :arg :args) idx :affects] set-conj hook-idx)))
    state
    deps))

(defn add-destructure-binding
  [{:keys [comp-sym hook-idx] :as state} ref-name binding-name kw defaults]
  (-> state
      (update-affects #{ref-name} hook-idx)
      (assoc-in [:bindings binding-name]
        {:type :hook
         :idx hook-idx
         :name binding-name
         :deps #{ref-name}})

      (update :hooks conj
        {:depends-on
         (let [{:keys [type idx] :as ref} (get-in state [:bindings ref-name])]
           (if (not= type :hook)
             #{} ;; args are tracked elsewhere, this is only for hooks depending on other hooks
             #{idx}))
         :provides binding-name
         :run
         `(fn [~comp-sym]
            (let ~(let-bindings state #{ref-name})
              (~kw ~ref-name ~(get defaults binding-name))))})
      (update :hook-idx inc)))

(defn hook-destructure-map
  [state
   ref-name
   {defaults :or :as map}]

  (loop [state state
         [entry & more] (dissoc map :or :as)]
    (if-not entry
      state
      (let [[key val] entry]
        (cond
          ;; {foo :bar}
          (and (symbol? key)
               (keyword? val))
          (-> state
              (add-destructure-binding ref-name key val defaults)
              (recur more))

          ;; {:keys [bar] ::keys [foo]}
          (and (= "keys" (name key))
               (vector? val))
          (let [ns (namespace key)]
            (-> state
                (r->
                  (fn [state sym]
                    (assert (simple-symbol? sym) "don't support keywords in destructure yet")
                    (let [kw (keyword ns (name sym))]
                      (add-destructure-binding state ref-name sym kw defaults)))
                  val)
                (recur more)))

          :else
          (throw (ex-info "unknown destructure" {:entry entry}))
          )))))

(defmulti analyze-hook (fn [state hook] (:name hook)))

(defn analyze-arg
  [state idx binding]
  (cond
    (symbol? binding)
    (-> state
        (assoc-in [:args idx] {:name binding :idx idx})
        (assoc-in [:bindings binding] {:type :arg :name binding :idx idx}))

    (map? binding)
    (let [{:keys [as]} binding
          arg-name (or as (gensym (str "arg_" idx "_")))]
      (-> state
          (assoc-in [:args idx] {:name arg-name :idx idx})
          (assoc-in [:bindings arg-name] {:type :arg :name arg-name :idx idx})
          (hook-destructure-map arg-name binding)))

    :else
    (throw (ex-info "unsupported form for component arg" {:binding binding :idx idx}))))

(defn add-data-binding
  [{:keys [comp-sym hook-idx bindings] :as state}
   [binding & body]]

  (let [binding-name
        (cond
          (simple-symbol? binding)
          binding

          (map? binding)
          (or (get binding :as)
              (symbol (str "__hook$" hook-idx)))

          ;; FIXME: vector destructure
          :else
          (throw (ex-info "invalid data binding" {:binding binding})))

        deps
        (find-used-bindings #{} bindings body)]

    (-> state
        ;; must be done first, otherwise shadowed bindings affect themselves
        ;; must track these here by id since names may be shadowed later
        (update-affects deps hook-idx)

        (cond->
          ;; don't make _ an accessible name when using (bind _ "something")
          ;; should be convention for unused name, accessing it should be an error
          (not= '_ binding-name)
          (assoc-in [:bindings binding-name]
            {:type :hook
             :idx hook-idx
             :name binding-name
             :deps deps}))

        (update :hooks conj
          {:all-deps deps
           :depends-on (bindings-indexes-of-type state deps :hook)
           :provides binding-name
           :run
           (if (empty? deps)
             `(fn [~comp-sym]
                ~@body)
             `(fn [~comp-sym]
                (let ~(let-bindings state deps)
                  ~@body)))})
        (update :hook-idx inc)

        (cond->
          ;; FIXME: vector destructure
          (map? binding)
          (hook-destructure-map binding-name binding)))))

(defmethod analyze-hook 'bind [state {:keys [body]}]
  ;; (bind some-name (something-producing-a-value-or-hook))

  ;; don't want to deal with render using bindings not yet declared
  (when (contains? state :render-fn)
    (throw (ex-info "data bindings can only be defined before render" {})))

  (add-data-binding state body))

(defmethod analyze-hook 'hook [state {:keys [body]}]
  ;; (hook (some-hook-without-output))

  ;; purely for side effects where the hook may hook into the lifecycle of the component
  (add-data-binding state (into ['_] body)))

(defmethod analyze-hook 'render
  [{:keys [comp-sym bindings] :as state} {:keys [body] :as hook}]
  ;; (render something)
  (when (contains? state :render-fn)
    (throw (ex-info "render already defined" {})))

  (let [deps (find-used-bindings #{} bindings body)]

    (assoc state
      :render-used deps
      :render-fn
      `(fn [~comp-sym]
         (let ~(let-bindings state deps)
           ~@body)))))

(defmethod analyze-hook 'event
  [{:keys [comp-sym bindings] :as state} {:keys [body] :as hook}]
  ;; (event ::ev-id [env e] (do-something))
  (let [[ev-id ev-args & body] body]

    (when-not (qualified-keyword? ev-id)
      (throw (ex-info "event requires qualified keyword identifier" {:ev-id ev-id})))

    (cond
      ;; (event ::some-id some-other-fn)
      ;; no access to hook data, just calls fn
      (and (symbol? ev-args) (nil? body))
      (assoc-in state [:events ev-id] ev-args)

      ;; (event ::some-id [env e] do-stuff)
      ;; removing [env e] from bindings since they would otherwise override the actual args
      (vector? ev-args)
      (let [inner-bindings
            (reduce
              (fn [bindings arg]
                (cond
                  (symbol? arg)
                  (dissoc bindings arg)

                  (and (map? arg) (:as arg))
                  (dissoc bindings (:as arg))

                  ;; FIXME: should support [a b :as x], should remove x.
                  (vector? arg)
                  (throw (ex-info "vector destructure not yet supported" {}))

                  :else
                  bindings))

              bindings
              ev-args)

            deps
            (find-used-bindings #{} inner-bindings body)]

        (assoc-in state [:events ev-id]
          (if (empty? deps)
            `(fn ~ev-args
               ~@body
               ;; ev-fn return value is ignored anyways, don't turn above into expression
               nil)
            `(fn ~ev-args
               ;; FIXME: this is kinda hacky, the component calls (event-fn env e ...)
               ;; and then we get the component out of the env
               ;; but I don't want the component to be part of the event signature
               ;; and I don't want to rewrite the ev-args to add the comp binding
               ;; since I can't to that for (event ::foo some-fn) without going through too much apply
               (let ~(into [comp-sym `(get-component ~(first ev-args))]
                       (let-bindings state deps))
                 ~@body)
               ;; ev-fn return value is ignored anyways, don't turn above into expression
               nil))))

      :else
      (throw (ex-info "invalid event declaration" {:hook hook})))))

(defn hook-symbol? [x]
  (and (symbol? x)
       (contains? (methods analyze-hook) x)))

(s/def ::defc-hook
  (s/cat
    :name hook-symbol?
    :body (s/* any?)))

(s/def ::defc-args
  (s/cat
    :comp-name simple-symbol?
    :docstring (s/? string?)
    :opts (s/? map?)
    :args vector? ;; FIXME: core.specs for destructure help
    ;; + since it requires at least (render ...)
    :hooks (s/+ (s/spec ::defc-hook))))

(s/fdef defc :args ::defc-args)

(defmacro defc [& args]
  (let [{:keys [comp-name args hooks opts] :as c}
        (s/conform ::defc-args args)

        {:keys [new-args-sym old-args-sym comp-sym render-used render-fn] :as state}
        (-> {:bindings {}
             :comp-sym (gensym "comp")
             :old-args-sym (with-meta (gensym "old") {:tag 'not-native})
             :new-args-sym (with-meta (gensym "new") {:tag 'not-native})
             :args []
             :hooks []
             :events {}
             :opts opts
             :effect-idx 0
             :hook-idx 0}
            (rkv-> analyze-arg args)
            (r-> analyze-hook hooks))

        render-deps
        (bindings-indexes-of-type state render-used :hook)

        render-args
        (bindings-indexes-of-type state render-used :arg)]

    ;; assume 30 bits is safe for ints?
    ;; FIXME: JS numbers are weird, how many bits are actually safe to use?
    (when (>= (:hook-idx state) 30)
      (throw (ex-info "too many hooks" {})))

    (when-not render-fn
      (throw (ex-info "missing render" {})))

    ;; (clojure.pprint/pprint state)

    ;; FIXME: put docstring somewhere if present
    `(def ~(vary-meta comp-name assoc :tag 'not-native)
       (make-component-config
         ~(str *ns* "/" comp-name)
         (cljs.core/array
           ~@(->> (:hooks state)
                  (map (fn [{:keys [depends-on affects run]}]
                         `(make-hook-config
                            ~(reduce bit-set 0 depends-on)
                            ~(reduce bit-set 0 affects)
                            ~run)))))
         ~(or opts {})
         ;; fn that checks args and invalidates hooks or sets render-required
         (fn [~comp-sym ~old-args-sym ~new-args-sym]
           (check-args! ~comp-sym ~new-args-sym ~(count (:args state)))
           ~@(for [{:keys [idx affects]} (:args state)
                   :let [affects-render? (contains? render-args idx)
                         affects-hooks? (seq affects)]
                   :when (or affects-render? affects-hooks?)]
               `(when (not=
                        ;; validated to be vectors elsewhere
                        (cljs.core/-nth ~old-args-sym ~idx)
                        (cljs.core/-nth ~new-args-sym ~idx))
                  ;; ~@ so that it doesn't leave ugly nil
                  ~@(when affects-render?
                      [`(arg-triggers-render! ~comp-sym ~idx)])
                  ~@(when affects-hooks?
                      [`(arg-triggers-hooks! ~comp-sym ~idx ~(reduce bit-set 0 affects))])))
           ;; trailing nil so the above isn't turned into an expression which results in ? : ...
           ;; dunno if there is a way to tell CLJS that this function has no return value
           nil)
         ~(reduce bit-set 0 render-deps)
         ~render-fn
         ~(:events state)
         ))))

(comment
  (require 'clojure.pprint)
  (clojure.pprint/pprint
    (macroexpand
      '(defc hello
         [{x :foo :keys [y] ::keys [z z2 z3] :as p}
          {sx :sx ::keys [sy sz] :as s}
          unused-arg]

         (data {:keys [a]}
           (hello-hook 1 p))

         (data {:keys [b2] :as b}
           (hello-hook 2 s))

         (data c
           (hello-hook 3 {:hello a :world b}))

         (data d
           (hello-hook 4 [a b c x y z sy sz]))

         (event ::some-event! [env e a]
           (prn [:ev e a]))

         (event ::some-event handled-elsewhere!)

         (data a (hello-hook 5 [:shadow a]))

         (render
           [:div a [:h1 d]])))))