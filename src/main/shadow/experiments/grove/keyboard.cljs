(ns shadow.experiments.grove.keyboard
  (:require
    [goog.events :as gev]
    [clojure.string :as str]
    [shadow.experiments.arborist.attributes :as sa]
    [shadow.experiments.grove.ui.util :as util]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.components :as comp]
    )
  (:import [goog.events KeyHandler EventType]))

(util/assert-not-in-worker!)

;; FIXME: this produces alt+alt, ctrl+ctrl for blank alt/control presses
;; not interested in those for now
(defn str-key [^goog e]
  (->> [(and (.-ctrlKey e) "ctrl")
        (and (.-altKey e) "alt")
        (and (.-metaKey e) "meta")
        (and (.-shiftKey e) "shift")
        (str/lower-case (.-key e))]
       (filter identity)
       (str/join "+")))

(def this-ns (namespace ::listen))

(sa/add-attr ::listen
  (fn [env ^js node oval nval]
    ;; FIXME: should throw when used without component
    ;; FIXME: should dispose key-handler when node/fragment is unmounted but there is no way to hook into that yet
    (when-some [comp (comp/get-component env)]

      (cond
        ;; off->on
        (and (not oval) nval)
        (let [key-handler (KeyHandler. node)]
          (set! node -shadow$key-handler key-handler)
          (.listen key-handler "key"
            (fn [^goog e]
              (let [event-id (keyword this-ns (str-key e))]
                ;; (js/console.log "checking event" event-id comp env)
                (when-some [handler (get (comp/get-events comp) event-id)]
                  ;; FIXME: needs fixing when using to event maps
                  (handler env [event-id] e))))))

        ;; on->off
        (and (not nval) oval)
        (when-some [key-handler (.-shadow$key-handler node)]
          (.dispose key-handler))

        ;; on->on
        :else
        nil))))