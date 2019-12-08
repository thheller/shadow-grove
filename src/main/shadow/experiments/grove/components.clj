(ns shadow.experiments.grove.components
  (:require
    [clojure.set :as set]
    [clojure.spec.alpha :as s]
    ))

(defn r-> [init fn coll]
  (reduce fn init coll))

(defn hook-destructure-map [state ref-name {defaults :or :as map}]
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
              (update-in [:hook-bindings] conj key `(~val ~ref-name ~(get defaults key)))
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
                      (update-in state [:hook-bindings] conj sym `(~kw ~ref-name ~(get defaults sym)))
                      ))
                  val)
                (recur more)))

          :else
          (throw (ex-info "unknown destructure" {:entry entry}))
          )))))

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
       (map #(get bindings %))
       (mapcat (fn [{:keys [name type idx]}]
                 [name
                  (case type
                    :arg
                    `(get-arg ~comp-sym ~idx)
                    #_#_:state
                        `(get-state ~comp-sym)
                    :hook
                    `(get-hook-value ~comp-sym ~idx))]
                 ))
       (vec)))

(defn add-regular-hook
  [{:keys [comp-sym hook-idx bindings] :as state} key body]
  (assert (simple-symbol? key) "only support symbol bindings right now, destructure TBD")

  (let [deps (find-used-bindings #{} bindings body)

        hook-deps
        (->> deps
             (map #(get bindings %))
             (filter #(= :hook (:type %)))
             (map :idx)
             (set))

        hook-args
        (->> deps
             (map #(get bindings %))
             (filter #(= :arg (:type %)))
             (map :idx)
             (set))]
    (-> state
        (assoc-in [:bindings key]
          {:type :hook
           :idx hook-idx
           :name key
           :deps deps})

        ;; update already created hooks if they affect this hook
        ;; doing this in the macro so we don't have to calc it at runtime
        (r->
          (fn [state idx]
            (update-in state [:hooks idx :affects] conj hook-idx))
          hook-deps)

        (r->
          (fn [state idx]
            (update-in state [:args idx :affects] conj hook-idx))
          hook-args)

        (update :hooks conj
          {:depends-on hook-deps
           :affects #{}
           :run
           (if (empty? deps)
             `(fn [~comp-sym]
                ~body)
             `(fn [~comp-sym]
                (let ~(let-bindings state deps)
                  ~body)))})
        (update :hook-idx inc))))

(defn add-event-hook
  [{:keys [comp-sym hook-idx bindings] :as state} key body]

  (let [deps (find-used-bindings #{} bindings body)

        hook-deps
        (->> deps
             (map #(get bindings %))
             (filter #(= :hook (:type %)))
             (map :idx)
             (set))

        hook-args
        (->> deps
             (map #(get bindings %))
             (filter #(= :arg (:type %)))
             (map :idx)
             (set))]
    (-> state
        ;; update already created hooks if they affect this hook
        ;; doing this in the macro so we don't have to calc it at runtime
        (r->
          (fn [state idx]
            (update-in state [:hooks idx :affects] conj hook-idx))
          hook-deps)

        (r->
          (fn [state idx]
            (update-in state [:args idx :affects] conj hook-idx))
          hook-args)

        (update :hooks conj
          {:depends-on hook-deps
           :affects #{}
           :run
           (if (empty? deps)
             `(fn [~comp-sym]
                (event-hook ~key
                  ~body))
             `(fn [~comp-sym]
                (let ~(let-bindings state deps)
                  (event-hook ~key
                    ~body))))})
        (update :hook-idx inc))))

(declare analyze-hooks)

(defn add-hook
  [state [key body]]
  (cond
    (simple-symbol? key)
    (add-regular-hook state key body)

    (map? key)
    (let [hook-name (get key :as (gensym))]
      (-> state
          (add-regular-hook hook-name body)
          ;; FIXME: kinda abusing the system here
          ;; :hook-bindings were already processed,
          ;; so we just reset and do it again
          (assoc :hook-bindings [])
          (hook-destructure-map hook-name key)
          (analyze-hooks)))

    (keyword? key)
    (add-event-hook state key body)

    :else
    (throw (ex-info "unsupported hook declaration" {:key key :body body}))))

(defn analyze-hooks [{:keys [hook-bindings] :as state}]
  (reduce
    add-hook
    state
    (partition-all 2 hook-bindings)))

(defn analyze-arg [state idx binding]
  (cond
    (symbol? binding)
    (-> state
        (assoc-in [:args idx] {:name binding :idx idx :affects #{}})
        (assoc-in [:bindings binding] {:type :arg :name binding :idx idx}))

    (map? binding)
    (let [{:keys [as]} binding
          arg-name (or as (gensym (str "arg_" idx "_")))]
      (-> state
          (assoc-in [:args idx] {:name arg-name :idx idx :affects #{}})
          (assoc-in [:bindings arg-name] {:type :arg :name arg-name :idx idx})
          (hook-destructure-map arg-name binding)))

    :else
    (throw (ex-info "unsupported form for component arg" {:binding binding :idx idx}))))

(defn analyze-args [state arg-bindings]
  (reduce-kv analyze-arg state arg-bindings))

(defn analyze-body [{:keys [bindings] :as state} body]
  (let [deps (find-used-bindings #{} bindings body)]
    (assoc state
      :render-used deps
      :render-args (->> deps
                        (map #(get bindings %))
                        (filter #(= :arg (:type %)))
                        (map :idx)
                        (set))
      :render-deps (->> deps
                        (map #(get bindings %))
                        (filter #(= :hook (:type %)))
                        (map :idx)
                        (set)))))

(s/def ::defc-args
  (s/cat
    :comp-name simple-symbol?
    :docstring (s/? string?)
    :opts (s/? map?)
    :bindings vector? ;; FIXME: core.specs for destructure help
    :hook-bindings vector?
    :body (s/* any?)))

(s/fdef defc :args ::defc-args)

(defmacro defc [& args]
  (let [{:keys [comp-name bindings hook-bindings opts body]}
        (s/conform ::defc-args args)

        state
        (-> {:bindings {}
             :comp-sym (gensym "comp")
             :old-args-sym (with-meta (gensym "old") {:tag 'not-native})
             :new-args-sym (with-meta (gensym "new") {:tag 'not-native})
             :args []
             :hooks []
             :opts opts
             :hook-bindings []
             :render-deps #{}
             :render-args #{}
             :hook-idx 0}
            (analyze-args bindings)
            (update :hook-bindings into hook-bindings)
            (analyze-hooks)
            (analyze-body body))]

    ;; assume 30 bits is safe for ints?
    ;; FIXME: JS numbers are weird, how many bits are actually safe to use?
    (when (>= (count (:hooks state)) 30)
      (throw (ex-info "too many hooks" {})))

    ;; (clojure.pprint/pprint state)

    ;; FIXME: put docstring somewhere
    ;; FIXME: figure out how to cheat compiler to it doesn't emit a check when calling these IFn impls
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
         (fn [~(:comp-sym state) ~(:old-args-sym state) ~(:new-args-sym state)]
           (check-args! ~(:comp-sym state) ~(:new-args-sym state) ~(count (:args state)))
           ~@(for [{:keys [idx affects]} (:args state)
                   :let [affects-render? (contains? (:render-args state) idx)
                         affects-hooks? (seq affects)]
                   :when (or affects-render? affects-hooks?)]
               `(when (not=
                        ;; validated to be vectors elsewhere
                        (cljs.core/-nth ~(:old-args-sym state) ~idx)
                        (cljs.core/-nth ~(:new-args-sym state) ~idx))
                  ;; ~@ so that it doesn't leave ugly nil
                  ~@(when affects-render?
                     [`(arg-triggers-render! ~(:comp-sym state) ~idx)])
                  ~@(when affects-hooks?
                      [`(arg-triggers-hooks! ~(:comp-sym state) ~idx ~(reduce bit-set 0 affects))])))
           ;; trailing nil so the above isn't turned into an expression which results in ? : ...
           ;; dunno if there is a way to tell CLJS that this function has no return value
           nil)
         ~(reduce bit-set 0 (:render-deps state))
         (fn [~(:comp-sym state)]
           (let ~(let-bindings state (:render-used state))
             ~@body))))))

(comment
  (require 'clojure.pprint)
  (clojure.pprint/pprint
    (macroexpand '(defc hello [arg1 arg2]
                    [{::keys [foo] :as x}
                     (query arg2 [::foo])
                     bar [arg1 arg2]]
                    [:div arg1 foo bar]))))

(comment
  (macroexpand '(defc hello
                  [{x :foo :keys [y] ::keys [z z2 z3] :as p}
                   {sx :sx ::keys [sy sz] :as s}]
                  [a (hello-hook 1 p)
                   {:keys [b2] :as b} (hello-hook 2 s)
                   c (hello-hook 3 {:hello a :world b})
                   d (hello-hook 4 [a b c x y z sy sz])
                   ::some-event!
                   (fn [env e a]
                     (prn [:ev e a]))
                   a (hello-hook 5 [:shadow a])]
                  [:div a [:h1 d]])))
