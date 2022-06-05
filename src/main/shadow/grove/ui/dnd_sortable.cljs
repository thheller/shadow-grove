(ns shadow.grove.ui.dnd-sortable
  (:require
    [goog.positioning :as gpos]
    [shadow.cljs.modern :refer (defclass)]
    [shadow.grove :as sg :refer (defc <<)]
    [shadow.arborist.protocols :as ap]
    [shadow.arborist.attributes :as attr]
    [shadow.arborist.common :as common]
    [shadow.grove.protocols :as gp]
    [shadow.grove.components :as comp]
    ))

(declare SortableSeed)

;; FIXME: test this, there should be smarter way to do this?
(defn splice [items item-idx move-to-idx]
  (let [item
        (nth items item-idx)

        remove-item-xf
        (remove #(identical? % item))

        items
        (into [] remove-item-xf items)

        before
        (subvec items 0 move-to-idx)

        after
        (subvec items move-to-idx)]

    (-> []
        (into before)
        (conj item)
        (into after))))


(comment
  (splice [1 2 3 :x] 3 0)

  (splice [:x 1 2 3] 0 3))

(defclass SortableNode
  (field env)
  (field root)
  (field items)
  (field key-fn)
  (field item-fn)

  (field hover-idx nil)
  (field drag-idx nil)
  (field drag-anchor nil)
  (field drag-indicator nil)
  (field did-drop? false)

  (constructor [this env items key-fn item-fn]
    (set! this -env env)
    (set! this -items items)
    (set! this -key-fn key-fn)
    (set! this -item-fn item-fn)
    (set! root (common/managed-root (assoc env ::sortable this)))
    (.render this))

  ap/IManaged
  (supports? [this next]
    (instance? SortableSeed next))

  (dom-sync! [this ^SortableSeed next]
    (set! this -options (.-options next))
    (set! this -items (.-items next))
    (set! this -key-fn (.-key-fn next))
    (set! this -item-fn (.-item-fn next))
    (.render this))

  (dom-insert [this parent anchor]
    (ap/dom-insert root parent anchor))

  (dom-first [this]
    (ap/dom-first root))

  (dom-entered! [this]
    (ap/dom-entered! root))

  (destroy! [this ^boolean dom-remove?]
    (ap/destroy! root dom-remove?)
    (when drag-indicator
      (.remove drag-indicator)))

  Object
  (render [this]
    (ap/update! root
      (sg/keyed-seq items key-fn
        (fn [item idx key]
          (item-fn item
            {::idx idx
             ::dragging (= drag-idx idx)
             ::hovering (= hover-idx idx)})
          ))))

  (add-target [this ^js node idx]
    (set! (. node -ondragover)
      (fn [e]
        (when (not= drag-idx idx)
          (.preventDefault e))

        ;; FIXME: debounce this, dragover fires way too often to reposition constantly
        (when (and drag-indicator drag-anchor)
          (gpos/positionAtAnchor
            drag-anchor
            gpos/Corner.TOP_LEFT
            drag-indicator
            gpos/Corner.BOTTOM_LEFT
            ))))

    (set! (. node -ondragenter)
      (fn [e]
        ;; FIXME: these all need to check whether the drag is ours
        ;; might have started somewhere else and not belong to this "group"

        (when drag-indicator
          (set! (.. drag-indicator -style -display)
            (if (= idx drag-idx)
              "none"
              "block"))

          (set! (.. drag-indicator -style -width) (str (.-clientWidth node) "px")))

        (set! hover-idx idx)
        (set! drag-anchor node)

        (.render this)
        ))

    (set! (. node -ondragleave)
      (fn [e]
        ))

    (set! (. node -ondrop)
      (fn [e]
        (.stopPropagation e)
        (.preventDefault e)

        (when (not= idx drag-idx)
          (set! did-drop? true)
          (let [new-items (splice items drag-idx idx)]
            (ap/handle-dom-event!
              (::comp/component env)
              env
              "drop"
              {:e ::sorted!
               :items-before items
               :items-after new-items
               :item-idx drag-idx
               :target-idx idx}
              e
              ))))))

  (remove-target [this ^js node idx]
    (set! (. node -ondragover) nil)
    (set! (. node -ondragenter) nil)
    (set! (. node -ondragleave) nil)
    (set! (. node -ondrop) nil))

  (add-draggable [this ^js node idx]
    (set! (. node -draggable) true)

    (set! (. node -ondragend)
      (fn [e]
        (.remove drag-indicator)
        (set! drag-indicator nil)
        (set! drag-idx nil)
        (set! hover-idx nil)

        (.render this)
        ))

    (set! (. node -ondragstart)
      (fn [e]
        (set! drag-idx idx)
        (set! did-drop? false)

        (set! (.. e -dataTransfer -effectAllowed) "move")
        (.. e -dataTransfer (setData "text/plain" (str idx)))

        ;; FIXME: make this use styleable
        (let [div (js/document.createElement "div")]
          (set! (.. div -style -display) "none")
          (set! (.. div -style -position) "absolute")
          (set! (.. div -style -backgroundColor) "red")
          (set! (.. div -style -height) "5px")
          (set! (.. div -style -width) "300px")
          (set! (.. div -style -top) "0px")

          (js/document.body.append div)
          (set! drag-indicator div)
          ))))

  (remove-draggable [this ^js node idx]
    (set! (. node -draggable) false)
    (set! (. node -ondragstart) nil)
    ))

(attr/add-attr ::target
  (fn [{::keys [^SortableNode sortable]} node oval nval]
    ;; FIXME: should probably warn when not used correctly
    (when sortable
      (if nval
        (.add-target sortable node (::idx nval))
        (.remove-target sortable node (::idx oval))))))

(attr/add-attr ::draggable
  (fn [{::keys [^SortableNode sortable]} node oval nval]
    (when sortable
      (if nval
        (.add-draggable sortable node (::idx nval))
        (.remove-draggable sortable node (::idx oval))))))

(deftype SortableSeed [items key-fn item-fn]
  ap/IConstruct
  (as-managed [this env]
    (SortableNode.
      env
      items
      key-fn
      item-fn)))

(defn keyed-seq [items key-fn item-fn]
  (SortableSeed. items key-fn item-fn))