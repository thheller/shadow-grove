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

To be continued ...


## claim once or more?

A `bind` slot can only be claimed once currently. Technically there is nothing stopping us from allowing multiple claims (and as such multiple refs).

```clojure
(bind x
  {:foo (sg/query-root [:foo])
   :bar (sg/query-root [:bar])})
```

This is currently not allowed and would need to be

```clojure
(bind foo (sg/query-root [:foo]))
(bind bar (sg/query-root [:bar]))
```

Which for this case even looks more pleasant, but this limitation might hurt the ability to compose things together.

The problem with variant one above is that it always runs both queries again, even if only one invalidated/changed. With two `bind` the component takes care to only run the actual query that invalidated.

react hooks compose better by not having that limitation. However, it means much more manual care must be taken to ensure only the minimal amount of code needs to run each cycle.

This limitation however also prevents some developer "errors". react hooks always need to execute in the exact same order and thus cannot be conditional.

```clojure
(bind x
  (when y
    (do-something y)))
```

This is technically ok now, but sort of "leaky". Say `y` toggles true-ish once and `do-something` performs something that claims the bind, such as running a db query. If `y` then turns `false` `do-something` does not run again, but it also doesn't clean up what was previously used. So the query it ran is still "active" and may invalidate to run the slot again. If `y` is still `false` it still doesn't do anything, but it might be better to have the query cleaned up.

So, the things I'm undecided on are:

- should once previous claims be cleaned up if not claimed again?
- should multiple claims be ok? if so can we do it without the hooks limitations?

I have been thinking about this for way too long, and decided to only allow "claim once" for now and seeing how often this becomes a problem in actual code.

There is an argument to be made that if we allow multiple claims we don't need `bind` or the entire `defc` abstraction at all. Could just work exactly like react hooks.

This could still be just an alternate implementation for those who want it.

## Previous Implementation

The implementation prior to this used a special "hook" return value and protocols. It was way too complex for what it ended up doing. The goal of that was to avoid magic runtime bindings, but I didn't like the design due to its complexity. The new version is also much more flexible and doesn't have some of the constraints the previous impl had, e.g. return values can be conditional now.