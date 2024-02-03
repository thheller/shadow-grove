# Components

The goal of the Component design is to gain access to incremental computation based on application events (eg. state changes). It is based on a macro to make them easy to write. It all looks like regular function calls from an API perspective, the complicated parts are implemented as "slots".

When defining a component via `defc` it takes several slots representing as a list starting with a symbol. Currently, there are `bind`, `hook`, `render` and `effect`.

- `(bind <name> <&body>)` sets up a named binding that can be used in later slots. If the value changes all other slots using that binding will also be triggered
- `(hook <&body>)` is the same as `bind` but does not have an output other slots can use, used purely for side effects
- `(render <&body>)` produces the component output, can only occur once
- `(event <event-kw> <arg-vector> <&body>)` creates an event handler fn

Note that all of these execute in order so `bind` and `hook` cannot be used after `render`. They may also only use bindings declared before themselves. They may shadow the binding name if desired. `event` can be used after or before `render` as it will trigger when the event fires are not during component rendering.

Example dummy component:
```clojure
;; shouldn't have global state, but convenient for demo purposes
(defonce bad-global-atom (atom {:foo 0 :bar 0}))

(defc ui-foo []
  (bind {:keys [foo bar] :as data}
    (sg/watch bad-global-atom))

  (bind baz
    (compute-expensive foo))
  
  (render
    (<< [:div {:on-click [::inc! :foo]} foo]
        [:div {:on-click [::inc! :bar]} bar]
        [:div baz])
  
  (event ::inc! [env e which]
    ;; env is the component env map, passed down from the root
    ;; e is the browser click event, not useful in this case
    ;; which is the argument used in the :on-click vector above
    (swap! bad-global-atom update which inc)
    )))
```

Unlike `react hooks` all of these are wired up at compile time and will only trigger if their "inputs" change (eg. previous `bind` name or component arguments). Conceptually the above component will create 2 "slot functions", which internally are referenced by their index. *(The actual implementation is a bit more complicated, but the concept stays the same)*

```clojure
[(fn [component] ;; idx 0
   (sg/watch bad-global-atom))
 (fn [component] ;; idx 1
   (let [foo (:foo (comp/get-slot-value component 0))]
     (compute-expensive foo)))]
```

The component ensures that these function execute only when needed, reducing the amount of code that needs to run per component render cycle substantially.

Slots by default are stateless, as in a function that returns a value, such as the above. They may opt into being stateful by "claiming" the `bind` slot. The process of claiming creates an `atom` which the code may then update whenever needed. Any update to this atom will notify the component to update and causing the slot code (and dependents) to run again.

For example the functionality behind the [sg/watch](https://github.com/thheller/shadow-grove/blob/65d61e64e10d3eeca77ccaeb42a9aa550ca35dfe/src/main/shadow/grove/components.cljs#L729-L764) function will watch the supplied atom and trigger an update when it changes.

`claim-bind!` will always return the same `atom` for its slot. Everything else is up to the code whether it runs again or re-uses previous values. Updating the `ref` while the slot fn is running will not trigger an update of itself, so doing updates there is fine.

The return value returned by the slot fn will be used as the value for the `bind` slot. If the value is equal to the previous returned value the component may decide to skip re-rendering the component and/or updating other dependent slots.

A `bind` slot can only be claimed once, since its return value is used for the `bind` name.

To be continued ...

## Previous Implementation

The implementation prior to this used a special "hook" return value and protocols. It was way too complex for what it ended up doing. The goal of that was to avoid magic runtime bindings, but I didn't like the design due to its complexity. The new version is also much more flexible and doesn't have some of the constraints the previous impl had, e.g. return values can be conditional now.