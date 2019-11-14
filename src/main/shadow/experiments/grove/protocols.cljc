(ns shadow.experiments.grove.protocols)

(defprotocol TxData
  (commit! [_]))

;; not using record since they shouldn't act as maps
;; also does a bunch of other stuff I don't want
(deftype Ident [entity-type id]
  ILookup
  (-lookup [this key]
    (case key
      :entity-type entity-type
      :id id
      nil))

  IEquiv
  (-equiv [this ^Ident other]
    (and (instance? Ident other)
         (keyword-identical? entity-type (.-entity-type other))
         (= id (.-id other)))))
