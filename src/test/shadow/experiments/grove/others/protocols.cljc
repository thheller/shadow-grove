(ns shadow.experiments.grove.protocols)

;; protocol is used in both CLJ and CLJS variants
;; just moved this in to this .cljc file to avoid repeating it
;; builder is not .cljc because I don't like working in files with lots of conditionals

(defprotocol IBuildTrees
  (fragment-start [this fragment-id node-count])
  (fragment-end [this])

  (element-open [this tag akeys avals abits specials])
  (element-close [this])
  (text [this val])

  (interpret [this val]))

(defrecord Ident [entity-type id])
