(ns shadow.experiments.grove.html
  (:refer-clojure :exclude (str))
  (:require
    [shadow.experiments.grove.builder :as build]
    [shadow.experiments.grove.protocols :as p])
  (:import [java.io Writer StringWriter]
           [clojure.lang IDeref]
           com.google.common.html.HtmlEscapers))

(set! *warn-on-reflection* true)

(defrecord Element [tag parent])

(defn ^String escape-val [thing]
  (-> (HtmlEscapers/htmlEscaper)
      (.escape (clojure.core/str thing))))

(deftype SafeString [actual]
  Object
  (toString [_]
    actual))

(defmethod print-method SafeString [^SafeString x ^Writer w]
  (.write w (.toString x)))

(deftype HTMLWriter
  [^StringWriter sw
   ^:unsynchronized-mutable element]

  IDeref
  (deref [_] element)

  p/IBuildTrees
  (fragment-start [this fragment-id node-count]
    (when element
      (throw (ex-info "invalid state, shouldn't have an element" {}))))

  (fragment-end [this]
    (SafeString. (.toString sw)))

  ;; <tag key="val" ...>
  (element-open [_ tag akeys avals sc specials]
    (let [el (Element. tag element)
          c (count akeys)]

      (set! element el)

      (.write sw "<")
      (.write sw (name tag))

      (dotimes [i c]
        (let [key (nth akeys i)
              val (nth avals i)]
          (when val
            (.write sw " ")
            (.write sw (name key))
            (when-not (true? val)
              (.write sw "=\"")
              (if (< i sc)
                ;; static attributes can be emitted as is since it is unlikely we will XSS ourselves
                (.write sw (clojure.core/str val))
                ;; dynamic attrs must be escaped properly
                (.write sw (escape-val val)))
              (.write sw "\"")))))
      (.write sw ">")))

  ;; </tag>
  (element-close [_]
    (.write sw "</")
    (.write sw (-> element :tag name))
    (.write sw ">")
    (set! element (:parent element)))

  ;; only text is passed through as is
  (text [_ val]
    (.write sw (clojure.core/str val)))

  ;; everything else must be escaped
  (interpret [_ val]
    (when (some? val)
      (if (instance? SafeString val)
        (.write sw (clojure.core/str val))
        (.write sw (escape-val val))))))

(defn new-fragment []
  (HTMLWriter. (StringWriter.) nil))

(defmacro << [& body]
  `(build/with (new-fragment)
     ~(build/compile {} &env body)))

(defmacro str [& body]
  `(build/with (new-fragment)
     ~(build/compile {} &env body)))