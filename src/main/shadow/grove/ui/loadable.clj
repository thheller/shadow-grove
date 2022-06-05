(ns shadow.grove.ui.loadable)

(defmacro refer-lazy
  ([lazy-name]
   `(refer-lazy ~lazy-name ~(symbol (name lazy-name))))
  ([lazy-name local-name]
   `(def ~(with-meta local-name {:tag 'function})
      (wrap-loadable
        (shadow.lazy/loadable ~lazy-name)))))