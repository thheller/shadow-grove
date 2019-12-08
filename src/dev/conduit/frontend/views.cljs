(ns conduit.frontend.views
  (:require
    [clojure.string :as str]
    [shadow.experiments.arborist :as sa :refer (defc <<)]
    [shadow.experiments.grove-main :as sg]
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

(defc tags-list [{:keys [tags]}]
  []
  (<< [:ul.tag-list
       (sa/render-seq tags identity
         (fn [tag]
           (<< [:li.tag-default.tag-pill.tag-outline tag])))]))

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
   (sg/query article-ident
     [::m/slug
      ::m/user-is-article-author?
      ::m/user-can-follow-author?
      ::m/user-is-following-author?
      author-join])

   author-name (:username author)]

  ;; (js/console.log "data" data)

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
  [article-data (sg/query article-ident
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
          (tags-list {:tags tag-list})]])))

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
   (sg/query [:user :active-page])]

  (<< [:nav.navbar.navbar-light
       [:div.container
        [:svg {:style {:width "40px" :height "40px"} :viewBox "0 0 68.4 68.4"}
         [:g {:fill "none"}
          [:g {:stroke "#000" :stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"}
           [:path {:d "m60.866 25.111a7.837 7.837 0 0 0 -1.1-2.234 7.723 7.723 0 0 0 -6.31-3.277c-4.275 0-8.073 3.509-7.784 7.816.67 10.053 14.318 20.826 14.318 20.826 15.123-12.016 14.317-20.634 14.317-20.634a7.8 7.8 0 0 0 -7.581-8 7.442 7.442 0 0 0 -6.53 3.947" :transform "translate(-29.391 -12.618)"}]
           [:path {:d "m58.569 118.227a2.216 2.216 0 0 1 -.257 4.421m.445-4.393s-10.064-2-12.565-2.529c-1.425-.306-3.046-.78-4.275-1.161a10.88 10.88 0 0 0 -8.265.659c-2.6 1.35-5.344 4.029-6.833 9.3" :transform "translate(-17.265 -73.414)"}]
           [:path {:d "m67.3 139.779s1.938-1.678 3.424-1.749c1.849-.089 13.342.029 13.68.032" :transform "translate(-43.324 -88.835)"}]
           [:path {:d "m56.949 113.312 13.42-6.145a2.715 2.715 0 0 1 3.438 1.012 2.715 2.715 0 0 1 -1.069 3.876c-6.331 3.206-24.015 12.469-25.119 12.95l-.31.125a11.974 11.974 0 0 1 -4.428.6h-14.7m39.819-17.794a2.7 2.7 0 0 0 -.424-1.457 2.715 2.715 0 0 0 -3.438-1.012l-12.707 5.846m8-3.962a2.7 2.7 0 0 0 -.424-1.457 2.715 2.715 0 0 0 -3.438-1.012l-11.042 5.088m-24.407 7.93 10.623 10.62" :transform "translate(-12.952 -67.356)"}]]
          [:path {:d "m0 0h68.4v68.4h-68.4z"}]]]
        [:a.navbar-brand {:href (url-for :home)}
         "conduit"]
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
  [{::m/keys [home-articles articles-count]
    :keys [filter tags user] :as data}
   (sg/query
     [:filter
      :tags
      ::m/home-articles
      ::m/articles-count
      :user])]

  ;; FIXME: this query completes once before home-articles are actually loaded
  ;; this prevents suspense from working since this claims to be ready
  ;; should somehow handle this in the worker so that it doesn't answer with an unfinished query

  (<< [:div.home-page
       (when (empty? user)
         (<< [:div.banner
              [:div.container
               [:h1.logo-font "conduit"]
               [:p "A place to share your knowledge."]]]))

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
                     tag])))]]]]]]))

;; -- Login -------------------------------------------------------------------
;;
(defc ui-login []
  [form
   (sg/form {:email "" :password ""})

   {:keys [email password] :as credentials}
   (sg/form-values form)

   {:keys [errors]}
   (sg/query [:errors])

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
  [{profile :active-profile}
   (sg/query [{:active-profile
               [:written-articles
                :is-own-profile?
                :user-is-following?
                :favorites]}])

   {:keys [username image bio user-is-following?]}
   profile]

  (<< [:div.profile-page
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
          #_ (articles-list (:articles profile))]]]]))

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
   (sg/query comment
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
(defc ui-article [article]
  [form (sg/form {})

   :post-comment
   (fn [env event]
     (.preventDefault event)
     (sg/run-tx env [:post-comment @form])
     (sg/form-reset! form))

   article-data
   (sg/query article [:title :body :comments])

   {:keys [user]}
   (sg/query [:user])]

  (<< [:div.article-page
       [:div.banner
        [:div.container
         [:h1 (:title article-data)]
         (article-meta article)]]

       [:div.container.page
        [:div.row.article-content
         [:div.col-md-12
          [:p (:body article-data)]]]
        (tags-list {:tags (:tagList article-data)})
        [:hr]
        [:div.article-actions
         (article-meta article)]
        [:div.row
         [:div.col-xs-12.col-md-8.offset-md-2
          #_(when (:comments errors)
              (errors-list {:errors (:comments errors)}))
          (if-not (empty? user)
            (<< [:form.card.comment-form
                 [:div.card-block
                  [:textarea.form-control
                   {:placeholder "Write a comment..."
                    :rows "3"
                    ::sg/form-field [form :body]}]]
                 [:div.card-footer
                  [:img.comment-author-img {:src (:image user)}]
                  [:button.btn.btn-sm.btn-primary
                   {:on-click [:post-comment]} "Post Comment"]]])
            (<< [:p
                 [:a {:href (url-for :register)} "Sign up"]
                 " or "
                 [:a {:href (url-for :login)} "Sign in"]
                 " to add comments on this article."]))

          (sa/render-seq (:comments article-data) identity ui-comment)
          ]]]]))

(defc ui-page-article []
  [{:keys [active-article]} (sg/query [:active-article])]
  (ui-article active-article))

(defc ui-root []
  [{:keys [active-page]} (sg/query [:active-page])]
  (<< (ui-header)

      (sa/suspense

        (case active-page
          :home (ui-home)
          :login (ui-login)
          :register (ui-register)
          :profile (ui-page-profile)
          :settings (ui-settings)
          :editor (ui-editor)
          :article (ui-page-article)
          (ui-home))

        {:fallback "yo"
         :timeout 1000})
      (ui-footer)))
