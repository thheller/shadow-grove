(ns dummy.replicant
  (:require
    [shadow.arborist.interpreted]
    [shadow.arborist.attributes :as attr]
    [shadow.grove :as sg]
    ))

(defonce root-el
  (js/document.getElementById "root"))

(defonce data-ref
  (-> {}
      (atom)))

(defonce rt-ref
  (sg/prepare {} data-ref ::rt))

(attr/add-attr :replicant/key
  (fn [env node oval nval]))

(defn ^:dev/after-load start []
  ;; experimenting with things to maybe get parity with replicant features
  ;; https://github.com/cjohansen/replicant/issues/16

  ;; from README
  [:ul.cards
   [:li {:replicant/key 1} "Item #1"]
   [:li {:replicant/key 2} "Item #2"]
   [:li {:replicant/key 3} "Item #3"]
   [:li {:replicant/key 4} "Item #4"]]

  ;; grove handles collections via protocol, so this is currently a special case
  ;; https://github.com/thheller/shadow-grove/blob/master/doc/arch/collections.md

  ;; question is at what cost can this be implemented

  ;; one thought would be to use the attribute support to "upgrade" the node when a replicant/key is detected
  ;; sort of creating a key collection on mount, and then using that on update
  ;; seems tricky to establish boundaries though, no guarantee all nodes are gonna have keys

  ;; or pre-walking the children to look for keys, which is gonna be costly and often waste
  ;; could look for a "sign" in parent node? [:ul.cards {:here-be-keyed-children true} ...]

  ;; more common case will likely be something that uses for, map or whatever other seq producing thing
  [:ul.cards
   (for [{:keys [id name]} [{:id 1 :name "Item #1"}]]
     [:li {:replicant/key id} name])]

  ;; which ends up as
  '[:ul.cards
    ;; leaving an extra wrapping seq here, which is easier to detect
    ([:li {:replicant/key 1} "Item #1"])]

  ;; so how common would this actually be?
  ;; what happens if there is an entry row without key?
  [:ul.cards
   [:li "before"]
   [:li {:replicant/key 1} "Item #1"]
   [:li {:replicant/key 2} "Item #2"]
   [:li {:replicant/key 3} "Item #3"]
   [:li {:replicant/key 4} "Item #4"]
   [:li "after"]]

  (sg/render rt-ref root-el
    [:ul.cards
     ;; no longer just data, so meh
     (sg/keyed-seq
       [[:li {:replicant/key 1} "Item #1"]
        [:li {:replicant/key 2} "Item #2"]
        [:li {:replicant/key 3} "Item #3"]
        [:li {:replicant/key 4} "Item #4"]]
       (comp :replicant/key second)
       identity)]
    ))

(defn init []
  (start))
