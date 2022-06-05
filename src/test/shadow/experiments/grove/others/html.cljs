(ns shadow.grove.others.html
  (:require-macros [shadow.grove.html])
  (:refer-clojure :exclude (str))
  (:require
    [shadow.grove.builder :as build]
    [shadow.grove.protocols :as p]
    [goog.string :as gstr]))

(defrecord Element [tag parent])

(defn ^String escape-val [thing]
  (gstr/htmlEscape thing))

(deftype SafeString [actual]
  Object
  (toString [_]
    actual))

(deftype HTMLWriter
  [^js sb
   ^:mutable element]

  p/IBuildTrees
  (fragment-start [this fragment-id node-count]
    (when element
      (throw (ex-info "invalid state, shouldn't have an element" {}))))

  (fragment-end [this]
    (SafeString. (.join sb "")))

  ;; <tag key="val" ...>
  (element-open [_ ^not-native tag akeys avals sc specials]
    (let [el (Element. tag element)
          c (alength akeys)]

      (set! element el)

      (.push sb "<")
      (.push sb (-name tag))

      (dotimes [i c]
        (let [^not-native key (aget akeys i)
              val (aget avals i)]
          (when val
            (.push sb " ")
            (.push sb (-name key))
            (when-not (true? val)
              (.push sb "=\"")
              (if (< i sc)
                ;; static attributes can be emitted as is since it is unlikely we will XSS ourselves
                (.push sb (cljs.core/str val))
                ;; dynamic attrs must be escaped properly
                (.push sb (escape-val val)))
              (.push sb "\"")))))
      (.push sb ">")))

  ;; </tag>
  (element-close [_]
    (.push sb "</")
    (.push sb (-> element :tag name))
    (.push sb ">")
    (set! element (:parent element)))

  ;; only text is passed through as is
  (text [_ val]
    (.push sb val))

  ;; everything else must be escaped
  (interpret [_ val]
    (when (some? val)
      (if (instance? SafeString val)
        (.push sb (.-actual val))
        (.push sb (escape-val val))))))

(defn new-fragment []
  (HTMLWriter. (array) nil))
