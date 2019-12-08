(ns conduit.model)

;; pure data schema definition
;; cannot use anything from db namespace this this is supposed to be usable as a reference
;; in the main/UI as well. not sure if it ever actually would though.
;; FIXME: figure this out in a cleaner/better way
(def schema
  {::article
   {:type :entity
    :attrs {::slug [:primary-key string?]
            ::author [:one ::user]}}
   ::user
   {:type :entity
    :attrs {::username [:primary-key string?]}}})