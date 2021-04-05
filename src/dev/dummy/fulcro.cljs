(ns dummy.fulcro
  (:require
    [clojure.pprint :refer [pprint]]
    [cljs.core.async :as async]
    [taoensso.timbre :as log]
    [com.fulcrologic.fulcro.application :as app]
    [com.fulcrologic.fulcro.components :as fc]
    [com.fulcrologic.fulcro.networking.mock-server-remote :refer [mock-http-server]]
    [com.fulcrologic.fulcro.alpha.raw :as raw]
    [com.fulcrologic.fulcro.mutations :as m]
    [com.wsscode.pathom.core :as p]
    [com.wsscode.pathom.connect :as pc]
    [com.fulcrologic.fulcro.data-fetch :as df]
    [com.fulcrologic.fulcro.algorithms.data-targeting :as dt]
    [com.fulcrologic.fulcro.algorithms.denormalize :as fdn]
    [com.fulcrologic.fulcro.dom.events :as evt]
    [com.fulcrologic.fulcro.algorithms.form-state :as fs]
    [com.fulcrologic.fulcro.algorithms.normalized-state :as fns]
    [com.fulcrologic.fulcro.algorithms.lookup :as ah]
    [edn-query-language.core :as eql]
    [shadow.experiments.grove :as sg :refer (defc <<)]
    [shadow.experiments.grove.protocols :as gp]
    [shadow.experiments.grove.db :as db]
    [shadow.experiments.grove.runtime :as rt]
    [shadow.experiments.grove.components :as comp]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; shadow-grove gluecode, provides a use-root hook, would be part of lib
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(deftype FulcroRoot [root-key model options ^:mutable data app component idx]
  gp/IBuildHook
  (hook-build [this c i]
    ;; get app from component env/context, no global references
    (let [app (comp/get-env c ::app)]
      (FulcroRoot. root-key model options data app c i)))

  gp/IHook
  (hook-init! [this]
    (let [root-fn
          (raw/add-root! app root-key model
            (assoc options
              :receive-props
              (fn [new-data]
                (set! data new-data)
                ;; signal component to re-render
                (comp/hook-invalidate! component idx))))]

      ;; dunno why this is a function I need to call
      ;; guess some react-ism
      (root-fn)))

  ;; doesn't seem to go async, no need for suspense
  (hook-ready? [this]
    true)

  (hook-value [this]
    (get data root-key))

  ;; don't know if there is a way to pull data ouf of the state
  ;; this will be triggered at render-time when component is just before update
  ;; but we already did the work in the :receive-props callback
  (hook-update! [this]
    true)

  ;; this would be called when the arguments to use-root changed
  (hook-deps-update! [this new-val]
    ;; FIXME: probably ok to have changing deps
    ;; but I don't know what I'd need to call in fulcro to tell it
    (throw (ex-info "shouldn't have changing deps?" {})))

  (hook-destroy! [this]
    (raw/remove-root! app root-key)))

(defn use-root [root-key model options]
  (FulcroRoot. root-key model options {} nil nil nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mock Server and database, in Fulcro client format for ease of use in demo
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce pretend-server-database
  (atom
    {:settings/id
     {1
      {:settings/id 1
       :settings/marketing? true
       :settings/theme :light-mode}}

     :account/id
     {1000
      {:account/id 1000
       :account/email "bob@example.com"
       :account/password "letmein"}}

     :user/id
     {100
      {:user/id 100
       :user/name "Emily"
       :user/email "emily@example.com"
       :user/settings [:settings/id 1]}}}))

(pc/defresolver settings-resolver [_ {:settings/keys [id]}]
  {::pc/input #{:settings/id}
   ::pc/output [:settings/marketing? :settings/theme]}
  (get-in @pretend-server-database [:settings/id id]))

(pc/defresolver user-resolver [_ {:user/keys [id]}]
  {::pc/input #{:user/id}
   ::pc/output [:user/name :user/email :user/age {:user/settings [:settings/id]}]}
  (try
    (-> @pretend-server-database
        (get-in [:user/id id])
        (update :user/settings #(into {} [%])))
    (catch :default e
      (log/error e "Resolver fail"))))

(pc/defresolver current-user-resolver [_ _]
  {::pc/output [{:current-user [:user/id]}]}
  {:current-user {:user/id 100}})

(pc/defmutation server-save-form [_ form-diff]
  {::pc/sym `save-form}
  (swap! pretend-server-database
    (fn [s]
      (reduce-kv
        (fn [final-state ident changes]
          (reduce-kv
            (fn [fs k {:keys [after]}]
              (if (nil? after)
                (update-in fs ident dissoc k)
                (assoc-in fs (conj ident k) after)))
            final-state
            changes))
        s
        form-diff)))
  (log/info "Updated server to:" (with-out-str (pprint @pretend-server-database)))
  nil)

;; For the UISM DEMO
(defonce session-id (atom 1000)) ; pretend like we have server state to remember client

(pc/defresolver account-resolver [_ {:account/keys [id]}]
  {::pc/input #{:account/id}
   ::pc/output [:account/email]}
  (select-keys (get-in @pretend-server-database [:account/id id] {}) [:account/email]))

(pc/defresolver session-resolver [_ {:account/keys [id]}]
  {::pc/output [{:current-session [:account/id]}]}
  (if @session-id
    {:current-session {:account/id @session-id}}
    {:current-session {:account/id :none}}))

(pc/defmutation server-login [_ {:keys [email password]}]
  {::pc/sym `login
   ::pc/output [:account/id]}
  (let [accounts (vals (get @pretend-server-database :account/id))
        account (first
                  (filter
                    (fn [a] (and (= password (:account/password a)) (= email (:account/email a))))
                    accounts))]
    (when (log/spy :info "Found account" account)
      (reset! session-id (:account/id account))
      account)))

(pc/defmutation server-logout [_ _]
  {::pc/sym `logout}
  (reset! session-id nil))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Registration for the above resolvers and mutations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def resolvers [user-resolver current-user-resolver settings-resolver server-save-form account-resolver session-resolver
                server-login server-logout])

(def pathom-parser
  (p/parser
    {::p/env
     {::p/reader
      [p/map-reader
       pc/reader2
       pc/open-ident-reader]
      ::pc/mutation-join-globals [:tempids]}
     ::p/mutate pc/mutate
     ::p/plugins
     [(pc/connect-plugin {::pc/register [resolvers]})
      (p/post-process-parser-plugin p/elide-not-found)
      p/error-handler-plugin]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Client. We close over the server parser above using a mock http server. The
;; extra level of indirection allows hot code reload to refresh the mock server and
;; parser without wiping out the app.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def User
  (raw/formc
    [:ui/saving?
     [df/marker-table '_]
     :user/id
     :user/name
     {:user/settings
      [:settings/id
       :settings/marketing?]}]))

(comment
  (fc/component-options User)
  (fc/get-ident User {:user/id 34})
  (fc/get-initial-state User {})
  (fc/get-ident User {:user/id 34})
  (-> (fc/get-query User) (meta) (:component) (fc/get-query))
  )

(m/defmutation initialize-form [_]
  (action [{:keys [state]}]
    (let [ident (get @state :current-user)]
      (fns/swap!-> state
        (fs/add-form-config* User ident {:destructive? true})
        (fs/mark-complete* ident)))))

(defn- set-saving! [{:keys [state ref]} tf]
  (when (vector? ref)
    (swap! state assoc-in (conj ref :ui/saving?) tf)))

(m/defmutation save-form [form]
  (action [{:keys [state] :as env}]
    (set-saving! env true)
    (let [idk (raw/id-key form)
          id (get form idk)]
      (when (and idk id)
        (swap! state fs/entity->pristine* [idk id]))))
  (ok-action [env] (set-saving! env false))
  (error-action [env] (set-saving! env false))
  (remote [env]
    (-> env
        (m/with-params (fs/dirty-fields form true))
        (m/returning User))))

(defc ui-user-form []
  (bind {:ui/keys [saving?]
         :user/keys [id name settings] :as u}
    (use-root :current-user User {}))

  ;; could be a generic event handler provided by lib
  (event ::update-field!
    [{::keys [app] :as env} {:keys [field] :as ev} e]
    (raw/set-value!! app u field (evt/target-value e)))

  (event ::toggle-marketing!
    [{::keys [app]} ev e]
    (raw/update-value!! app settings :settings/marketing? not))

  (event ::submit!
    [{::keys [app]} ev e]
    (fc/transact! app [(save-form u)] {:ref [:user/id id]}))

  (render
    (js/console.log "render" u)

    (let [loading? (df/loading? (get-in u [df/marker-table ::user]))

          {:settings/keys [marketing?]} settings]

      (<< [:div.ui.segment
           [:h2 "Form"]
           [:div {:class (str "ui form " (when loading? " loading"))}
            [:div.field
             [:label "Name"]
             [:input
              {:value (or name "")
               :on-change {:e ::update-field! :field :user/name}}]]

            [:div.ui.checkbox
             [:input {:type "checkbox"
                      :on-change ::toggle-marketing!
                      :checked (boolean marketing?)}]

             [:label "Marketing Emails?"]]

            [:div.field
             [:button.ui.primary.button
              {:class
               (str "ui primary button"
                    (when-not (fs/dirty? u) " disabled")
                    (when saving? " loading"))

               :on-click ::submit!}

              "Save"]]]]))))

(defn ui-root []
  (<< [:div "Hello World"]
      (ui-user-form)))

;; not actually using data-ref but setup requires this for now
(defonce data-ref
  (-> {}
      (db/configure {})
      (atom)))

;; shadow-grove runtime
(defonce rt-ref
  (-> {}
      (rt/prepare data-ref ::db)))

;; fulcro runtime would ideally just be part of the rt-ref
;; but keeping it separate for simplicity for now
(defonce raw-app
  (let [process-eql
        (fn [eql]
          (async/go
            (let [tm (async/timeout 300)]
              (async/<! tm)
              (pathom-parser {} eql))))

        app
        (app/fulcro-app
          {:remotes
           {:remote
            (mock-http-server {:parser process-eql})}})]

    (tap> app)

    app))

(defn ^:dev/after-load start []
  (sg/start ::ui (ui-root)))

(defonce root-el
  (js/document.getElementById "root"))

(defn init []
  ;; doing this here for now
  ;; IMHO component lifecycle is not the correct place to do this
  (df/load! raw-app :current-user User
    {:post-mutation `initialize-form
     :marker ::user})

  (sg/init-root rt-ref ::ui root-el
    ;; passes down raw-app in context so everything that needs it can access it
    {::app raw-app})

  (start))