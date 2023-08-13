(defproject com.thheller/shadow-grove "0.5.1"
  :description "A ClojureScript system to build browser based frontends"
  :url "http://github.com/thheller/shadow-grove"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :repositories
  {"clojars" {:url "https://clojars.org/repo"
              :sign-releases false}}

  :dependencies
  [[org.clojure/clojure "1.11.1" :scope "provided"]
   [org.clojure/clojurescript "1.11.60" :scope "provided"]
   [com.thheller/shadow-css "0.4.3"]]

  :resource-paths
  ["src/resources"]

  :source-paths
  ["src/main"]

  :test-paths
  ["src/test"]

  :profiles
  {:dev
   {:source-paths ["src/dev"]
    :dependencies
    []}
   :cljs-dev
   {:dependencies
    [[thheller/shadow-cljs "2.25.2"]
     ]}})

