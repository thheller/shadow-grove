(ns build
  (:require
    [shadow.css.build :as cb]
    [clojure.java.io :as io]))

(defn css-release []

  (let [build-state
        (-> (cb/start)
            (cb/index-path (io/file "src" "main") {}))]

    (spit (io/file "src" "ui-release" "shadow-css-index.edn")
      (pr-str
        {:version 1
         :namespaces (:namespaces build-state)}))

    ;; playground
    (let [build-state
          (-> build-state
              (cb/index-path (io/file "src" "dev") {})
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
              (cb/index-path (io/file "src" "dev") {})
              (cb/generate '{:ui {:include [dummy*]}})
              (cb/write-outputs-to (io/file "examples" "dummy" "css")))]

      (doseq [mod (:outputs build-state)
              {:keys [warning-type] :as warning} (:warnings mod)]
        (prn [:CSS (name warning-type) (dissoc warning :warning-type)])))

    ;; examples, one css file per example so they don't interfere
    ;; examples/public/css/<name>.css from examples.<name> and what it requires
    (let [build-state
          (-> build-state
              (cb/index-path (io/file "src" "dev") {})
              ;; the index also reads .clj files, so examples/common.clj (the macros)
              ;; can shadow common.cljs, which holds the css
              (cb/index-file (io/file "src" "dev" "examples" "common.cljs")))]
      (doseq [^java.io.File dir (.listFiles (io/file "examples" "public"))
              :when (.exists (io/file dir "index.html"))
              :let [entry (symbol (str "examples." (.getName dir)))]
              :when (get-in build-state [:namespaces entry])]
        (let [build-state
              (-> build-state
                  (cb/generate {(keyword (.getName dir)) {:entries [entry]}})
                  (cb/write-outputs-to (io/file "examples" "public" "css")))]

          (doseq [chunk (vals (:chunks build-state))
                  {:keys [warning-type] :as warning} (:warnings chunk)]
            (prn [:CSS entry (name warning-type) (dissoc warning :warning-type)])))))

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