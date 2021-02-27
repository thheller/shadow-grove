(ns shadow.experiments.grove.ui.forms
  (:require [shadow.experiments.grove.protocols :as gp]
            [shadow.experiments.arborist.protocols :as ap]
            [shadow.experiments.grove.components :as comp]))

(deftype FormInstance
  [component idx config ^:mutable state]
  gp/IHook
  (hook-init! [this]
    (js/console.log ::form-instance-init!))
  (hook-ready? [this] true)
  (hook-value [this]
    ;; FIXME: return something thats easier to work with and as much data as possible
    ;; maybe pure data with meta reference to this, fields need this
    this)
  (hook-deps-update! [this ^FormInit val]
    (assert (identical? config (.-config val)))
    (when-not (= state (.-state val))
      (js/console.log ::form-instance-deps-update! this val)))
  (hook-update! [this]
    (js/console.log ::form-instance-update! this))
  (hook-destroy! [this]
    (js/console.log ::form-instance-destroy! this))

  Object
  ;; dunno if we actually need to keep a reference
  ;; should return config, so it doesn't need a separate method
  (register-field [this field instance]
    (get-in config [:fields field]))

  (remove-field [this field])

  (get-field-value [this field]
    (let [v (get state field ::undefined)]
      (if-not (keyword-identical? ::undefined v)
        v
        (get-in config [:fields field :default-value]))))

  (field-did-change! [this field val]
    (set! state (assoc state field val))

    ;; FIXME: invalidate form to trigger re-render of hosting component?

    ;; FIXME: should validation be done by the form or the field?
    ;; eager-submit as in submit as soon as the form is valid
    (when (:eager-submit config)
      (let [submit-fn (:submit config)]
        (submit-fn (comp/get-env component) state)))

    val))

(deftype FormInit [config state]
  gp/IBuildHook
  (hook-build [this comp idx]
    (FormInstance. comp idx config state)))

(defn form-has-errors? [form]
  false)

(defn form-has-error? [form field]
  false)

;; force configuration separately from instantiation
;; saves a bunch of checking if a form config was changed
;; since it may contain function it would otherwise
;; never compare equal
(defn configure [config]
  ;; FIXME: validate config?
  (fn form-config [state]
    ;; FIXME: validate state being passed in?
    (FormInit. config state)))



;; =====================================================================
;; SELECT
;; =====================================================================
;; FIXME: move to separate ns once the structure is cleaner

(defn select-find-idx [options current-val]
  (reduce-kv
    (fn [_ idx [val label]]
      (when (= val current-val)
        (reduced idx)))
    nil
    options))

(declare SelectInit)

(deftype ManagedSelect
  [env
   element
   ^FormInstance form
   field
   ^:mutable options
   ^:mutable field-config]

  ap/IManaged
  (supports? [this ^SelectInit next]
    (and (instance? SelectInit next)
         (identical? form (.-form next))
         (= field (.-field next))))

  (dom-sync! [this ^SelectInit next]
    (when (not= options (.-options next))
      (js/console.warn ::select-options-changed! this next)))

  (dom-insert [this parent anchor]
    (.insertBefore parent element anchor))

  (dom-first [this]
    element)

  (dom-entered! [this])

  (destroy! [this dom-remove?]
    (when dom-remove?
      (.remove element))
    (.remove-field form field))

  Object
  (init! [this]
    ;; FIXME: should maybe take some config from env or form
    ;; so that it is easier to keep input styles consistent?
    (set! field-config (.register-field form field this))

    (let [class (or (get field-config :class-empty)
                    (get field-config :class-valid))
          init-val (.get-field-value form field)
          init-idx (select-find-idx options init-val)]

      (when class
        (set! element -className class))

      (.set-options! this)
      (when init-idx
        (set! element -selectedIndex init-idx))

      ;; FIXME: any other events we need to listen to for <select>?
      (.addEventListener element "change"
        (fn [e]
          (let [idx (.-selectedIndex element)
                ;; options should be [[1 "foo"]]
                [val label] (get options idx)]

            (.field-did-change! form field val))))))


  (set-options! [this]
    ;; FIXME: does this work for reset?
    ;; (set! (.. element -options -length) 0)

    (reduce-kv
      (fn [_ idx [val label]]
        (let [opt (js/document.createElement "option")]
          (set! opt -text label)
          (when (nil? val)
            (set! opt -disabled true))
          (.add element opt nil)))
      nil
      options)
    ))

(deftype SelectInit [form field options]
  ap/IConstruct
  (as-managed [this env]
    (doto (ManagedSelect. env (js/document.createElement "select") form field options nil)
      (.init!))))

;; not doing fields via attrs since that means
;; none of the related code can ever be DCE'd
;; which is really bad
;; this is out since too much code would be always alive
;; (<< [:select {:form/field [form :thing options]}])
;; this would be ok since enough select-specific could would still be removable
;; (<< [:select {:form/field (form/select-field form :thing options)}])
;; this is just the shortest and likely enough, also full control over DOM element
;; (form/select form :thing options)
(defn- select-option? [thing]
  (and (vector? thing)
       (= 2 (count thing))
       (string? (nth thing 1))))

(defn select [form field options]
  {:pre [(instance? FormInstance form)
         (keyword? field)
         (vector? options)
         (every? select-option? options)]}
  (SelectInit. form field options))


;; FIXME: implement something like material-components-web, native select/input feel old and clunky
;; can't use it directly because it is loading it via npm means no DCE
;; way too much code if everything is bundled
;; can't consume it directly because no clean ESM output is provided (as of now)
