(ns conduit.frontend.env
  (:require
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.grove :as sg]))

(def test-articles
  [{:description "It's about CONDUIT" :epoch 1574598956325, :slug "article-title-conduit-ixlsfw", :updatedAt "2019-11-24T12:35:56.325Z", :createdAt "2019-11-24T12:35:56.325Z", :title "ARTICLE TITLE - CONDUIT", :author {:username "jack37", :bio nil, :image "https://static.productionready.io/images/smiley-cyrus.jpg", :following false}, :favoritesCount 0, :body "Here's the MARKDOWN.", :favorited false, :tagList []}
   {:description "Test1", :epoch 1574602712184, :slug "test5-x1icv1", :updatedAt "2019-11-24T13:38:32.184Z", :createdAt "2019-11-24T13:38:32.184Z", :title "Test5", :author {:username "devdatt", :bio nil, :image "https://static.productionready.io/images/smiley-cyrus.jpg", :following false}, :favoritesCount 0, :body "Test2", :favorited false, :tagList []}
   {:description "Mysql Views", :epoch 1574601040377, :slug "aa-578ubh", :updatedAt "2019-11-24T13:11:40.386Z", :createdAt "2019-11-24T13:10:40.377Z", :title "Mysql", :author {:username "freakpanda", :bio nil, :image "https://static.productionready.io/images/smiley-cyrus.jpg", :following false}, :favoritesCount 0, :body "1. They use same namespace so names must be unique than table name.\n2. Definations can be updated using ALTER VIEW.\n\n```SQL\nCREATE VIEW\n\nCREATE \n    [OR REPLACE]\n    [ALGORITHM = {UNDEFINED | MERGE | TEMPTABLE}]\n    [DEFINER = user]\n    [SQL SECURITY {DEFINER | INVOKER}]\n    VIEW view_name [(column_list)]\n    AS select statement\n    [WITH [CASCADED | LOCAL] CHECK OPTION]\n```", :favorited false, :tagList []}
   {:description "ee", :epoch 1574596585172, :slug "ewd-r4402h", :updatedAt "2019-11-24T11:56:25.172Z", :createdAt "2019-11-24T11:56:25.172Z", :title "ewd", :author {:username "ayushiB", :bio nil, :image "https://images.unsplash.com/photo-1503803548695-c2a7b4a5b875?ixlib=rb-1.2.1&w=1000&q=80", :following false}, :favoritesCount 0, :body "ee", :favorited false, :tagList []}
   {:description "lia testa", :epoch 1574602863589, :slug "test-m2j7af", :updatedAt "2019-11-24T13:41:03.589Z", :createdAt "2019-11-24T13:41:03.589Z", :title "test", :author {:username "mamadmamad", :bio nil, :image "https://static.productionready.io/images/smiley-cyrus.jpg", :following false}, :favoritesCount 0, :body "Hi from Iran, a prison:-)", :favorited false, :tagList ["sugar" "test"]}
   {:description "kfkeowkk", :epoch 1574602355526, :slug "ofkewofk-d2d6ej", :updatedAt "2019-11-24T13:32:35.526Z", :createdAt "2019-11-24T13:32:35.526Z", :title "ofkewofk", :author {:username "monnke", :bio nil, :image "https://static.productionready.io/images/smiley-cyrus.jpg", :following false}, :favoritesCount 0, :body "okopfew", :favorited false, :tagList []}
   {:description "hhah", :epoch 1574601571569, :slug "omg-fj095n", :updatedAt "2019-11-24T13:41:32.402Z", :createdAt "2019-11-24T13:19:31.569Z", :title "omggg", :author {:username "lzbstarNYC", :bio "I am Jared, I live in New York, and I am in Taishan now\n\n:D\n\nsuccessfull", :image "1", :following false}, :favoritesCount 0, :body "hsb", :favorited false, :tagList ["123" "erer" "sdf"]}
   {:description "Soon...", :epoch 1574598620719, :slug "angular-what-is-it-all-about-tvb8qh", :updatedAt "2019-11-24T12:30:20.719Z", :createdAt "2019-11-24T12:30:20.719Z", :title "Angular - what is it all about?", :author {:username "jack37", :bio nil, :image "https://static.productionready.io/images/smiley-cyrus.jpg", :following false}, :favoritesCount 0, :body "# The top 5 things I like about angular", :favorited false, :tagList []}
   {:description "about", :epoch 1574598478671, :slug "title-75lmqe", :updatedAt "2019-11-24T12:27:58.671Z", :createdAt "2019-11-24T12:27:58.671Z", :title "title", :author {:username "Ikemad", :bio nil, :image "https://static.productionready.io/images/smiley-cyrus.jpg", :following false}, :favoritesCount 0, :body "content", :favorited false, :tagList []}
   {:description "r", :epoch 1574598814651, :slug "r-9fdrdq", :updatedAt "2019-11-24T12:33:34.651Z", :createdAt "2019-11-24T12:33:34.651Z", :title "r", :author {:username "lia", :bio "xcZCzxc", :image "zxczxcxccxz", :following false}, :favoritesCount 0, :body "r", :favorited false, :tagList []}])

(defonce data-ref
  (-> {:active-page :home
       :tags ["butt" "test" "dragons" "training" "tags" "as" "coffee" "animation" "baby" "cars" "flowers" "caramel" "japan" "money" "happiness" "sugar" "clean" "sushi" "well" "cookies"]
       :articles-count 500}
      (with-meta {::db/schema (db/configure
                                {::article
                                 {:type :entity
                                  :attrs {:slug [:primary-key string?]
                                          :author [:one ::user]}}
                                 ::user
                                 {:type :entity
                                  :attrs {:username [:primary-key string?]}}})})
      (db/merge-seq ::article test-articles [:articles])
      (atom)))

(defonce app
  (-> {}
      (sa/init)
      (sg/env ::conduit data-ref)))
