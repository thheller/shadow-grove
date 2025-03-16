(defproject com.thheller/shadow-grove "1.0.2"
  :description "A ClojureScript system to build browser based frontends"
  :url "http://github.com/thheller/shadow-grove"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :repositories
  {"clojars" {:url "https://clojars.org/repo"
              :sign-releases false}}

  :dependencies
  [[org.clojure/clojure "1.11.1" :scope "provided"]
   [org.clojure/clojurescript "1.11.132" :scope "provided"]
   [com.thheller/shadow-css "0.6.0"]]

  :resource-paths
  ["src/resources"]

  :source-paths
  ["src/main"]

  :test-paths
  ["src/test"]

  :profiles
  {:provided
   {:source-paths
    ["src/ui-release"]}
   :dev
   {:source-paths ["src/dev"]
    :dependencies
    []}
   :cljs-dev
   {:dependencies
    [[thheller/shadow-cljs "2.28.21"]
     ]}})

