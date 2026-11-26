(ns shadow.grove.components
  (:require
    [cljs.core :as cljs]
    [clojure.spec.alpha :as s]
    [clojure.set :as set]
    [clojure.string :as str]))

(defn r-> [init rfn coll]
  (reduce rfn init coll))

(defn rkv-> [init rfn coll]
  (reduce-kv rfn init coll))

(def set-conj (fnil conj #{}))

(declare find-used-bindings)

(defn binding-form-idents
  "all symbol and keyword names used in a binding form.
   every symbol a destructure binds is derived from one of those, gensyms are not."
  [form]
  (into #{}
    (comp (filter ident?)
      (map #(symbol (name %))))
    (tree-seq coll? seq form)))

(defn destructure-info
  "analyzes a binding form by letting the cljs compiler destructure it, the same
   way let and fn will. so whatever syntax the compiler supports (e.g. :keys! in 1.13)
   works here too, without having to parse it.

   returns {:syms [...] :deps #{...}}
   :syms all symbols bound in order, without _. a symbol bound twice appears once, last binding wins as in let.
   :deps the bindings used by the destructure itself, e.g. by :or defaults or lookup keys.
         symbols bound earlier in the same form shadow bindings of the same name, same as in let."
  [bindings form]
  (let [idents (binding-form-idents form)]
    (loop [syms []
           deps #{}
           visible bindings
           pairs (seq (partition 2 (cljs/destructure [form (gensym "val")])))]
      (if-not pairs
        {:syms (into [] (comp (remove #(= '_ %)) (distinct)) syms)
         :deps deps}
        (let [[[sym init] & more] pairs
              user-sym? (contains? idents sym)]
          (recur
            (cond-> syms user-sym? (conj sym))
            (find-used-bindings deps visible init)
            (cond-> visible user-sym? (dissoc sym))
            more))))))

(defn binding-syms
  "returns a vector of all symbols bound by a destructuring binding form
   in the order they are bound. _ is never included."
  [form]
  (:syms (destructure-info {} form)))

(defn binding-top-level-as
  "returns the :as symbol of a map or vector binding form, only checks the top level"
  [form]
  (let [as
        (cond
          (map? form)
          (get form :as)

          (vector? form)
          (second (drop-while #(not= :as %) form)))]

    (when (and (some? as) (not (simple-symbol? as)))
      (throw (ex-info "invalid :as in binding" {:form form :as as})))

    as))

(comment
  (binding-syms '{:keys [foo] :or {foo 1}})
  (binding-syms '{:keys [foo ::bar] ::keys [baz] :strs [s] qux :qux :as x})
  (binding-syms '{{:keys [nested] :as inner} :foo [a _ & more :as all] :bar})
  (destructure-info
    '{default-a {} some-key {} nested {} unused {}}
    '{{:keys [nested] :or {nested default-a}} some-key :or {x 1}})
  (binding-top-level-as '[a b :as x]))

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

    ;; filter out (fn [args] ...), (fn name [args] ...), multi-arity (fn ([a] ...) ([a b] ...)), and fn*
    (and (seq? form) (or (= 'fn (first form)) (= 'fn* (first form))))
    (let [[_ maybe-name & more] form
          [fn-name clauses] (if (symbol? maybe-name)
                              [maybe-name more]
                              [nil (cons maybe-name more)])
          base-bindings (if fn-name (dissoc bindings fn-name) bindings)
          arities (if (vector? (first clauses))
                    [clauses]
                    clauses)]
      (reduce
        (fn [used clause]
          (if (and (seq? clause) (vector? (first clause)))
            (let [[args & body] clause
                  {:keys [syms deps]} (destructure-info base-bindings args)
                  fn-bindings (reduce dissoc base-bindings syms)]
              (reduce #(find-used-bindings %1 fn-bindings %2) (into used deps) body))
            (find-used-bindings used base-bindings clause)))
        used
        arities))

    ;; filter out shadowed locals in (let [...] ...) and (loop [...] ...)
    (and (seq? form) (or (= 'let (first form)) (= 'loop (first form))) (vector? (second form)))
    (let [[_ bind-vec & body] form
          [used inner-bindings]
          (reduce
            (fn [[used bindings] [bform init]]
              (let [{:keys [syms deps]} (destructure-info bindings bform)
                    used (-> used
                             (into deps)
                             (find-used-bindings bindings init))]
                [used (reduce dissoc bindings syms)]))
            [used bindings]
            (partition 2 bind-vec))]
      (reduce #(find-used-bindings %1 inner-bindings %2) used body))

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

                    :ref
                    `(get-ref ~comp-sym ~idx)
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
      (let [{:keys [type idx]} (get bindings dep-sym)]
        (case type
          :slot (update-in state [:values idx :affects] set-conj slot-idx)
          :arg (update-in state [:args idx :affects] set-conj slot-idx)
          ;; refs never change, so they never make anything dirty
          :ref state)))
    state
    deps))

;; destructuring is handled by letting the regular let do the work
;;
;; (bind {:keys [foo baz] :as bar} (something))
;;
;; becomes one runnable slot running (something), whose own value is bar,
;; plus one value cell in :values for every other symbol bound (foo, baz).
;; value cells are not runnable slots: they hold values in slot-values and track
;; which runnable slots depend on them, without taking up dirty bits or work loop
;; iterations. the check fn generated for the slot (see make-check-fn) does the
;; destructure and checks each bound value individually, so only dependents of
;; values that actually changed get dirty. without :as the full value is never compared.
;;
;; the runnable slot always holds the value its run fn produced (or the :as value)
;; so rt/*slot-value* and sg/suspend! keep working as before

(defn add-destructure-slot
  "adds a runnable slot running run-form whose result is destructured via binding-form.
   own-sym if set is bound to the slot value itself.
   every other symbol bound by binding-form gets a value cell in :values."
  [{:keys [slot-idx val-idx bindings] :as state}
   {:keys [binding-form own-sym run-deps run-form debug-info]}]

  (let [{:keys [syms] expr-deps :deps}
        ;; expr-deps, :or defaults or lookup keys may use other bindings
        ;; they are evaluated in the check fn, not the run fn
        (destructure-info bindings binding-form)

        cell-syms
        (->> syms
             (remove #(= own-sym %))
             (vec))

        deps
        (set/union run-deps expr-deps)

        own-slot-idx
        slot-idx

        own-val-idx
        val-idx

        cells
        (->> cell-syms
             (map-indexed (fn [i sym] [sym (+ own-val-idx 1 i)]))
             (vec))

        own-name
        (or own-sym (symbol (str "__slot$" own-slot-idx)))]

    (-> state
        ;; must be done first, otherwise shadowed bindings affect themselves
        (update-affects deps own-slot-idx)

        (update :slots conj
          {:slot-idx own-slot-idx
           :val-idx own-val-idx
           :provides own-name
           :debug-info debug-info
           :run run-form
           :check
           {:form binding-form
            ;; must be generated now, names may be shadowed by later bindings
            :or-let (let-bindings state expr-deps)
            :own-sym own-sym
            :own-val-idx own-val-idx
            :cells cells}})

        (update :values conj
          {:val-idx own-val-idx
           :slot-idx own-slot-idx
           :depends-on (bindings-indexes-of-type state deps :slot)
           :provides own-name
           :debug-info debug-info})

        (cond->
          own-sym
          (assoc-in [:bindings own-sym]
            {:type :slot
             :idx own-val-idx
             :name own-sym}))

        (r->
          (fn [state [sym cval-idx]]
            (-> state
                (assoc-in [:bindings sym]
                  {:type :slot
                   :idx cval-idx
                   :name sym})
                (update :values conj
                  {:val-idx cval-idx
                   :depends-on #{own-val-idx}
                   :provides sym
                   :debug-info {:type :destructure
                                :name (str sym)
                                :from (str own-name)
                                :column (:column (meta sym))
                                :line (:line (meta sym))}})))
          cells)

        (update :slot-idx inc)
        (update :val-idx + 1 (count cells)))))

(defn make-check-fn
  "generates the fn checking the destructured values of a slot.
   called with the value the slot produced. must be generated after all slots are analyzed,
   since it needs to know who uses which values"
  [{:keys [comp-sym values] :as state} render-deps {:keys [form or-let own-sym own-val-idx cells]}]
  (let [val-sym
        (gensym "val")

        ;; nil when nothing depends on the value, otherwise [affects-bits render?]
        consumers
        (fn [val-idx]
          (let [affects (reduce bit-set 0 (get-in values [val-idx :affects]))
                render? (contains? render-deps val-idx)]
            (when (or (pos? affects) render?)
              [affects render?])))

        own-consumers
        (when own-sym
          (consumers own-val-idx))

        checks
        (->> cells
             (map (fn [[sym cval-idx]]
                    (if-some [[affects render?] (consumers cval-idx)]
                      {:form `(slot-check! ~comp-sym ~cval-idx ~sym ~affects ~render?)
                       ;; only need to remember the result if the :as value can use it
                       :changed (when own-consumers
                                  (gensym (str sym "-changed")))}
                      ;; nothing depends on this, but events may still access it
                      {:form `(slot-store! ~comp-sym ~cval-idx ~sym)})))
             (vec))

        changed-syms
        (into [] (keep :changed) checks)

        own-val
        (or own-sym val-sym)

        own-form
        (if-some [[affects render?] own-consumers]
          (if (seq changed-syms)
            ;; all other values are derived from this one, so if any of those changed so did this
            ;; no need to compare, which saves comparing the whole :as value most of the time
            `(if (or ~@changed-syms)
               (slot-force! ~comp-sym ~own-val-idx ~own-val ~affects ~render?)
               (slot-check! ~comp-sym ~own-val-idx ~own-val ~affects ~render?))
            `(slot-check! ~comp-sym ~own-val-idx ~own-val ~affects ~render?))
          `(slot-store! ~comp-sym ~own-val-idx ~own-val))]

    `(fn [~comp-sym ~val-sym]
       (let [~@or-let
             ~form ~val-sym
             ~@(->> checks
                    (filter :changed)
                    (mapcat (juxt :changed :form)))]
         ~@(->> checks
                (remove :changed)
                (map :form))
         ~own-form
         nil))))

(defmulti analyze-slot (fn [state slot] (:name slot)))

;; args are destructured when the component is called, not when it mounts
;;
;; (defc ui-thing [{:keys [foo]} bar] ...)
;;
;; gets an args-fn of (fn [{:keys [foo]} bar] [foo bar]), so (ui-thing {:foo 1 :baz 2} 3)
;; creates a seed with args [1 3]. every symbol bound by the arg vector is its own arg,
;; the map itself is only kept if bound via :as. so changes to keys the component
;; doesn't bind don't even make the seed not= and a destructure that throws
;; (e.g. :keys! in 1.13) throws in the caller, not later on mount.
;;
;; ^:stable works on the whole form, marking everything it binds as stable,
;; or on individual symbols, e.g. {:keys [^:stable id label]} or {:keys [label] :as ^:stable item}

(defn analyze-args [state args]
  (let [syms
        ;; fn params are a valid vector destructure, & included
        (binding-syms args)

        stable-syms
        (into #{}
          (comp (filter #(:stable (meta %)))
            (mapcat binding-syms))
          ;; ^:stable on top level forms or plain symbols
          (concat (remove #(= '& %) args)
            ;; ^:stable on symbols inside destructure forms
            syms))]

    (-> state
        (assoc :args-fn
               `(fn ~args
                  [~@syms])
               :min-args
               (count (take-while #(not= '& %) args)))
        (rkv->
          (fn [state idx sym]
            (-> state
                (assoc-in [:args idx] {:name sym :idx idx :stable (contains? stable-syms sym)})
                (assoc-in [:bindings sym] {:type :arg :name sym :idx idx})))
          syms))))

(defn add-data-binding
  [{:keys [comp-sym slot-idx val-idx bindings] :as state}
   [binding & body]]

  (let [deps
        (find-used-bindings #{} bindings body)

        run-form
        (if (empty? deps)
          `(fn [~comp-sym]
             ~@body)
          `(fn [~comp-sym]
             (let ~(let-bindings state deps)
               ~@body)))]

    (cond
      (simple-symbol? binding)
      (let [debug-info {:type :bind
                        :name (str binding)
                        :column (:column (meta binding))
                        :line (:line (meta binding))}]
        (-> state
            ;; must be done first, otherwise shadowed bindings affect themselves
            ;; must track these here by id since names may be shadowed later
            (update-affects deps slot-idx)

            (cond->
              ;; don't make _ an accessible name when using (bind _ "something")
              ;; should be convention for unused name, accessing it should be an error
              (not= '_ binding)
              (assoc-in [:bindings binding]
                {:type :slot
                 :idx val-idx
                 :name binding}))

            (update :slots conj
              {:slot-idx slot-idx
               :val-idx val-idx
               :provides binding
               :debug-info debug-info
               :run run-form})

            (update :values conj
              {:val-idx val-idx
               :slot-idx slot-idx
               :depends-on (bindings-indexes-of-type state deps :slot)
               :provides binding
               :debug-info debug-info})

            (update :slot-idx inc)
            (update :val-idx inc)))

      (or (map? binding) (vector? binding))
      (let [as (binding-top-level-as binding)]
        (add-destructure-slot state
          {:binding-form binding
           :own-sym as
           :run-deps deps
           :run-form run-form
           :debug-info {:type :bind
                        :name (str (or as (symbol (str "__slot$" slot-idx))))
                        :column (:column (meta binding))
                        :line (:line (meta binding))}}))

      :else
      (throw (ex-info "invalid data binding" {:binding binding})))))

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

;; (ref name) or (ref name init)
;;
;; init is evaluated once when the component is constructed, the name is a constant after that.
;; (ref name) is (ref name (volatile! nil)), same as (sg/ref) and meant for :dom/ref.
;; init may only use things that never change during the lifetime of the instance:
;; ^:stable args (changing those remounts) and previously declared refs.
;; anything that changes over time is combined with refs in a bind instead.
;;
;; refs are not slots. they have no dirty bits and don't count against the slot limit,
;; binds using them only re-run because of their other deps.
;; may appear anywhere in defc, uses follow the usual rule of only seeing earlier names.

(defmethod analyze-slot 'ref
  [{:keys [comp-sym bindings refs] :as state} {:keys [body] :as slot}]
  (let [[sym init] body]
    (when-not (and (<= 1 (count body) 2) (simple-symbol? sym))
      (throw (ex-info "ref expects (ref name) or (ref name init), with name being a plain symbol or _" {:body body})))

    (let [init
          (if (= 1 (count body))
            `(cljs.core/volatile! nil)
            init)

          deps
          (find-used-bindings #{} bindings init)

          invalid
          (->> deps
               (remove
                 (fn [dep]
                   (let [{:keys [type idx]} (get bindings dep)]
                     (case type
                       :ref true
                       :arg (get-in state [:args idx :stable])
                       false))))
               (sort)
               (vec))

          idx
          (count refs)]

      (when (seq invalid)
        (throw
          (ex-info
            (str "ref " sym " can only use ^:stable args and previously declared refs in its init, but uses: "
                 (str/join ", " invalid)
                 ". init only runs once, use a bind to combine the ref with things that change.")
            {:ref sym :invalid invalid})))

      (-> state
          (update :refs conj
            {:name sym
             :init
             (if (empty? deps)
               `(fn [~comp-sym] ~init)
               `(fn [~comp-sym]
                  (let ~(let-bindings state deps)
                    ~init)))})
          (cond->
            ;; (ref _ (something-with-lifecycle)) is valid, but _ is never accessible
            (not= '_ sym)
            (assoc-in [:bindings sym]
              {:type :ref
               :idx idx
               :name sym}))))))

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
            (reduce dissoc bindings (binding-syms ev-args))

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
             :values []
             :refs []
             :events {}
             :opts opts
             :effect-idx 0
             :slot-idx 0
             :val-idx 0}
            (analyze-args args)
            (r-> analyze-slot slots))

        include-debug-info?
        (not= :release (:shadow.build/mode &env))

        opts
        (let [stable-args
              (reduce-kv
                (fn [acc idx {:keys [stable]}]
                  (if-not stable
                    acc
                    (conj acc idx)))
                []
                (:args state))]
          ;; must keep user opts (e.g. :supports?) when there are no stable args
          (cond-> opts
            (seq stable-args)
            (assoc ::stable-args stable-args)))

        render-deps
        (bindings-indexes-of-type state render-used :slot)

        render-args
        (bindings-indexes-of-type state render-used :arg)]

    ;; 30 bits stay an unboxed positive Smi on 64-bit V8; only runnable slots count, not destructured cells
    (when (> (:slot-idx state) 30)
      (throw (ex-info (str "too many slots in " comp-name " (" (:slot-idx state) ", max 30)")
               {:component comp-name :slots (:slot-idx state)})))

    (when-not render-fn
      (throw (ex-info "missing render" {})))

    ;; (clojure.pprint/pprint state)

    ;; FIXME: put docstring somewhere if present
    `(def ~(vary-meta comp-name assoc :tag 'not-native)
       (make-component-config
         ~(str *ns* "/" comp-name)
         ~(:min-args state)
         ~(:args-fn state)
         (cljs.core/array
           ~@(->> (:slots state)
                  (map
                    (fn [{:keys [val-idx run check debug-info]}]
                      (let [affects (if check
                                      0
                                      (reduce bit-set 0 (get-in state [:values val-idx :affects])))
                            affects-render? (if check
                                              false
                                              (contains? render-deps val-idx))]
                        `(make-slot-config
                           ~val-idx
                           ~affects
                           ~affects-render?
                           ~run
                           ~(when check
                              (make-check-fn state render-deps check))
                           ~(when include-debug-info?
                              debug-info)))))))
         ~(:val-idx state)
         ;; ref init fns, in declaration order. nil when there are none
         ~(when (seq (:refs state))
            `(cljs.core/array ~@(map :init (:refs state))))
         ~(or opts {})
         ;; fn that checks args and invalidates slots or sets render-required
         ;; args are always produced by args-fn, so their count is correct
         (fn [~comp-sym ~old-args-sym ~new-args-sym]
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
                      [`(arg-triggers-slots! ~comp-sym ~idx ~(reduce bit-set 0 affects) ~(apply min affects))])))
           ;; drops return value in :advanced
           js/undefined)
         ~render-fn
         ~(:events state)
         ~(when include-debug-info?
            (let [m (meta comp-name)]
              {:args (->> (:args state) (map :name) (mapv str))
               :refs (->> (:refs state) (map :name) (mapv str))
               :values (->> (:values state)
                            (mapv (fn [{:keys [val-idx depends-on affects debug-info]}]
                                    (assoc debug-info
                                      :idx val-idx
                                      :depends-on (vec (sort depends-on))
                                      :affects (vec (sort affects))))))
               :file (:file m)
               :line (:line m)
               :column (:column m)}))
         ))))

(comment
  (require 'clojure.pprint)
  (clojure.pprint/pprint
    (macroexpand
      '(defc hello [[b c & more]]
         (render
           [:div b c more]))))

  (tap>
    (s/conform ::defc-args
      '([^:stable {x :foo :keys [y] ::keys [z z2 z3] :as p}
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
          [:div a [:h1 d]]))))

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
