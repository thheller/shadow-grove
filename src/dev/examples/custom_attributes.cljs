(ns examples.custom-attributes
  "A custom attribute attaches behavior to the one element it is on. A namespaced key in
   an element's attribute map, `{::autosize 6}`, calls the handler registered for it,
   with the element and the old and new value. Use it to wrap a DOM API the element
   itself is the subject of: a listener, an observer, a third-party widget bound to the
   node. The built-in `:dom/ref`, `::keyboard/listen` and `:ui/href` work this way."
  (:require
    [examples.common :as c]
    [shadow.arborist.attributes :as attr]
    [shadow.grove :as sg :refer (defc << css)]))

(defonce app (sg/get-runtime :app))

;; ## registering a handler
;;
;; `(attr/add-attr ::autosize (fn [env node oval nval] ...))` registers the handler for
;; `{::autosize value}` on any element (`shadow.arborist.attributes`). It is called
;;
;; - on create, `oval` nil,
;; - when the value changes, both set,
;; - when the attribute goes away, `nval` nil. Fragments also clear namespaced attributes
;;   when they are destroyed, so removing the element calls it with `nval` nil too: the
;;   cleanup lives in the handler. Make it idempotent anyway.
;;
;; It is not called when the value stays `=`. Keep per-node state on the node itself, the
;; handler is a plain function shared by every element using the attribute.
;;
;; On create the node is not in the document yet, and attributes after this one in the
;; map (here `:value`) may not be set yet. Anything measuring has to wait, here a
;; `requestAnimationFrame`.
;;
;; `::autosize` grows a textarea with its content, up to `nval` rows. Try it: type a few
;; lines, change the max (a change call), turn it off (`nval` nil, the listener is
;; removed and the textarea stays as it is), remove the textarea (`nval` nil from the
;; destroyed fragment).

(defn fit! [^js node]
  (let [max-rows (.-shadow$autosize-max node)]
    (set! (.-rows node) 1)
    (while (and (< (.-rows node) max-rows)
                (> (.-scrollHeight node) (.-clientHeight node)))
      (set! (.-rows node) (inc (.-rows node))))))

(attr/add-attr ::autosize
  (fn [env ^js node oval nval]
    (c/log! "::autosize handler, oval" (pr-str oval) "nval" (pr-str nval))
    (cond
      ;; off -> on
      (and (nil? oval) (some? nval))
      (let [listener #(fit! node)]
        (set! (.-shadow$autosize-max node) nval)
        (set! (.-shadow$autosize-listener node) listener)
        (.addEventListener node "input" listener)
        (js/requestAnimationFrame listener))

      ;; on -> off
      (nil? nval)
      (when-some [listener (.-shadow$autosize-listener node)]
        (.removeEventListener node "input" listener)
        (set! (.-shadow$autosize-listener node) nil))

      ;; value changed
      :else
      (do (set! (.-shadow$autosize-max node) nval)
          (fit! node)))))

;; ## not for reaching other elements
;;
;; An attribute handler only touches its own node. It is not a way for a parent to find
;; and change elements rendered by its children, that goes around the rendering. Pass
;; the data down instead (`(ui-item item highlight?)`), or let the child query it. If it
;; really has to be DOM interop, put a `:dom/ref` on the container and use
;; `(.querySelectorAll @container "...")` in an event slot or effect ([refs](/refs/)).
;;
;; - A namespaced attribute without a registered handler throws "namespaced attribute
;;   without setter".
;; - Plain `data-*` and `aria-*` attributes are set as DOM attributes, they need no
;;   handler.

;; # page

(defc ui-root []
  (ref state (atom {:max-rows 6 :on? true :show? true}))
  (bind {:keys [max-rows on? show?]} (sg/watch state))

  (event ::max! [env ev e] (swap! state update :max-rows #(if (= 6 %) 3 6)))
  (event ::on! [env ev e] (swap! state update :on? not))
  (event ::show! [env ev e] (swap! state update :show? not))

  (render
    (c/page "custom attributes"
      {"registering a handler"
       (<< [:div {:class [c/$row (css :mb-2)]}
            [:button {:class c/$btn :on-click ::max!} (str "max " (if (= 6 max-rows) 3 6) " rows")]
            [:button {:class c/$btn :on-click ::on!} (if on? "turn it off" "turn it on")]
            [:button {:class c/$btn :on-click ::show!} (if show? "remove the textarea" "add it back")]]
           (when show?
             (<< [:textarea#text
                  {::autosize (when on? max-rows)
                   :class [c/$input (css :w-full)]
                   :value "one\ntwo"}])))})))

(defn init []
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
