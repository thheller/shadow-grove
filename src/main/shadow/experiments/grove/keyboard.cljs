(ns shadow.experiments.grove.keyboard
  (:require [goog.events :as gev]
            [shadow.experiments.grove.ui.util :as util]
            [shadow.experiments.grove.protocols :as gp]
            [shadow.experiments.grove.components :as comp]
            [clojure.string :as str])
  (:import [goog.events KeyHandler EventType]))

(util/assert-not-in-worker!)

(defn desugar-shortcut [s]
  (let [parts (str/split (str/lower-case s) #"\+")
        key (last parts)
        mods (butlast parts)]
    [key
     (set (map keyword mods))]))

(defn desugar-keys [m]
  (reduce-kv
    (fn [m shortcut handler]
      (assert (string? shortcut) "only specify string shortcuts")
      (assert (fn? handler) "need handler fn")

      (assoc m (desugar-shortcut shortcut) handler))
    {}
    m))

(deftype KeyHook [handler-id env ^:mutable keys]
  gp/IBuildHook
  (hook-build [this c i]
    (KeyHook. (random-uuid) (comp/get-env c) (desugar-keys keys)))

  gp/IHook
  (hook-init! [this]
    (let [{::keys [key-hooks-ref]} env]
      (swap! key-hooks-ref conj this)))

  (hook-ready? [this] true)
  (hook-value [this] ::key-hook)
  (hook-update! [this] false)

  (hook-deps-update! [this new-val]
    (assert (instance? new-val KeyHook))
    (set! keys (desugar-keys (.-keys new-val)))
    false)

  (hook-destroy! [this]
    (let [{::keys [key-hooks-ref]} env]
      (swap! key-hooks-ref
        (fn [current]
          (->> current
               (remove #{this})
               (vec))))))

  Object
  (handle-key! [this pretty-key e]
    (when-some [handler (get keys pretty-key)]
      (handler env e)
      true)))

(defn listen [keys]
  (KeyHook. nil nil keys))

(defn check-key! [e pretty-key ^KeyHook hook]
  (when (.handle-key! hook pretty-key e)
    (reduced true)))

(defn describe-key [^goog e]
  [(str/lower-case (.-key e))
   (-> #{}
       (cond->
         (.-shiftKey e)
         (conj :shift)
         (.-ctrlKey e)
         (conj :ctrl)
         (.-altKey e)
         (conj :alt)
         (.-metaKey e)
         (conj :meta)))])

(defn init []
  (fn [app-env]
    (let [key-handler (KeyHandler. js/document)
          key-hooks-ref (atom [])]

      (.listen key-handler "key" #_ js/goog.events.KeyHandler.EventType
        (fn [^goog e]
          (let [hooks @key-hooks-ref]
            ;; (js/console.log "key" e hooks)
            (when (seq hooks)
              (let [pretty-key (describe-key e)]
                (reduce
                  (fn [_ hook]
                    (check-key! e pretty-key hook))
                  nil
                  ;; FIXME: should only the first one win or the last one?
                  (reverse hooks)))))))

      (assoc app-env
        ::key-hooks-ref key-hooks-ref
        ::key-handler key-handler))))
