(ns build
  (:require
    [shadow.css.build :as cb]
    [clojure.java.io :as io]))

(defn css-release []
  (let [build-state
        (-> (cb/start)
            (cb/index-path (io/file "src" "dev") {})
            (cb/index-path (io/file "src" "main") {}))]


    ;; playground
    (let [build-state
          (-> build-state
              (cb/generate
                '{:ui
                  {:entries
                   [shadow.grove.examples.app]}})
              (cb/write-outputs-to (io/file "examples" "app" "public" "css")))]

      (doseq [mod (:outputs build-state)
              {:keys [warning-type] :as warning} (:warnings mod)]
        (prn [:CSS (name warning-type) (dissoc warning :warning-type)])))

    ;; dummy
    (let [build-state
          (-> build-state
              (cb/generate '{:ui {:include [dummy*]}})
              (cb/write-outputs-to (io/file "examples" "dummy" "css")))]

      (doseq [mod (:outputs build-state)
              {:keys [warning-type] :as warning} (:warnings mod)]
        (prn [:CSS (name warning-type) (dissoc warning :warning-type)])))

    ;; devtools
    (let [build-state
          (-> build-state
              (cb/generate
                '{:ui
                  {:entries
                   [shadow.grove.devtools]}})
              (cb/minify)
              (cb/write-outputs-to (io/file "src" "ui-release" "shadow" "grove" "devtools" "dist" "css")))]

      (doseq [mod (:outputs build-state)
              {:keys [warning-type] :as warning} (:warnings mod)]
        (prn [:CSS (name warning-type) (dissoc warning :warning-type)])))
    ))

(comment
  (time
    (css-release)))