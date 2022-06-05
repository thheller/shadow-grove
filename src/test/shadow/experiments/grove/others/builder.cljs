(ns shadow.grove.others.builder
  (:require-macros [shadow.grove.builder])
  (:require
    [shadow.grove.protocols :as p]))

(def ^:dynamic ^not-native *instance* nil)

(defn check-instance! []
  (when-not *instance*
    (throw (ex-info "no shadow.grove.builder/*instance* set!" {}))))

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