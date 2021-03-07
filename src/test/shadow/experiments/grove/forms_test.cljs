(ns shadow.experiments.grove.forms-test
  (:require
    [cljs.test :as ct :refer (deftest is)]
    [shadow.experiments.grove :as sg :refer (defc <<)]))

(comment
  (def form
    (-> (form-describe)
        (form-add-field :name :string {})
        (form-add-field :flag :boolean {})
        (form-add-field :extra :string {})))

  ;; forms are boring, nobody wants to code them properly
  ;; but a proper form makes all the difference and is one of the most important aspects for user-driven input
  ;; doing them properly with all the event wiring and aria-* attributes is very difficult

  ;; can already do everything needed in the "old" style just using an atom
  ;; but I want some kind of abstraction that makes it easier to do correct forms

  ;; might be out of scope given how complex it is though

  (defc my-page []
    (bind loaded-data
      (sg/query [::my-data]))

    (bind form
      ;; must be able to initialize form with data loaded from other sources
      ;; if this data changes the form must recognize those changes
      ;; it must not dismiss those changes but it must also not mess with data
      ;; the user might currently be editing
      (form/init form loaded-data))


    (render

      (<< [:div
           ;; need helpers for other :aria-* related things on labels
           ;; directly setting attributes manually is tedious and error prone
           ;; footgun potential for conflicting :class etc.
           [:label {:form-label-attrs [form :name]} "Name"]

           ;; this would suck because there are a lot of extra attributes you'd need to manage
           ;; changing :class depending on field state empty/clean/dirty/invalid/valid
           ;; adding :id and other :aria-* related things
           ;; somehow needs to be wired up to form, can't rely on "magic" later
           [:input {:type "text"}]

           ;; going with the attribute path exposes many footguns where people start
           ;; adding custom :on-change or whatever events
           ;; conflicting :class/:id handling
           ;; must still be possible though if people really want custom things
           [:input {:input-attrs [form :name] :class "foo"}]

           ;; premade form fields that generate <input> dom elements, managing all attributes
           (form-input form :name {:data-foo "extra-attr"})

           ;; form errors/validation problems may want to add extra DOM elements
           (when (form-has-error? form :name)
             (<< [:div "Invalid Name."]))]

          ;; or the above just wrapped in a simple reusable helper
          (form-simple-input-with-label form :name "Name")

          ;; some kind of logic that just renders the entire form
          ;; when the dev don't care much about how it looks
          ;; forms are ultimately boring in most cases (eg. admin stuff)
          (form-simple form)

          ;; fields will have widely different DOM structures
          ;; must be flexible enough to allow this
          [:div
           [:label {:form-label-attrs [form :flag]}
            (form-checkbox form :flag)
            "Optional?"]]

          ;; must be possible to access current form state easily
          ;; to hide/show optional/contextual fields
          (when (form-get-value form :flag)
            (<< [:div
                 [:label {:form-label-attrs [form :extra]} "Extra Field"]
                 (form-input form :extra)]))))))
