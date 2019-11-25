(ns shadow.experiments.arborist
  (:require
    [shadow.experiments.arborist.fragments :as fragments]
    [shadow.experiments.arborist.components :as comp]))

(defmacro << [& body]
  (fragments/make-fragment &env body))

;; I prefer << but <> looks more familiar to reagent :<>
;; costs nothing to have both, let the user decide
(defmacro <> [& body]
  (fragments/make-fragment &env body))

(defmacro fragment [& body]
  (fragments/make-fragment &env body))

;; only here for bench-fragment, do not use directly.
(defmacro fragment-fallback [& body]
  (fragments/make-fragment (dissoc &env :shadow.build.compiler/analyze-top) body))

(defmacro defc [& args]
  `(comp/defc ~@args))