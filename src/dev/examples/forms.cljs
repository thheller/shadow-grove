(ns examples.forms
  "Two working styles for form state. Don't mix them on one field.

   - **KV-backed and controlled**: the value lives in KV, `:value` comes from a bind and
     `:on-input` runs a tx per keystroke. Right when something outside the field must
     overwrite it: a search box, a draft reset on route change.
   - **Owned by the form component**: the inputs own their text after mount, the form
     collects the edits in an atom, and only submit runs a tx. Right for editing an
     entity.

   A dialog flag or a list filter that only one component cares about is neither, it is
   an atom in a `ref` ([local-state](/local-state/))."
  (:require
    [clojure.string :as str]
    [examples.common :as c]
    [shadow.grove :as sg :refer (defc deftx << css)]))

(defonce app (sg/get-runtime :app))

;; ## controlled input backed by KV
;;
;; `:value` from a bind, `:on-input` writes it back. The clear button changes the value
;; from outside the field, which is the reason it lives in KV. Use `:on-input`, not
;; `:on-change`: change doesn't fire when the browser autocompletes.

(deftx search-input! []
  [tx-env ev e]
  (assoc-in tx-env [:ui :search] (.. e -target -value)))

(deftx search-clear! []
  [tx-env ev e]
  (assoc-in tx-env [:ui :search] ""))

(defc ui-controlled []
  (bind search (sg/kv-lookup :ui :search))
  (render
    (<< [:div {:class c/$row}
         [:input#search {:class c/$input :placeholder "controlled" :value search :on-input (search-input!)}]
         [:button {:class c/$btn :on-click (search-clear!)} "clear from outside"]
         "length " (count search)])))

;; ## uncontrolled, FormData on submit
;;
;; For fire-and-forget input without live validation: no `:value`, no handler per
;; keystroke. `:on-submit` with `:e/prevent-default` in the deftx base map, and the
;; handler reads the form from `(.-target e)`: one field via `.-value`, several with
;; `js/FormData`, or `(.. e -target -elements)`.
;;
;; Validation errors are data: the "failing" handler returns the env with the error set
;; instead of the change. Side effects that aren't KV writes (`localStorage`, focus) go
;; into an fx so the handler stays testable ([fx](/fx/)). Resetting the form here is a
;; small exception.

(defn form-value [form field]
  (str/trim (str (.get (js/FormData. form) field))))

(deftx add-tag! []
  {:e/prevent-default true}
  [tx-env ev e]
  (let [form (.-target e)
        tag (form-value form "tag")]
    (if (str/blank? tag)
      (assoc-in tx-env [:ui :tag-error] "a tag can't be blank")
      (do (.reset form)
          (-> tx-env
              (update-in [:ui :tags] conj tag)
              (assoc-in [:ui :tag-error] nil))))))

(defc ui-submit []
  (bind {:keys [tags tag-error]} (sg/kv-lookup :ui))
  (render
    (<< [:form {:class c/$row :on-submit (add-tag!)}
         [:input {:class c/$input :name "tag" :placeholder "uncontrolled, read on submit"}]
         [:button {:class c/$btn :type "submit"} "add tag"]
         "tags: " (pr-str tags)]
        (when tag-error
          (<< [:div#tag-error (css :text-red-700 :text-sm) tag-error])))))

;; ## the editor owns the draft
;;
;; The editor keeps the edits in `(ref edits (atom {}))` and passes `edits` to the field
;; editors. The atom never changes, so the fields don't update from args. Each field
;; writes its initial value into the input from a `:mount` effect, and doesn't set
;; `:value` in the fragment. Every keystroke `swap!`s the atom, no tx. Submit passes the
;; edits to a deftx (which would queue an [http-fx](/http-fx/) request in a real app).
;;
;; - Typing re-runs only `change-count` (a `sg/watch` of the atom with `count`). The
;;   editor's render badge moves when the count changes, not per keystroke.
;; - Why not `:value`: an `:on-input` that writes state renders again and sets the input
;;   to the value it already has, which can jump the caret. If the field owns the text,
;;   leave the node alone after mount.
;; - This only works if the editor mounts with the real initial value: suspend (or `when`
;;   the entity) before rendering the form, and take the entity id as `^:stable`, so
;;   another person remounts the editor and `:mount` fills the fields again
;;   ([stable-args](/stable-args/)).
;; - Several components sharing one draft through a helper is what `rt/claim-slot!` is for
;;   ([bind-helpers](/bind-helpers/)).

(defc ui-field [edits field initial]
  (ref input)
  (effect :mount [env]
    (set! @input -value (str initial)))
  (event ::input! [env ev e]
    (swap! edits assoc field (.. e -target -value)))
  (render
    (<< [:label {:class (css :flex :flex-col :text-sm :gap-1)}
         (name field)
         [:input {:dom/ref input :class c/$input :name (name field) :on-input ::input!}]])))

(deftx person-save! [id changes]
  [tx-env ev e]
  (update-in tx-env [:person id] merge changes))

(defc ui-editor [^:stable id]
  (ref edits (atom {}))
  (bind person (sg/kv-lookup :person id))
  (bind change-count (sg/watch edits count))

  (event ::save! [env ev e]
    (.preventDefault e)
    (sg/run-tx env (person-save! id @edits))
    (reset! edits {}))

  (render
    (<< [:form {:class (css :flex :flex-col :gap-2 {:max-width "20rem"}) :on-submit ::save!}
         (ui-field edits :name (:name person))
         (ui-field edits :email (:email person))
         [:div {:class c/$row}
          [:button#save {:class [c/$btn c/$btn-primary] :type "submit" :disabled (zero? change-count)}
           (str "save " change-count " change" (when (not= 1 change-count) "s"))]
          (c/render-count ::editor)]])))

;; # page

(defc ui-saved [id]
  (bind person (sg/kv-lookup :person id))
  (render (<< [:div#saved {:class c/$muted} "in KV: " (pr-str person)])))

(defc ui-root []
  (ref selected (atom 1))
  (bind id (sg/watch selected))
  (event ::select! [env {:keys [id]} e] (reset! selected id))

  (render
    (c/page "forms"
      {"controlled input backed by KV"
       (ui-controlled)

       "uncontrolled, FormData on submit"
       (ui-submit)

       "the editor owns the draft"
       (<< [:div {:class [c/$row (css :mb-2)]}
            [:button {:class c/$btn :on-click {:e ::select! :id 1}} "person 1"]
            [:button {:class c/$btn :on-click {:e ::select! :id 2}} "person 2"]]
           (ui-editor id)
           (ui-saved id))})))

(defn init []
  (sg/add-kv-table app :ui {} {:search "" :tags []})
  (sg/add-kv-table app :person {:primary-key :id}
    {1 {:id 1 :name "Ada" :email "ada@example.com"}
     2 {:id 2 :name "Grace" :email "grace@example.com"}})
  (c/render! app (ui-root)))

(defn ^:dev/after-load reload! []
  (c/render! app (ui-root)))
