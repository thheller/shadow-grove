(ns shadow.grove.db.ident
  (:refer-clojure :exclude (ident?)))

;; keeping this in its own namespace only for hot-reload purposes
;; keeping it in db directly is a little annoying otherwise when working on db namespace

;; not using record since they shouldn't act as maps
;; users should treat these as black box and never directly look at the fields
;; also does a bunch of other stuff I don't want

#?(:cljs
   (deftype Ident [entity-type id ^:mutable _hash]
     ILookup
     (-lookup [this key]
       (case key
         :entity-type entity-type
         :id id
         nil))

     IHash
     (-hash [this]
       (if (some? _hash)
         _hash
         (let [x (bit-or 123 (hash id) (hash id))]
           (set! _hash x)
           x)))

     IEquiv
     (-equiv [this ^Ident other]
       (and (instance? Ident other)
            (keyword-identical? entity-type (.-entity-type other))
            (= id (.-id other))))

     IPrintWithWriter
     (-pr-writer [this writer opts]
       ;; gdb = grove db
       ;; using the full shadow.grove.db/ident is rather long
       ;; coupled with namespaced entity-type keyword things become way too long
       ;; inspect and otherwise printed
       (-write writer "#gdb/ident [")
       ;; can't pr-writer these since some native types don't implement IPrintWithWriter
       ;; and pr-str handles those
       (-write writer (pr-str entity-type))
       (-write writer " ")
       (-write writer (pr-str id))
       (-write writer "]"))

     Object
     (toString [this]
       (pr-str this)))

   ;; FIXME: should also be deftype
   :clj
   (do (defrecord Ident [entity-type id])

       (defmethod print-method Ident [ident writer]
         (.write writer "#gdb/ident [")
         ;; FIXME: shouldn't use pr-str?
         (.write writer (pr-str (:entity-type ident)))
         (.write writer " ")
         (.write writer (pr-str (:id ident)))
         (.write writer "]"))))


(defn ident? [thing]
  (instance? Ident thing))