(ns examples.custom-attributes
  "Subtree context plus custom attributes: a node puts something into its children's
   env, and a namespaced attribute on elements below finds it there. `dnd_sortable` in
   the library works this way: the sortable container is in the env, items register
   with `::dnd/target` and `::dnd/draggable` attributes. Hosting children with an
   extended env is [child-host](/child-host/)."
  (:require
    [examples.common :as c]
    [shadow.arborist.attributes :as attr]
    [shadow.arborist.common :as common]
    [shadow.arborist.protocols :as ap]
    [shadow.grove :as sg :refer (defc << css)]))

(defonce app (sg/get-runtime :app))

;; ## a node in its subtree's env
;;
;; `Group` is the shared object: it knows the elements registered with it and can act on
;; them (highlight). `group-scope` is a node hosting its children in a `managed-root`
;; created with `(assoc env ::group group)`, so everything rendered below sees it.
;; Namespace your env keys.

(deftype Group [^:mutable items ^:mutable listeners]
  Object
  (add-item [this node label]
    (set! items (assoc items node label))
    (.changed this))
  (remove-item [this node]
    (set! items (dissoc items node))
    (.changed this))
  (changed [this]
    (doseq [f listeners] (f)))
  (highlight! [this on?]
    (doseq [node (keys items)]
      (.toggle (.-classList node) "group-highlight" on?))))

(declare GroupSeed)

(deftype GroupNode [env group root]
  ap/IManaged
  (supports? [this next] (instance? GroupSeed next))
  (dom-sync! [this ^GroupSeed next] (ap/update! root (.-body next)))
  (dom-insert [this parent anchor] (ap/dom-insert root parent anchor))
  (dom-first [this] (ap/dom-first root))
  (dom-entered! [this] (ap/dom-entered! root))
  (destroy! [this dom-remove?] (ap/destroy! root dom-remove?)))

(deftype GroupSeed [group body]
  ap/IConstruct
  (as-managed [this env]
    (GroupNode. env group (doto (common/managed-root (assoc env ::group group)) (ap/update! body)))))

(defn group-scope [group body]
  (GroupSeed. group body))

;; ## a namespaced attribute that finds it
;;
;; `(attr/add-attr ::item (fn [env node oval nval] ...))` registers a handler for
;; `{::item value}` on any element (`shadow.arborist.attributes`). It is called on create
;; (`oval` nil), on change, and when the attribute goes away (`nval` nil). Fragments also
;; clear namespaced attributes when they are destroyed, so removing the element calls it
;; with `nval` nil too: cleanup can live in the handler. Make it idempotent anyway.
;;
;; - A namespaced attribute without a registered handler throws "namespaced attribute
;;   without setter".
;; - Built-in ones: `:dom/ref` ([refs](/refs/)), `::keyboard/listen`
;;   ([keyboard](/keyboard/)), `:ui/href` (registered by `history/init!`,
;;   [routing](/routing/)).
;;
;; Try it: change a label (a change call), remove the attribute from "two" (nval nil),
;; remove the last element (nval nil from the destroyed fragment), "count" shows what is
;; registered.

(attr/add-attr ::item
  (fn [env node oval nval]
    (when-some [^Group group (::group env)]
      (c/log! "::item handler, oval" (pr-str oval) "nval" (pr-str nval))
      (if nval
        (.add-item group node nval)
        (.remove-item group node)))))

(defn items [group labels tagged?]
  (group-scope group
    (<< [:ul#group
         (sg/simple-seq labels
           (fn [label idx]
             (<< [:li {::item (when (or tagged? (not= idx 1)) label) :data-label label} label])))])))

;; ## IHandleDOMEvents
;;
;; Fragments send `:on-*` keyword and map values to `(::ap/dom-event-handler env)`. `defc`
;; installs itself there, which is why event maps reach `event` slots. A node can install
;; itself in its children's env to intercept those values, but the next `defc` below it
;; takes over again. Rarely needed: a node emitting events from its own callbacks sends
;; them to `(::comp/event-target env)` ([widget-node](/widget-node/)).

;; # page

;; the page reads the group through an atom it updates
(defonce group (Group. {} []))
(defonce group-info (atom {:registered 0 :connected 0}))

(defn refresh-info! []
  (reset! group-info
    {:registered (count (.-items group))
     :connected (count (filter #(.-isConnected %) (keys (.-items group))))}))

(defc ui-info []
  (bind {:keys [registered connected]} (sg/watch group-info))
  (render
    (<< [:div#info {:class c/$muted} "registered: " registered ", still in the document: " connected])))

(defc ui-root []
  (ref state (atom {:labels ["one" "two" "three"] :tagged? true :highlight? false}))
  (bind {:keys [labels tagged? highlight?]} (sg/watch state))

  (event ::untag! [env ev e] (swap! state update :tagged? not))
  (event ::drop! [env ev e] (swap! state update :labels #(vec (butlast %))))
  (event ::rename! [env ev e] (swap! state update-in [:labels 0] str "!"))
  (event ::highlight! [env ev e]
    (swap! state update :highlight? not)
    (.highlight! group (not highlight?)))
  (event ::refresh! [env ev e] (refresh-info!))

  (render
    (c/page "custom attributes"
      {"a namespaced attribute that finds it"
       (<< [:div {:class [c/$row (css :mb-2)]}
            [:button {:class c/$btn :on-click ::rename!} "change the first label"]
            [:button {:class c/$btn :on-click ::untag!} (if tagged? "remove the attribute from two" "add it back")]
            [:button {:class c/$btn :on-click ::drop!} "remove the last element"]
            [:button {:class c/$btn :on-click ::highlight!} "highlight registered"]
            [:button {:class c/$btn :on-click ::refresh!} "count"]]
           (items group labels tagged?)
           (ui-info))})))

(defn init []
  (set! (.-listeners group) [refresh-info!])
  (let [style (js/document.createElement "style")]
    (set! (.-textContent style) ".group-highlight { background: #fde68a; }")
    (.append js/document.head style))
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
