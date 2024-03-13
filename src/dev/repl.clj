(ns repl
  (:require
    [build]
    [clojure.java.io :as io]
    [shadow.cljs.devtools.server.fs-watch :as fs-watch]))

(defonce css-watch-ref (atom nil))

(defn start
  {:shadow/requires-server true}
  []
  ;; until I can figure out a clean API for this
  (build/css-release)

  (reset! css-watch-ref
    (fs-watch/start
      {}
      [(io/file "src" "main")
       (io/file "src" "dev")]
      ["cljs" "cljc" "clj"]
      (fn [updates]
        (try
          (build/css-release)
          (catch Exception e
            (prn [:css-failure e]))))))

  ::started)

(defn stop []
  (when-some [css-watch @css-watch-ref]
    (fs-watch/stop css-watch)
    (reset! css-watch-ref nil))

  ::stopped)

(defn go []
  (stop)
  (start))
