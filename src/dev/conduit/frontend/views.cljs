(ns conduit.frontend.views
  (:require
    [clojure.string :as str]
    [shadow.experiments.arborist :as sa]
    [shadow.experiments.grove :as sg :refer (defc <<)]
    [conduit.model :as m]
    ))

;; initially ported from https://github.com/jacekschae/conduit
;; rewritten heavily to adapt to framework, mostly re-used hiccup parts

;; -- Helpers -----------------------------------------------------------------
;;
(defn format-date
  [date]
  (.toDateString (js/Date. date)))

(defn url-for [& parts]
  (str "/" (->> parts
                (map (fn [x]
                       (if (keyword? x)
                         (name x)
                         (str x))))
                (str/join "/"))))

(defn tags-list [tags]
  (when (seq tags)
    (<< [:ul.tag-list
         (sa/render-seq tags identity
           (fn [tag]
             (<< [:li.tag-default.tag-pill.tag-outline tag])))])))

(def author-join {::m/author [::m/username ::m/image]})

(defn author-image [author]
  (if ^boolean js/goog.DEBUG
    ;; annoying to load a bunch of images in dev
    "https://static.productionready.io/images/smiley-cyrus.jpg"
    (::m/image author)))

(defc article-meta [article-ident]
  [{::m/keys
    [user-is-author?
     user-can-follow?
     user-is-following-author?
     author
     createdAt
     favoritesCount
     favorited
     slug] :as data}
   (sg/query-ident article-ident
     [::m/slug
      ::m/user-is-article-author?
      ::m/user-can-follow-author?
      ::m/user-is-following-author?
      author-join])

   author-name (:username author)]

  (js/console.log "data" data)

  (<< [:div.article-meta
       [:a {:href (url-for :profile author-name)}
        [:img {:src (:image author)}] " "]
       [:div.info
        [:a.author {:href (url-for :profile author-name)} author-name]
        [:span.date (format-date createdAt)]]

       (cond
         user-is-author?
         (<< [:span
              [:a.btn.btn-sm.btn-outline-secondary
               {:href (url-for :editor slug)}
               [:i.ion-edit]
               [:span " Edit Article "]]
              " "
              [:a.btn.btn-outline-danger.btn-sm
               {:href (url-for :home)
                :on-click [:delete-article slug]}
               [:i.ion-trash-a]
               [:span " Delete Article "]]])

         user-can-follow?
         (<< [:span
              [:button.btn.btn-sm.action-btn.btn-outline-secondary
               {:on-click [:toggle-follow-user author-name]}
               [:i {:class (if user-is-following-author? "ion-minus-round" "ion-plus-round")}]
               [:span (if user-is-following-author? " Unfollow " " Follow ") author-name]]
              " "
              [:button.btn.btn-sm.btn-primary
               {:on-click [:toggle-favorite-article slug]
                :class (when (not favorited) "btn-outline-primary")}
               [:i.ion-heart]
               [:span (if favorited " Unfavorite Post " " Favorite Post ")]
               [:span.counter "(" favoritesCount ")"]]]))]))

(defc articles-preview [article-ident]
  [article-data (sg/query-ident article-ident
                  [::m/description
                   ::m/user-can-favorite?
                   author-join])]

  (let [{::m/keys [user-can-favorite? description slug created-at title author favorites-count favorited tag-list]}
        article-data

        username (::m/username author)]

    (<< [:div.article-preview
         [:div.article-meta
          [:a {:href (url-for :profile username)}
           [:img {:src (author-image author)}]]
          [:div.info
           [:a.author {:href (url-for :profile username)} username]
           [:span.date (format-date created-at)]]
          (when user-can-favorite?
            (<< [:button.btn.btn-primary.btn-sm.pull-xs-right
                 {:on-click [:toggle-favorite-article slug]
                  :class (when (not favorited) "btn-outline-primary")}
                 [:i.ion-heart " "]
                 [:span favorites-count]]))]
         [:a.preview-link {:href (url-for :article slug)}
          [:h1 title]
          [:p description]
          [:span "Read more ..."]
          (tags-list tag-list)]])))

(defn errors-list
  [errors]
  (<< [:ul.error-messages
       (pr-str errors)

       #_(for [[key [val]] errors]
           ^{:key key} [:li (str (name key) " " val)])]))

;; -- Header ------------------------------------------------------------------
;;
(defc ui-header []
  [{:keys [active-page user]}
   (sg/query-root [:user :active-page])

   main-suspense
   (sg/env-watch ::sg/suspense-keys [::main])]

  (<< [:nav.navbar.navbar-light
       [:div.container
        [:a.navbar-brand {:href (url-for :home)}
         "conduit"
         ;; FIXME: add proper loading spinner or so
         (when main-suspense
           (str " [loading]"))]
        (if (empty? user)
          (<< [:ul.nav.navbar-nav.pull-xs-right
               [:li.nav-item
                [:a.nav-link {:href (url-for :home) :class (when (= active-page :home) "active")} "Home"]]
               [:li.nav-item
                [:a.nav-link {:href (url-for :login) :class (when (= active-page :login) "active")} "Sign in"]]
               [:li.nav-item
                [:a.nav-link {:href (url-for :register) :class (when (= active-page :register) "active")} "Sign up"]]])
          (<< [:ul.nav.navbar-nav.pull-xs-right
               [:li.nav-item
                [:a.nav-link {:href (url-for :home) :class (when (= active-page :home) "active")} "Home"]]
               [:li.nav-item
                [:a.nav-link {:href (url-for :editor "new") :class (when (= active-page :editor) "active")}
                 [:i.ion-compose "New Article"]]]
               [:li.nav-item
                [:a.nav-link {:href (url-for :settings) :class (when (= active-page :settings) "active")}
                 [:i.ion-gear-a "Settings"]]]
               [:li.nav-item
                [:a.nav-link {:href (url-for :profile (:username user)) :class (when (= active-page :profile) "active")} (:username user)
                 [:img.user-pic {:src (:image user)}]]]]))]]))

;; -- Footer ------------------------------------------------------------------
;;
(defc ui-footer []
  []
  (<< [:footer
       [:div.container
        [:a.logo-font {:href (url-for :home)} "conduit"]
        [:span.attribution
         "An interactive learning project from "
         [:a {:href "https://thinkster.io"} "Thinkster"]
         ". Code & design licensed under MIT."]]]))

;; -- Home --------------------------------------------------------------------
;;
(defc ui-home []
  [data
   (sg/query-root
     [:filter
      :tags
      ::m/home-articles
      ::m/articles-count
      :user])]

  (let [{:keys [filter tags user]
         ::m/keys [home-articles articles-count]}
        data]
    (<< [:div.home-page
         [:div.container.page
          [:div.row
           [:div.col-md-9
            [:div.feed-toggle
             [:ul.nav.nav-pills.outline-active
              (when-not (empty? user)
                (<< [:li.nav-item
                     [:a.nav-link {:href (url-for :home)
                                   :class (when (:feed filter) "active")
                                   :on-click [:get-feed-articles {:offset 0 :limit 10}]} "Your Feed"]]))
              [:li.nav-item
               [:a.nav-link {:href (url-for :home)
                             :class (when-not (or (:tag filter) (:feed filter)) "active")
                             :on-click [:get-articles {:offset 0 :limit 10}]} "Global Feed"]]
              (when (:tag filter)
                (<< [:li.nav-item
                     [:a.nav-link.active
                      [:i.ion-pound] (str " " (:tag filter))]]))]]

            (sa/render-seq home-articles identity articles-preview)

            (when-not (< articles-count 10)
              (<< [:ul.pagination
                   (sa/render-seq
                     (range (/ articles-count 10))
                     identity
                     (fn [offset]
                       (<< [:li.page-item
                            {:class (when (= (* offset 10) (:offset filter)) "active")
                             :on-click [:get-articles (if (:tag filter)
                                                        {:offset (* offset 10)
                                                         :tag (:tag filter)
                                                         :limit 10}
                                                        {:offset (* offset 10)
                                                         :limit 10})]}
                            [:a.page-link {:href (url-for :home)} (+ 1 offset)]])))]))]

           [:div.col-md-3
            [:div.sidebar
             [:p "Popular Tags"]
             [:div.tag-list
              (sa/render-seq tags identity
                (fn [tag]
                  (<< [:a.tag-pill.tag-default
                       {:href (url-for :home)
                        :on-click [:get-articles {:tag tag
                                                  :limit 10
                                                  :offset 0}]}
                       tag])))]]]]]])))

(defn ui-page-home []
  (<< [:div.home-page
       [:div.banner
        [:div.container
         [:h1.logo-font "conduit"]
         [:p "A place to share your knowledge."]]]]

      (sg/suspense
        {:fallback (<< [:div.container "Loading Articles ..."])
         :key ::home
         :timeout 500}
        (ui-home))))

;; -- Login -------------------------------------------------------------------
;;
(defc ui-login []
  [form
   (sg/form {:email "" :password ""})

   {:keys [email password] :as credentials}
   (sg/form-values form)

   {:keys [errors]}
   (sg/query-root [:errors])

   :login-user
   (fn [env e]
     (js/console.log "login-user" form))]

  (<< [:div.auth-page
       [:div.container.page
        [:div.row
         [:div.col-md-6.offset-md-3.col-xs-12
          [:h1.text-xs-center "Sign in"]
          [:p.text-xs-center
           [:a {:href (url-for :register)} "Need an account?"]]
          #_(when (:login errors)
              (errors-list (:login errors)))
          [:form {:on-submit [:login-user]}
           [:fieldset.form-group
            [:input.form-control.form-control-lg
             {:type "text"
              :placeholder "Email"
              ::sg/form-field [form :email]}]]

           [:fieldset.form-group
            [:input.form-control.form-control-lg
             {:type "password"
              :placeholder "Password"
              ::sg/form-field [form :password]}]]
           [:button.btn.btn-lg.btn-primary.pull-xs-right
            {}
            "Sign in"]]]]]]))

;; -- Register ----------------------------------------------------------------
;;
(defc ui-register []
  [form (sg/form {:username "" :email "" :password ""})

   :register-user
   (fn [env e]
     (js/console.log "register-user" form))]

  (<< [:div.auth-page
       [:div.container.page
        [:div.row
         [:div.col-md-6.offset-md-3.col-xs-12
          [:h1.text-xs-center "Sign up"]
          [:p.text-xs-center
           [:a {:href (url-for :login)} "Have an account?"]]
          #_(when (:register-user errors)
              (errors-list (:register-user errors)))
          [:form {:on-submit [:register-user]}
           [:fieldset.form-group
            [:input.form-control.form-control-lg
             {:type "text"
              :placeholder "Your Name"
              ::sg/form-field [form :username]}]]
           [:fieldset.form-group
            [:input.form-control.form-control-lg
             {:type "text"
              :placeholder "Email"
              ::sg/form-field [form :email]}]]
           [:fieldset.form-group
            [:input.form-control.form-control-lg
             {:type "password"
              :placeholder "Password"
              ::sg/form-field [form :password]}]]
           [:button.btn.btn-lg.btn-primary.pull-xs-right
            {}
            "Sign up"]]]]]]))

;; -- Profile -----------------------------------------------------------------
;;
(defc ui-page-profile []
  [{profile :active-profile :as data}
   (sg/query-root
     [{:active-profile
       [:written-articles
        :is-own-profile?
        :user-is-following?
        :favorites]}])]

  (let [{:keys [username image bio user-is-following?]} profile]

    (<< [:div (pr-str data)]
        [:div.profile-page
         [:div.user-info
          [:div.container
           [:div.row
            [:div.col-xs-12.col-md-10.offset-md-1
             [:img.user-img {:src image}]
             [:h4 username]
             [:p bio]
             (if (:is-own-profile? profile)
               (<< [:a.btn.btn-sm.btn-outline-secondary.action-btn {:href (url-for :settings)}
                    [:i.ion-gear-a] " Edit Profile Settings"])
               (<< [:button.btn.btn-sm.action-btn.btn-outline-secondary {:on-click [:toggle-follow-user username]}
                    [:i {:class (if user-is-following? "ion-minus-round" "ion-plus-round")}]
                    [:span (if user-is-following? (str " Unfollow " username) (str " Follow " username))]]))]]]]
         [:div.container
          [:div.row
           [:div.col-xs-12.col-md-10.offset-md-1
            [:div.articles-toggle
             [:ul.nav.nav-pills.outline-active
              [:li.nav-item
               [:a.nav-link {:href (url-for :profile username) :class (when (seq (:articles profile)) "active")} "My Articles"]]
              [:li.nav-item
               [:a.nav-link {:href (url-for :favorited username) :class (when (seq (:favorites profile)) "active")} "Favorited Articles"]]]]

            "FIXME: profile articles"
            #_(articles-list (:articles profile))]]]])))

;; -- Settings ----------------------------------------------------------------
;;
(defc ui-settings []
  [form (sg/form {})]
  #_[{:keys [bio email image username] :as user} @(subscribe [:user])
     default {:bio bio :email email :image image :username username}
     user-update (reagent/atom default)]

  (<< [:div.settings-page
       [:div.container.page
        [:div.row
         [:div.col-md-6.offset-md-3.col-xs-12
          [:h1.text-xs-center "Your Settings"]
          [:form
           [:fieldset
            [:fieldset.form-group
             [:input.form-control
              {:type "text"
               :placeholder "URL of profile picture"
               ::sg/form-field [form :image]}]]
            [:fieldset.form-group
             [:input.form-control.form-control-lg
              {:type "text"
               :placeholder "Your Name"
               ::sg/form-field [form :username]}]]
            [:fieldset.form-group
             [:textarea.form-control.form-control-lg
              {:rows "8"
               :placeholder "Short bio about you"
               ::sg/form-field [form :bio]}]]
            [:fieldset.form-group
             [:input.form-control.form-control-lg
              {:type "text"
               :placeholder "Email"
               ::sg/form-field [form :email]}]]
            [:fieldset.form-group
             [:input.form-control.form-control-lg
              {:type "password"
               :placeholder "Password"
               ::sg/form-field [form :password]}]]
            [:button.btn.btn-lg.btn-primary.pull-xs-right
             {:on-click [:update-user]}
             "Update Settings"]]]
          [:hr]
          [:button.btn.btn-outline-danger
           {:on-click [:logout-user]}
           "Or click here to logout."]]]]]))

;; -- Editor ------------------------------------------------------------------
;;
(defc ui-editor []
  [form (sg/form {})
   active-article nil]
  #_[{:keys [title description body tagList slug] :as active-article} @(subscribe [:active-article])
     tagList (str/join " " tagList)
     default {:title title :description description :body body :tagList tagList}
     content-ref (reagent/atom default)

     ::upsert-article
     (fn [env event]
       (.preventDefault event)
       (sg/run-tx env :upsert-article {:slug slug
                                       :article {:title (str/trim (or (:title content) ""))
                                                 :description (str/trim (or (:description content) ""))
                                                 :body (str/trim (or (:body content) ""))
                                                 :tagList (str/split (:tagList content) #" ")}}))
     ]

  (<< [:div.editor-page
       [:div.container.page
        [:div.row
         [:div.col-md-10.offset-md-1.col-xs-12
          #_(when (:upsert-article errors)
              [errors-list (:upsert-article errors)])
          [:form
           [:fieldset
            [:fieldset.form-group
             [:input.form-control.form-control-lg
              {:type "text"
               :placeholder "Article Title"
               ::sg/form-field [form :title]}]]
            [:fieldset.form-group
             [:input.form-control
              {:type "text"
               :placeholder "What's this article about?"
               ::sg/form-field [form :description]}]]
            [:fieldset.form-group
             [:textarea.form-control
              {:rows "8"
               :placeholder "Write your article (in markdown)"
               ::sg/form-field [form :body]}]]
            [:fieldset.form-group
             [:input.form-control
              {:type "text"
               :placeholder "Enter tags"
               ::sg/form-field [form :tagList]}]]
            [:button.btn.btn-lg.btn-primary.pull-xs-right
             {:on-click [::upsert-article]}
             (if active-article
               "Update Article"
               "Publish Article")]]]]]]]))

(defc ui-comment [comment]
  [{:keys [id createdAt body author user-is-comment-author?]}
   (sg/query-ident comment
     [:id
      :createdAt
      :body
      {:author [:username :image]}
      :user-is-comment-author?])]

  (<< [:div.card
       [:div.card-block
        [:p.card-text body]]
       [:div.card-footer
        [:a.comment-author {:href (url-for :profile (:username author))}
         [:img.comment-author-img {:src (:image author)}]]
        " "
        [:a.comment-author {:href (url-for :profile (:username author))} (:username author)]
        [:span.date-posted (format-date createdAt)]
        (when user-is-comment-author?
          [:span.mod-options {:on-click [:delete-comment id]}
           [:i.ion-trash-a]])]]))

;; -- Article -----------------------------------------------------------------
;;

(defc ui-article-comment-form [article]
  [{::m/keys [current-user]}
   (sg/query-ident article
     [{::m/current-user [::m/image]}])

   form (sg/form {})

   :post-comment
   (fn [env event]
     (.preventDefault event)
     (sg/run-tx env [:post-comment @form])
     (sg/form-reset! form))]

  (<< [:form.card.comment-form
       [:div.card-block
        [:textarea.form-control
         {:placeholder "Write a comment..."
          :rows "3"
          ::sg/form-field [form :body]}]]
       [:div.card-footer
        [:img.comment-author-img {:src (::m/image current-user)}]
        [:button.btn.btn-sm.btn-primary
         {:on-click [:post-comment]} "Post Comment"]]]))

(defc ui-article [article]
  [{::m/keys [user-is-author? user-can-comment?] :as article-data}
   (sg/query-ident article
     [::m/title
      ::m/body
      ::m/comments
      ::m/user-is-author?
      {::m/current-user
       [::m/image]}
      ::m/user-can-comment?])]

  (<< [:div.article-page
       [:div.banner
        [:div.container
         [:h1 (::m/title article-data)]
         (article-meta article)]]

       [:div.container.page
        [:div.row.article-content
         [:div.col-md-12
          [:p (::m/body article-data)]]]
        (tags-list (::m/tag-list article-data))
        [:hr]
        [:div.article-actions
         (article-meta article)]
        [:div.row
         [:div.col-xs-12.col-md-8.offset-md-2
          (cond
            user-can-comment?
            (ui-article-comment-form article)

            (not user-is-author?)
            (<< [:p
                 [:a {:href (url-for :register)} "Sign up"]
                 " or "
                 [:a {:href (url-for :login)} "Sign in"]
                 " to add comments on this article."]))

          (sa/render-seq (:comments article-data) identity ui-comment)
          ]]]]))

(defc ui-page-article []
  [{:keys [active-article]} (sg/query-root [:active-article])]
  (ui-article active-article))

(defc ui-root []
  [{:keys [active-page]} (sg/query-root [:active-page])]
  (<< (ui-header)
      (sg/suspense
        {:fallback "Loading ..."
         :key ::main
         :timeout 500}
        (case active-page
          :home (ui-page-home)
          :login (ui-login)
          :register (ui-register)
          :profile (ui-page-profile)
          :settings (ui-settings)
          :editor (ui-editor)
          :article (ui-page-article)
          (ui-page-home)))
      (ui-footer)))


