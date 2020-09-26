(defproject thheller/shadow-experiments "0.0.2"
  :description "WARNING: Experimental Code! Changing completely without notice! Do not use for anything but other experiments!"
  :url "http://github.com/thheller/shadow-experiments"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :repositories
  {"clojars" {:url "https://clojars.org/repo"
              :sign-releases false}}

  :dependencies
  [[org.clojure/clojure "1.10.1" :scope "provided"]
   [org.clojure/clojurescript "1.10.773" :scope "provided"]]

  :resource-paths
  ["src/resources"]

  :source-paths
  ["src/main"]

  :test-paths
  ["src/test"]

  :profiles
  {:dev
   {:source-paths ["src/dev"]}
   :cljs-dev ;; using this in shadow-cljs so can't use dev profile (Cursive complains about circular dep)
   {:dependencies
    [[thheller/shadow-cljs "2.11.4"]]}})

