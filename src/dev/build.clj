(ns build
  (:require
    [shadow.css.build :as cb]
    [clojure.java.io :as io]))

(defn css-release []
  (let [build-state
        (-> (cb/start)
            (cb/index-path (io/file "src" "dev") {})
            (cb/generate
              '{:ui
                {:include
                 [shadow.grove.examples*]}})
            (cb/write-outputs-to (io/file "examples" "app" "public" "css")))]

    (doseq [mod (:outputs build-state)
            {:keys [warning-type] :as warning} (:warnings mod)]

      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))
    ))

(comment
  (time
    (css-release)))