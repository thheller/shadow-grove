(ns shadow.arborist)

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
