(ns shadow.grove.components
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

(defn get-map-destructure-names [m]
  (reduce-kv
    (fn [names key val]
      (cond
        (symbol? key)
        (conj names key)

        (= :as key)
        (conj names val)

        (vector? val)
        (into names val)

        :else
        names))
    #{}
    m))

(defn get-vec-destructure-names [v]
  (reduce
    (fn [names val]
      (cond
        (= '& val)
        names

        (symbol? val)
        (conj names val)

        :else
        names))
    #{}
    v))

(comment
  (get-map-destructure-names '{:keys [foo] ::keys [bar] baz :baz :as x})
  (get-vec-destructure-names '[a b c & d :as x]))

(defn find-fn-args [args]
  {:pre [(vector? args)]}
  (reduce
    (fn [names arg]
      (cond
        (symbol? arg)
        (conj names arg)

        (map? arg)
        (set/union names (get-map-destructure-names arg))

        (vector? arg)
        (set/union names (get-vec-destructure-names arg))

        :else
        names))
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
    ;; so they don't appear as used when using the same name as a defc binding
    ;; (defc ui-thing [a]
    ;;   (render
    ;;     (simple-seq ...
    ;;       (fn [a]
    ;;         (<< [:div a])))) <- this isn't the a from args
    (and (seq? form) (= 'fn (first form)))
    (let [[_ maybe-vec & more] form

          shadows
          (if (symbol? maybe-vec)
            (find-fn-args (first more))
            (find-fn-args maybe-vec))

          fn-bindings
          (reduce dissoc bindings shadows)]

      (reduce #(find-used-bindings %1 fn-bindings %2) used form))

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

                    :slot
                    `(get-slot-value ~comp-sym ~idx)
                    )]))
       (vec)))

(defn bindings-indexes-of-type [{:keys [bindings] :as state} deps type]
  (->> deps
       (map #(get bindings %))
       (filter #(= type (:type %)))
       (map :idx)
       (set)))

(defn update-affects [{:keys [bindings] :as state} deps slot-idx]
  (reduce
    (fn [state dep-sym]
      (let [{:keys [type idx] :as binding} (get bindings dep-sym)]
        (update-in state [(case type :slot :slots :arg :args) idx :affects] set-conj slot-idx)))
    state
    deps))

(defn add-destructure-binding
  ([state ref-name binding-name key defaults]
   (add-destructure-binding
     state ref-name binding-name
     [:lookup key (get defaults binding-name)]))

  ([{:keys [bindings slot-idx] :as state} ref-name binding-name access-type]
   (-> state
       (update-affects #{ref-name} slot-idx)
       (assoc-in [:bindings binding-name]
         {:type :slot
          :idx slot-idx
          :name binding-name
          :deps #{ref-name}})

       (update :slots conj
         {:depends-on
          (let [{:keys [type idx] :as ref} (get-in state [:bindings ref-name])]
            (if (not= type :slot)
              #{} ;; args are tracked elsewhere, this is only for slots depending on other slots
              #{idx}))
          :debug-info {:type :destructure
                       :name (str binding-name)
                       :from (str ref-name)
                       :column (:column (meta binding-name))
                       :line (:line (meta binding-name))}
          :provides binding-name
          :run
          (case (first access-type)
            :lookup
            (let [[_ key default] access-type
                  {:keys [type idx] :as src} (get bindings ref-name)]
              (case type
                :arg `(arg-destructure ~idx ~key ~default)
                :slot `(slot-destructure ~idx ~key ~default)))

            :rest
            (let [[_ drop] access-type
                  {:keys [type idx] :as src} (get bindings ref-name)]
              (case type
                :arg `(arg-destructure-tail ~idx ~drop)
                :slot `(slot-destructure-tail ~idx ~drop))))

          #_(fn [~comp-sym]
              (let ~(let-bindings state #{ref-name})
                ~accessor))})
       (update :slot-idx inc))))

(defn slot-destructure-map
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

(defn slot-destructure-vec
  [state
   ref-name
   v]

  (let [idx-syms
        (into []
          (take-while #(and (simple-symbol? %)
                            (not= '& %)))
          v)

        kv
        (loop [kv {}
               s (drop (count idx-syms) v)]
          (if-not (seq s)
            kv
            (let [[k v & more] s]
              (when-not v
                (throw (ex-info "invalid vector destructure" {:k k :v v})))
              (recur
                (assoc kv k v)
                more))))


        ;; add index arguments
        state
        (reduce-kv
          (fn [state idx sym]
            (add-destructure-binding state ref-name sym idx {}))
          state
          idx-syms)]

    ;; handle remaining kv pairs
    ;; [... :as X]
    ;; [... & more]
    ;; FIXME: are there others?
    (reduce-kv
      (fn [state k v]
        (case k
          :as state ;; handled earlier, no need to do anything
          & (add-destructure-binding state ref-name v
              [:tail (count idx-syms)])
          (throw (ex-info "invalid vector destructure" {:k k :v v}))))
      state
      kv)))

(defmulti analyze-slot (fn [state slot] (:name slot)))

(defn analyze-arg
  [state idx binding]
  (cond
    (symbol? binding)
    (-> state
        (assoc-in [:args idx] {:name binding :idx idx :stable (:stable (meta binding))})
        (assoc-in [:bindings binding] {:type :arg :name binding :idx idx}))

    (map? binding)
    (let [{:keys [as]} binding
          arg-name (or as (symbol (str "__arg$" idx)))
          ;; allow both?
          ;; ^:stable {:keys [foo bar] :as x}
          ;; {:keys [foo bar] :as ^:stable x}
          stable (or (:stable (meta binding))
                     (:stable (meta as)))]
      (-> state
          (assoc-in [:args idx] {:name arg-name :idx idx :stable stable})
          (assoc-in [:bindings arg-name] {:type :arg :name arg-name :idx idx})
          (slot-destructure-map arg-name binding)))

    (vector? binding)
    (let [[_as sym :as v] (drop-while #(not= :as %) binding)

          arg-name
          (if-not (seq v)
            (symbol (str "__arg$" idx))
            ;; :as followed by symbol only
            (do (when-not (simple-symbol? sym)
                  (throw (ex-info "invalid data binding" {:binding binding})))
                sym))

          stable (or (:stable (meta binding))
                     (:stable (meta arg-name)))]
      (-> state
          (assoc-in [:args idx] {:name arg-name :idx idx :stable stable})
          (assoc-in [:bindings arg-name] {:type :arg :name arg-name :idx idx})
          (slot-destructure-vec arg-name binding)))

    :else
    (throw (ex-info "unsupported form for component arg" {:binding binding :idx idx}))))

(defn add-data-binding
  [{:keys [comp-sym slot-idx bindings] :as state}
   [binding & body]]

  (let [binding-name
        (cond
          (simple-symbol? binding)
          binding

          (map? binding)
          (or (get binding :as)
              (symbol (str "__slot$" slot-idx)))

          (vector? binding)
          (let [[_as sym :as v] (drop-while #(not= :as %) binding)]
            (if-not (seq v)
              (symbol (str "__slot$" slot-idx))
              ;; :as followed by symbol only
              (do (when-not (simple-symbol? sym)
                    (throw (ex-info "invalid data binding" {:binding binding})))
                  sym)))

          :else
          (throw (ex-info "invalid data binding" {:binding binding})))

        deps
        (find-used-bindings #{} bindings body)]

    (-> state
        ;; must be done first, otherwise shadowed bindings affect themselves
        ;; must track these here by id since names may be shadowed later
        (update-affects deps slot-idx)

        (cond->
          ;; don't make _ an accessible name when using (bind _ "something")
          ;; should be convention for unused name, accessing it should be an error
          (not= '_ binding-name)
          (assoc-in [:bindings binding-name]
            {:type :slot
             :idx slot-idx
             :name binding-name
             :deps deps}))

        (update :slots conj
          {:all-deps deps
           :depends-on (bindings-indexes-of-type state deps :slot)
           :provides binding-name
           :debug-info {:type :bind
                        :name (str binding-name)
                        :column (:column (meta binding))
                        :line (:line (meta binding))}
           :run
           (if (empty? deps)
             `(fn [~comp-sym]
                ~@body)
             `(fn [~comp-sym]
                (let ~(let-bindings state deps)
                  ~@body)))})
        (update :slot-idx inc)

        (cond->
          ;; FIXME: vector destructure
          (map? binding)
          (slot-destructure-map binding-name binding)

          (vector? binding)
          (slot-destructure-vec binding-name binding)
          ))))

(defmethod analyze-slot 'bind [state {:keys [body]}]
  ;; (bind some-name (something-producing-a-value-or-slot))

  ;; don't want to deal with render using bindings not yet declared
  (when (contains? state :render-fn)
    (throw (ex-info "data bindings can only be defined before render" {})))

  (add-data-binding state body))

(defmethod analyze-slot 'effect [state {:keys [body]}]
  ;; (effect :mount [env] ...)
  ;; much better than
  ;; (hook (sg/effect :mount (fn [env] ...))

  (let [[when args & rest] body]
    (when-not (and (vector? args) (= 1 (count args)))
      (throw (ex-info "effect requires an arg vector" {:body body})))

    (add-data-binding state
      `[~'_ (slot-effect ~when (fn ~args ~@rest))])))

(defmethod analyze-slot 'hook [state {:keys [body]}]
  ;; (hook (some-hook-without-output))

  ;; purely for side effects where the slot may slot into the lifecycle of the component
  (add-data-binding state (into ['_] body)))

(defmethod analyze-slot 'render
  [{:keys [comp-sym bindings] :as state} {:keys [body] :as slot}]
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

(defmethod analyze-slot '<<
  [{:keys [comp-sym bindings] :as state} {:keys [body] :as slot}]
  ;; (<< something)
  (when (contains? state :render-fn)
    (throw (ex-info "render already defined" {})))

  (let [deps (find-used-bindings #{} bindings body)]

    (assoc state
      :render-used deps
      :render-fn
      `(fn [~comp-sym]
         (let ~(let-bindings state deps)
           (shadow.grove/<< ~@body))))))

(defmethod analyze-slot 'event
  [{:keys [comp-sym bindings] :as state} {:keys [body] :as slot}]
  ;; (event ::ev-id [env e] (do-something))
  (let [[ev-id ev-args & body] body]

    (cond
      ;; (event ::some-id some-other-fn)
      ;; no access to slot data, just calls fn
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

            ;; need to rebind arguments since we need the first arg but it may not have a "name"
            ;; (event :something [{:keys [foo]} data e] ...)
            arg-syms
            (vec (take (count ev-args) (repeatedly gensym)))

            deps
            (find-used-bindings #{} inner-bindings body)]

        (assoc-in state [:events ev-id]
          (if (empty? deps)
            `(fn ~ev-args
               ~@body
               ;; ev-fn return value is ignored anyways, don't turn above into expression
               nil)
            `(fn ~arg-syms
               ;; FIXME: this is kinda hacky, the component calls (event-fn env e ...)
               ;; and then we get the component out of the env
               ;; but I don't want the component to be part of the event signature
               ;; and I don't want to rewrite the ev-args to add the comp binding
               ;; since I can't to that for (event ::foo some-fn) without going through too much apply
               (let ~(-> []
                         (conj comp-sym `(get-component ~(first arg-syms)))
                         ;; let binding from slots first
                         (into (let-bindings state deps))
                         ;; then function args because they may shadow let names
                         (into (mapcat vector ev-args arg-syms)))
                 ~@body)
               ;; ev-fn return value is ignored anyways, don't turn above into expression
               nil))))

      :else
      (throw (ex-info "invalid event declaration" {:slot slot})))))

(defn slot-symbol? [x]
  (and (symbol? x)
       (contains? (methods analyze-slot) x)))

(s/def ::defc-slot
  (s/cat
    :name slot-symbol?
    :body (s/* any?)))

(s/def ::defc-args
  (s/cat
    :comp-name simple-symbol?
    :docstring (s/? string?)
    :opts (s/? map?)
    :args vector? ;; FIXME: core.specs for destructure help
    ;; + since it requires at least (render ...)
    :slots (s/+ (s/spec ::defc-slot))))

(s/fdef defc :args ::defc-args)

(defn make-dirty-bits [mask n]
  (if (zero? n)
    mask
    (recur
      (bit-or (bit-shift-left mask 1) 1)
      (dec n))))

(defmacro defc [& args]
  (let [{:keys [comp-name args slots opts] :as c}
        (s/conform ::defc-args args)

        {:keys [new-args-sym old-args-sym comp-sym render-used render-fn] :as state}
        (-> {:bindings {}
             :comp-sym (gensym "comp")
             :old-args-sym (with-meta (gensym "old") {:tag 'not-native})
             :new-args-sym (with-meta (gensym "new") {:tag 'not-native})
             :args []
             :slots []
             :events {}
             :opts opts
             :effect-idx 0
             :slot-idx 0}
            (rkv-> analyze-arg args)
            (r-> analyze-slot slots))

        opts
        (let [stable-args
              (reduce-kv
                (fn [acc idx {:keys [stable]}]
                  (if-not stable
                    acc
                    (conj acc idx)))
                []
                (:args state))]
          (when (seq stable-args)
            (assoc opts ::stable-args stable-args)))

        render-deps
        (bindings-indexes-of-type state render-used :slot)

        render-args
        (bindings-indexes-of-type state render-used :arg)]

    ;; assume 30 bits is safe for ints?
    ;; FIXME: JS numbers are weird, how many bits are actually safe to use?
    (when (>= (:slot-idx state) 30)
      (throw (ex-info "too many slots" {})))

    (when-not render-fn
      (throw (ex-info "missing render" {})))

    ;; (clojure.pprint/pprint state)

    ;; FIXME: put docstring somewhere if present
    `(def ~(vary-meta comp-name assoc :tag 'not-native)
       (make-component-config
         ~(str *ns* "/" comp-name)
         (cljs.core/array
           ~@(->> (:slots state)
                  (map (fn [{:keys [depends-on affects run debug-info]}]
                         `(make-slot-config
                            ~(reduce bit-set 0 depends-on)
                            ~(reduce bit-set 0 affects)
                            ~run
                            ~debug-info)))))
         ~(make-dirty-bits 0 (count (:slots state)))
         ~(or opts {})
         ;; fn that checks args and invalidates slots or sets render-required
         (fn [~comp-sym ~old-args-sym ~new-args-sym]
           (check-args! ~comp-sym ~new-args-sym ~(count (:args state)))
           ~@(for [{:keys [idx affects]} (:args state)
                   :let [affects-render? (contains? render-args idx)
                         affects-slots? (seq affects)]
                   :when (or affects-render? affects-slots?)]
               `(when (not=
                        ;; validated to be vectors elsewhere
                        (cljs.core/-nth ~old-args-sym ~idx)
                        (cljs.core/-nth ~new-args-sym ~idx))
                  ;; ~@ so that it doesn't leave ugly nil
                  ~@(when affects-render?
                      [`(arg-triggers-render! ~comp-sym ~idx)])
                  ~@(when affects-slots?
                      [`(arg-triggers-slots! ~comp-sym ~idx ~(reduce bit-set 0 affects))])))
           ;; drops return value in :advanced
           js/undefined)
         ~(reduce bit-set 0 render-deps)
         ~render-fn
         ~(:events state)
         ~(let [m (meta comp-name)]
            {:args (->> (:args state) (map :name) (mapv str))
             :file (:file m)
             :line (:line m)
             :column (:column m)})
         ))))

(comment
  (require 'clojure.pprint)
  (clojure.pprint/pprint
    (macroexpand
      '(defc hello [[b c & more]]
         (render
           [:div b c more]))))

  (clojure.pprint/pprint
    (macroexpand
      '(defc hello
         [^:stable {x :foo :keys [y] ::keys [z z2 z3] :as p}
          {sx :sx ::keys [sy sz] :as s}
          unused-arg]

         (bind {:keys [a]}
           (hello-hook 1 p))

         (bind {:keys [b2] :as b}
           (hello-hook 2 s))

         (bind c
           (hello-hook 3 {:hello a :world b}))

         (bind d
           (hello-hook 4 [a b c x y z sy sz]))

         (event ::some-event! [env e a]
           (prn [:ev e a]))

         (event ::some-event handled-elsewhere!)

         (bind a (hello-hook 5 [:shadow a]))

         (effect :mount [env]
           (js/console.log "here be mounted"))

         (render
           [:div a [:h1 d]])))))