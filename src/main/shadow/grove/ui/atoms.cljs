(ns shadow.grove.ui.atoms
  (:require
    [shadow.grove.components :as comp]
    [shadow.grove.protocols :as gp]
    [shadow.grove.ui.util :as util]))

(deftype EnvWatch
  [key-to-atom path default
   ^:mutable the-atom
   ^:mutable val
   ^:mutable component-handle]

  gp/IHook
  (hook-init! [this ch]
    (set! component-handle ch)

    (let [atom (get (gp/get-component-env ch) key-to-atom)]
      (when-not atom
        (throw (ex-info "no atom found under key" {:key key-to-atom :path path})))
      (set! the-atom atom))

    (set! val (get-in @the-atom path default))
    (add-watch the-atom this
      (fn [_ _ _ new-value]
        ;; check immediately and only invalidate if actually changed
        ;; avoids kicking off too much work
        (let [next-val (get-in new-value path default)]
          (when (not= val next-val)
            (set! val next-val)
            (gp/hook-invalidate! component-handle))))))

  (hook-ready? [this] true) ;; born ready
  (hook-value [this] val)
  (hook-update! [this]
    ;; only gets here if val actually changed
    true)

  (hook-deps-update! [this new-val]
    (throw (ex-info "shouldn't have changing deps?" {})))
  (hook-destroy! [this]
    (remove-watch the-atom this)))

(deftype AtomWatch
  [^:mutable the-atom
   ^:mutable access-fn
   ^:mutable val
   ^:mutable component-handle]

  gp/IHook
  (hook-init! [this ch]
    (set! component-handle ch)
    (set! val (access-fn nil @the-atom))
    (.add-watch! this))

  (hook-ready? [this] true) ;; born ready
  (hook-value [this] val)
  (hook-update! [this]
    ;; only gets here if value changed
    true)
  (hook-deps-update! [this ^AtomWatch next]
    (set! access-fn (.-access-fn next))
    (let [atom-val @(.-the-atom next)
          curr-val @the-atom
          next-val (access-fn curr-val atom-val)]

      ;; need to forget about the previous atom
      ;; since it was also redefined to be a new atom
      ;; so any local update will update the new one, not the one we have
      (remove-watch the-atom this)
      (set! the-atom (.-the-atom next))
      (.add-watch! this)

      (when (not= val next-val)
        (set! val next-val)
        true)))

  (hook-destroy! [this]
    (remove-watch the-atom this))

  Object
  (add-watch! [this]
    (add-watch the-atom this
      (fn [_ _ old new]
        (let [next-val (access-fn old new)]
          (when (not= val next-val)
            (set! val next-val)
            (gp/hook-invalidate! component-handle)))))))