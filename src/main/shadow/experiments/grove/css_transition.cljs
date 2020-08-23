(ns shadow.experiments.grove.css-transition
  (:require
    [shadow.experiments.arborist.attributes :as sa]
    [shadow.experiments.arborist.dom-scheduler :as ds]
    [shadow.experiments.grove.protocols :as gp]
    [goog.reflect :as gr]))


;; extremely simplistic css class based transitions
;; instead of living in the virtual DOM tree it requires calling
;; the trigger-out! manually (usually from an event)
;; it takes a callback which will be called after the transition ends
;; and should result in removing the actual node from the tree or so
;; otherwise it'll revert to the state it is in without transition classes

;; will use the usual
;; <class>-enter
;; <class>-enter-active
;; <class>-exit
;; <class>-exit-active

;; FIXME: appear support?

(defprotocol DomTransition
  (trigger-out! [this callback])
  (set-node! [this node]))

(deftype ClassTransition [class ^:mutable ^js element ^:mutable stage component idx]
  gp/IBuildHook
  (hook-build [this c i]
    (ClassTransition. class element stage c i))

  gp/IHook
  (hook-init! [this])
  (hook-ready? [this] true)
  (hook-value [this] this)
  (hook-update! [this] false)
  (hook-deps-update! [this next] false)
  (hook-destroy! [this])

  gp/IHookDomEffect
  (hook-did-update! [this ^boolean did-render?]
    (when (and did-render? (= stage :mount))
      (set! stage :active)

      (let [class-enter (str class "-enter")
            class-enter-active (str class "-enter-active")]

        ;; FIXME: this isn't reliable if there are :hover transitions or so
        (.addEventListener element "transitionend"
          (fn [e]
            (.. element -classList (remove class-enter-active)))
          #js {:once true})

        (ds/write!
          (.. element -classList (add class-enter))

          (ds/read!

            ;; CSS trigger
            (gr/sinkValue (.-scrollTop element))

            (ds/write!
              (.. element -classList (add class-enter-active))
              (.. element -classList (remove class-enter))))))))

  DomTransition
  (trigger-out! [this callback]

    (let [class-exit (str class "-exit")
          class-exit-active (str class "-exit-active")]

      ;; FIXME: this isn't reliable if there are :hover transitions or so
      (.addEventListener element "transitionend"
        (fn [e]
          (ds/write!
            (.. element -classList (remove class-exit-active))
            (callback)))
        #js {:once true})

      (ds/write!
        (.. element -classList (add class-exit))

        (ds/read!

          ;; CSS trigger
          (gr/sinkValue (.-scrollTop element))

          (ds/write!
            (.. element -classList (add class-exit-active))
            (.. element -classList (remove class-exit)))))))

  (set-node! [this new]
    (when element
      (throw (ex-info "already have an element?" {:new new :this this})))

    (set! element new)))

(defn class-transition [class]
  (ClassTransition. class nil :mount nil nil))

(sa/add-attr ::ref
  (fn [env node oval nval]
    (set-node! nval node)))

