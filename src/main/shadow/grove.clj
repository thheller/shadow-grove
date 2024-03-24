(ns shadow.grove)

;; just for convenience, less imports for the user

(defmacro defc [& args]
  `(shadow.grove.components/defc ~@args))

(defmacro << [& body]
  (with-meta `(shadow.arborist.fragments/html ~@body) (meta &form)))

;; I prefer << but <> looks more familiar to reagent :<>
;; costs nothing to have both, let the user decide
(defmacro <> [& body]
  (with-meta `(shadow.arborist.fragments/html ~@body) (meta &form)))

(defmacro fragment [& body]
  (with-meta `(shadow.arborist.fragments/html ~@body) (meta &form)))

(defmacro html [& body]
  (with-meta `(shadow.arborist.fragments/html ~@body) (meta &form)))

(defmacro svg [& body]
  (with-meta `(shadow.arborist.fragments/svg ~@body) (meta &form)))

(defmacro css [& body]
  (with-meta `(shadow.css/css ~@body) (meta &form)))

(defmacro dev-only [& body]
  (when (= :dev (:shadow.build/mode &env))
    `(do ~@body)))

(defmacro dev-log [& args]

  (when (= :dev (:shadow.build/mode &env))
    (let [{:keys [line column file]} (meta &form)]
      `(when-not (nil? shadow.grove/dev-log-handler)
         (shadow.grove/dev-log-handler {:ns ~(str *ns*) :line ~line :column ~column :file ~file} [~@args])))))