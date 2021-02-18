(ns dummy.website
  (:require [hiccup.page :refer (html5)]))

(defn adjust-config [config]
  (-> config
      (assoc :FOO (System/getenv "FOO"))))

(defn services [config]
  {})

(defn web-index
  {:http/handle [:GET "/"]}
  [env]
  {:status 200
   :body "Hello World!"})

(defn web-product-page
  {:http/handle [:GET "/product/{product-id}"]}
  [env {:keys [product-id]}]
  {:status 200
   :body
   (html5 {:lang "de"}
     [:head
      [:title "Hello World!"]]
     [:body
      [:div "Product: " product-id]])})

(defn admin-only
  [env]
  :ok)

(defn web-admin-index
  {:http/handle [:GET "/admin"]
   :http/intercept [::admin-only]}
  [env]
  {:status 200
   :body "Hello World!"})
