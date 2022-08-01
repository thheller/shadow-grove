(ns repl
  (:require
    [clojure.java.io :as io]
    [shadow.css.build :as cb]
    [shadow.cljs.devtools.server.fs-watch :as fs-watch]))

(defonce css-ref (atom nil))
(defonce css-watch-ref (atom nil))

(defn generate-css []
  (let [result
        (-> @css-ref
            (cb/generate '{:ui {:include [shadow.grove.examples*]}})
            (cb/write-outputs-to (io/file "examples" "app" "public" "css")))]

    (prn :CSS-GENERATED)

    (doseq [mod (:outputs result)
            {:keys [warning-type] :as warning} (:warnings mod)]

      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))

    (println))

  (let [result
        (-> @css-ref
            (cb/generate '{:ui {:include [dummy*]}})
            (cb/write-outputs-to (io/file "examples" "dummy" "css")))]

    (prn :CSS-GENERATED)

    (doseq [mod (:outputs result)
            {:keys [warning-type] :as warning} (:warnings mod)]

      (prn [:CSS (name warning-type) (dissoc warning :warning-type)]))

    (println))

  :done)

(comment
  (generate-css))

(defn start
  {:shadow/requires-server true}
  []
  ;; until I can figure out a clean API for this
  (reset! css-ref
    (-> (cb/start)
        (cb/index-path (io/file "src" "dev") {})
        (cb/index-path (io/file "src" "main") {})))

  (generate-css)

  (reset! css-watch-ref
    (fs-watch/start
      {}
      [(io/file "src" "main")
       (io/file "src" "dev")]
      ["cljs" "cljc" "clj"]
      (fn [updates]
        (try
          (doseq [{:keys [file event]} updates
                  :when (not= event :del)]
            (swap! css-ref cb/index-file file))

          (generate-css)
          (catch Exception e
            (prn :css-build-failure)
            (prn e))))))

  ::started)

(defn stop []
  (when-some [css-watch @css-watch-ref]
    (fs-watch/stop css-watch)
    (reset! css-ref nil))

  ::stopped)

(defn go []
  (stop)
  (start))
