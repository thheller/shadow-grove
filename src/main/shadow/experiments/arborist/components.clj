(ns shadow.experiments.arborist.components
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

(defn add-props-binding [state sym-or-map]
  (cond
    (symbol? sym-or-map)
    (-> state
        (assoc :props-name sym-or-map)
        (assoc-in [:bindings sym-or-map] {:type :props :name sym-or-map}))

    (map? sym-or-map)
    (let [{:keys [as]} sym-or-map
          props-name (or as (gensym "props"))]
      (-> state
          (assoc :props-name props-name)
          (assoc-in [:bindings props-name] {:type :props :name props-name})
          (hook-destructure-map props-name sym-or-map)))

    :else
    (throw (ex-info "unsupported form for props" {}))))

(defn add-state-binding [state sym-or-map]
  (cond
    (nil? sym-or-map)
    state

    (symbol? sym-or-map)
    (-> state
        (assoc :state-name sym-or-map)
        (assoc-in [:bindings sym-or-map] {:type :state :name sym-or-map}))

    (map? sym-or-map)
    (let [{:keys [as]} sym-or-map
          state-name (or as (gensym "state"))]
      (-> state
          (assoc :state-name state-name)
          (assoc-in [:bindings state-name] {:type :state :name state-name})
          (hook-destructure-map state-name sym-or-map)))

    :else
    (throw (ex-info "unsupported form for state" {}))))

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
                    :props
                    `(get-props ~comp-sym)
                    :state
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
             (set))]
    (-> state
        (assoc-in [:bindings key]
          {:type :hook
           :idx hook-idx
           :name key
           :deps deps})

        (cond->
          (contains? deps (:props-name state))
          (update :props-affects bit-set hook-idx)

          (contains? deps (:state-name state))
          (update :state-affects bit-set hook-idx))

        ;; update already created hooks if they affect this hook
        ;; doing this in the macro so we don't have to calc it at runtime
        (r->
          (fn [state idx]
            (update-in state [:hooks idx :affects] conj hook-idx))
          hook-deps)

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
             (set))]
    (-> state
        (cond->
          (contains? deps (:props-name state))
          (update :props-affects bit-set hook-idx)

          (contains? deps (:state-name state))
          (update :state-affects bit-set hook-idx))

        ;; update already created hooks if they affect this hook
        ;; doing this in the macro so we don't have to calc it at runtime
        (r->
          (fn [state idx]
            (update-in state [:hooks idx :affects] conj hook-idx))
          hook-deps)

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

(defn analyze-body [{:keys [bindings] :as state} body]
  (let [deps (find-used-bindings #{} bindings body)]
    (assoc state
      :render-used deps
      :render-deps (->> deps
                        (map #(get bindings %))
                        (filter #(= :hook (:type %)))
                        (map :idx)
                        (set))
      :props-affect-render (contains? deps (:props-name state))
      :state-affect-render (contains? deps (:state-name state)))))

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
             :hooks []
             :opts opts
             :hook-bindings []
             :props-affects 0
             :state-affects 0
             :env-affects 0
             :render-deps #{}
             :hook-idx 0}
            (add-props-binding (get bindings 0))
            (add-state-binding (get bindings 1))
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
         ;; FIXME: props/state should probably integrate into hook bits somehow
         ~(:props-affect-render state)
         ~(:props-affects state)
         ~(:state-affect-render state)
         ~(:state-affects state)
         ~(reduce bit-set 0 (:render-deps state))
         (fn [~(:comp-sym state)]
           (let ~(let-bindings state (:render-used state))
             ~@body))))))
(comment
  (defc some-props [{:keys [x] :as props} state]
    []
    ...)

  ;; is turned into

  (defc some-props [props state]
    [x (get props :x)]
    ...)

  ;; so that the hooks logic is used for simple dirty checks
  ;; but also so that props can carry hook inits that will apply to the component
  ;; FIXME: not sure that is actually a good idea, we'll see

  )

(comment
  (macroexpand '(defc hello [{:keys [pa] :as props} state]
                  [{::keys [foo] :as x}
                   (query [::foo])]
                  [:div pa foo])))

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
