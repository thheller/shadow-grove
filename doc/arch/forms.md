# Forms

Forms are one of the most essential things in frontend UIs. Just displaying data is quite boring. At some point you'll want to let your users modify it in some way.

Although all the mechanisms for working with forms are present in `shadow-grove` currently I consider it a substantial missing piece to have no first class form support. Forms can get complex rather quickly and unreasonably complex if you factor in accessibility concerns (eg. `aria-*` attributes).

## Form Problem: Rendering

There are certain aspects in the rendering mode used by `shadow-grove` (as well as `react` and others) where you end up in a render cycle that can be problematic.

```clojure
(defc ui-form []
  (bind data-ref (atom {:hello "world"}))
  (bind {:keys [hello] :as data} (sg/watch data-ref))

  (render
    (<< [:form {:on-submit ::submit!}
         [:label {:for "hello"} "Hello: "]
         [:input {:id "hello" :type "text" :value hello :on-input ::input!}]
         [:button {:type "submit"} "Go!"]]))

  (event ::submit! [env ev e]
    (js/console.log "submit" data))

  (event ::input! [env ev e]
    (swap! data-ref assoc :hello (.. e -target -value)))) 
```

I'm using a local `atom` here, which I consider a total anti-pattern, but it is useful for keeping this example short and concise. The same problem arises when using the properly normalized db and EQL queries. The steps that occur are:

- On first mount the `data-ref` atom is created with the initial state of `{:hello "world"}`. This will only run once.
- The second `bind` will watch the first binding `data-ref`. It will trigger whenever the value in `data-ref` is modified.
- It gets the `:hello` value out of the data and the `render` uses it to set the `:value` of the input.
- The `input` event of the text input will trigger with any input made. So suppose we add a `!`. It'll trigger the `::input!` event with `(.. e -target -value)` being `hello!`.
- We then update the `data-ref` accordingly, which will in turn trigger the second hook to update.
- Since `hello` changed we will also and up in `render` again to set `:value` to `"hello!"`. 

As you may have guessed this render was entirely unnecessary. It was already at that value since it originated from there.

This can get way out of control if you do something async (eg. talk to a server) which may take some time. If the user continues typing you now may need to abort that work. At the very least you need to make sure you don't end up resetting the value back to something outdated. Just debouncing the event is not a solution.

One way for dealing with this in `react` is via "controlled" and "uncontrolled" components. You could do the same in `shadow-grove` but I consider this lacking in several regards.

## Form Goals

- Avoid unnecessary renders.
- Deal with `id` and `aria-*` related attributes in some declarative manner.
- Neatly integrated into the core library
- Extensible. Probably protocol based so custom input components can be created.
- Composable. Nesting form inputs is common, one form may become an attribute of another form.
- Validation. HTML standard form validation is not enough, full current form state needs to be considered for some validations.
- Error Messages. Ideally should also cover with error messages in some way, although that may be best dealt with directly in render.

**I have not yet settled on something for this.** For now everything is in the exact same place that `react` has been in since release. It is definitely workable but I'd like something better integrated and less manual.