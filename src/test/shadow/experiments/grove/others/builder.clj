(ns shadow.experiments.grove.builder
  (:refer-clojure :exclude (compile))
  (:require
    [clojure.string :as str]
    [shadow.experiments.grove.protocols :as p]))

;; I dislike .cljc too much to make this a .cljc file ...

;;
;; API (used by macro)
;;

(def ^:dynamic *instance* nil)

(defn check-instance! []
  (when-not *instance*
    (throw (ex-info "no *builder* instance" {}))))

(defn fragment-start [fragment-id node-count]
  (p/fragment-start *instance* fragment-id node-count))

(defn fragment-end []
  (p/fragment-end *instance*))

(defn element-open [type akeys avals attr-offset specials]
  (p/element-open *instance* type akeys avals attr-offset specials))

(defn element-close []
  (p/element-close *instance*))

(defn text [content]
  (p/text *instance* content))

(defn interpret [thing]
  (p/interpret *instance* thing))

;;;
;;; macro stuff
;;;

(defn literal? [thing]
  (or (string? thing)
      (number? thing)
      (boolean? thing)
      (= thing 'nil)))

(defn code? [thing]
  (or (symbol? thing)
      (seq? thing)))

(declare process-html)

(defn process-children [context children]
  (-> (assoc context :body [])
      (as-> X
        (reduce process-html X children))
      (:body)))

(defn host-array [{:keys [env] :as context} arr]
  (if (:ns env)
    `(cljs.core/array ~@arr)
    ;; FIXME: can clojure construct arrays directly? seems like all array fns require a seq?
    ;; vec is fine too though, doesn't really matter
    arr))

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

(defn process-html-element [context el]
  ;; FIXME: identify if el is completely static and emit optimized logic?
  (let [tag-kw (nth el 0)]
    (when-not (keyword? tag-kw)
      (throw (ex-info "invalid element" {:el el})))

    (let [[attrs children]
          (let [attrs (get el 1)]
            (if (and attrs (map? attrs))
              [attrs (subvec el 2)]
              [nil (subvec el 1)]))

          [tag id class]
          (parse-tag tag-kw)

          ;; FIXME: validate attrs doesn't match :id or :class when tag has it

          attrs
          (-> attrs
              (cond->
                id
                (assoc :id id)

                class
                (assoc :class class)))

          ;; tag may be namespaced for theming purposes
          tag (keyword (namespace tag-kw) tag)

          static-attrs
          (reduce-kv
            (fn [m key val]
              (if (or (not (literal? val))
                      (qualified-keyword? key))
                m
                (assoc m key val)))
            {}
            attrs)

          dynamic-attrs
          (reduce-kv
            (fn [m key val]
              (if (or (literal? val)
                      (qualified-keyword? key))
                m
                (assoc m key val)))
            {}
            attrs)

          specials
          (reduce-kv
            (fn [m key val]
              (if-not (qualified-keyword? key)
                m
                (assoc m key val)))
            nil
            attrs)

          akeys
          (-> []
              (into (keys static-attrs))
              (into (keys dynamic-attrs)))

          avals
          (reduce
            (fn [vals key]
              (conj vals (get attrs key)))
            []
            akeys)]

      ;; FIXME: emit optimized code for :class, :style attrs?
      ;; OR use :dom/class (some-macro-that-cares-care-of-that ...) via specials

      ;; :class "totally static"
      ;; :class ["foo" "bar" (when something? "foo")]
      ;; :class {"foo" something?}
      ;; :class any?

      ;; :style "string"
      ;; :style {:border 1} <- can be precompiled into string
      ;; :style {:border x}
      ;; :style any?

      (-> context
          (update :body conj
            (with-meta
              `(element-open
                 ~tag
                 ~(host-array context akeys)
                 ~(host-array context avals)
                 ~(count static-attrs)
                 ~specials)
              (meta el)))
          (update :body into (process-children context children))
          (update :body conj `(element-close))))))

(defn process-html-code [context code]
  (update context :body conj `(interpret ~code)))

(defn process-html-literal [context value]
  (update context :body conj `(text ~(str value))))

(defn process-html [context thing]
  (cond
    (literal? thing)
    (process-html-literal context thing)

    (vector? thing)
    (process-html-element context thing)

    (code? thing)
    (process-html-code context thing)

    :else
    (throw (ex-info "invalid thing" {:thing thing}))
    ))


;; to be used by other macros

(defn compile [config macro-env nodes]
  (let [context
        (assoc config
          :env macro-env
          :body [])

        {:keys [body] :as result}
        (reduce process-html context nodes)

        fragment-id
        (str *ns* "@" (:line macro-env) ":" (:column macro-env))]

    `(do (fragment-start ~fragment-id ~(count nodes))
         ~@body
         (fragment-end))))


(defmacro with [builder & body]
  `(binding [*instance* ~builder]
     ~@body))

;; clojure doesn't like set! if there is no binding yet
;; try/finally is a bit overkill though as fragments likely do IO or other mutable stuff
;; and probably can't be recovered from exceptions anyways
;; might want to emit different code?
#_`(let [b# *instance*
         c# ~builder
         r# (do (set! *instance* c#)
                ~@body)]

     (set! *instance* b#)
     r#)