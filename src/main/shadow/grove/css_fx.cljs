(ns shadow.grove.css-fx
  (:require
    [shadow.cljs.modern :refer (defclass)]
    [shadow.arborist.attributes :as sa]
    [shadow.arborist.dom-scheduler :as ds]
    [shadow.grove.protocols :as gp]
    [goog.reflect :as gr]
    [shadow.arborist.common :as common]
    [shadow.arborist.protocols :as ap]))


;; extremely simplistic css class based transitions
;; pretty similar to react CSSTransition

;; requires calling trigger-out! to begin the out transition
;; it takes a callback which will be called after the transition timeout
;; after that it'll unmount the child assuming that the whole group will unmount soon thereafter

;; will use the usual
;; <class>-enter
;; <class>-enter-active
;; <class>-exit
;; <class>-exit-active

;; FIXME: appear support?

;; FIXME: why is this method so popular?
;; why the indirection to css classes? why not just set styles?

(defclass GroupRoot
  (field env)
  (field marker)
  (field opts)
  (field child-root)
  (field timeout-id)

  (constructor [this first-env first-opts]
    (set! opts first-opts)
    (set! env (assoc first-env ::root this))
    (set! marker (common/dom-marker env))
    (set! child-root (common/managed-root env))

    (ap/update! child-root (:child first-opts)))

  ap/IManaged
  (supports? [this next]
    (ap/identical-creator? opts next))

  (dom-sync! [this next-opts]
    (ap/update! child-root (:child next-opts)))

  (dom-insert [this parent anchor]
    (.insertBefore parent marker anchor)
    (ap/dom-insert child-root parent anchor)

    ;; FIXME: maybe enforce that the child only added one element?
    )

  (dom-first [this]
    marker)

  (dom-entered! [this]
    (ap/dom-entered! child-root)

    (let [{:keys [class timeout]} opts
          class-enter (str class "-enter")
          class-enter-active (str class "-enter-active")
          element (.-nextElementSibling marker)]

      (if-not element
        (js/console.log "no element to transition in?" marker)

        ;; microtasking this in 3 steps is probably overkill
        ;; but gives other writes a chance to also happen before paint?
        ;; doubt there will be many though?
        (ds/write!
          (.. element -classList (add class-enter))

          (ds/read!
            ;; CSS trigger
            (gr/sinkValue (.-scrollTop element))

            (ds/write!

              (set! timeout-id
                (js/setTimeout
                  (fn []
                    (set! timeout-id nil)
                    ;; don't really need to remove the class if we remove the element?
                    ;; (.. element -classList (remove class-exit-active))
                    (.. element -classList (remove class-enter-active)))
                  timeout))

              (.. element -classList (add class-enter-active))
              (.. element -classList (remove class-enter))))))))

  (destroy! [this dom-remove?]
    (when timeout-id
      (js/clearTimeout timeout-id))

    (when dom-remove?
      (.remove marker))

    (ap/destroy! child-root dom-remove?))

  Object
  (begin-exit [this callback]
    (let [{:keys [class timeout]} opts
          class-exit (str class "-exit")
          class-exit-active (str class "-exit-active")
          element (.-nextElementSibling marker)]

      ;; FIXME: this could be a totally different element than we transitioned in
      ;; there could also be no element

      (if-not element
        (js/console.log "no element to transition out?" element)

        (ds/write!
          (.. element -classList (add class-exit))

          (ds/read!

            ;; CSS trigger
            (gr/sinkValue (.-scrollTop element))

            (ds/write!
              (set! timeout-id
                (js/setTimeout
                  (fn []
                    (set! timeout-id nil)
                    ;; don't really need to remove the class if we remove the element?
                    ;; (.. element -classList (remove class-exit-active))
                    (ap/update! child-root nil)

                    (callback))
                  timeout))

              (.. element -classList (add class-exit-active))
              (.. element -classList (remove class-exit)))))))))

(defn trigger-out! [{::keys [root] :as env} callback]
  (.begin-exit root callback))

(defn make-group [opts env]
  (GroupRoot. env opts))

(defn transition-group [opts child]
  (with-meta (assoc opts :child child) {`ap/as-managed make-group}))
