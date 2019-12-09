(ns shadow.experiments.grove-main
  (:require [shadow.experiments.grove.components :as comp]
            [shadow.experiments.arborist.fragments :as fragments]))

;; just for convenience, less imports for the user

(defmacro defc [& args]
  `(comp/defc ~@args))

(defmacro << [& body]
  (fragments/make-fragment &env &form body))

;; I prefer << but <> looks more familiar to reagent :<>
;; costs nothing to have both, let the user decide
(defmacro <> [& body]
  (fragments/make-fragment &env &form body))

(defmacro fragment [& body]
  (fragments/make-fragment &env &form body))

(defmacro html [& body]
  (fragments/make-fragment &env &form body))

(defmacro svg [& body]
  (fragments/make-fragment (assoc &env ::fragments/svg true) &form body))