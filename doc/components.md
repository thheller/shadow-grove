# Components

The goal of the Component design is to gain access to incremental computation based on application events (eg. state changes). It is based on a simple macro to make them easy to write. It all looks like regular function calls from an API perspective, the complicated parts are implemented as reusable/composable hooks.

I name them hooks since naming is hard and they somewhat resemble the same concept in React. If I find a better name I'll most certainly change it.

When definining a component via `defc` it takes several hooks representing as a list starting with a symbol. Currently there are `bind`, `hook`, `render` and `effect`.

- `(bind <name> <&body>)` sets up a named binding that can be used in later hooks. If the value changes all other hooks using that binding will also be triggered
- `(hook <&body>)` is the same as `bind` but does not have an output other hooks can use
- `(render <&body>)` produces the component output, can only occur once
- `(event <event-kw> <arg-vector> <&body>)` creates an event handler fn

Note that all of these execute in order so `bind` and `hook` cannot be used after `render`. They may also only use bindings declared before themselves. They may shadow the binding name if desired. `event` can be used after or before `render` as it will trigger when the event fires are not during component rendering.

This design was changed from previous iterations because `(defc name [arg] [hook1 (foo)] ...)` was too generic and this design should make things easier to adapt for server-side stuff at some point. Server side would never process events so could easily just drop `(event ...)` definitions.

Example dummy component:
```clojure
;; shouldn't have global state, but convenient for demo purposes
(defonce bad-global-atom (atom {:foo 0 :bar 0}))

(defc ui-foo []
  (bind {:keys [foo bar] :as data}
    (watch bad-global-atom))

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

Unlike `react` hooks all of these are wired up at compile time and will only trigger if their "inputs" change (eg. previous `bind` name or component arguments).

In the above example the `compute-expensive` will only re-trigger if `foo` changes, not if `bar` changes.

To make things actually composable `hook` or `bind` can return values that implement a simple protocol which will give them access to the component lifecycle. In fact there is one default implementation for this protocol that will be used unless overriden. It is also the simplest hook implementation.

```clojure
(deftype SimpleVal [^:mutable val]
  p/IHook
  (hook-init! [this])
  (hook-ready? [this] true)
  (hook-value [this] val)
  (hook-update! [this])
  (hook-deps-update! [this new-val]
    (let [updated? (not= new-val val)]
      (set! val new-val)
      updated?))
  (hook-destroy! [this]))

(extend-protocol p/IBuildHook
  default
  (hook-build [val component idx]
    (SimpleVal. val)))
```

First the component will call `hook-build` on the result of the function call. This will just wrap the value in a `SimpleVal` hook by default. It doesn't need access to the component so it'll just discard those.

Once constructed the `SimpleVal` will just hold on to the actual val and return it to be using as the `bind` value. Initially the `hook-init!` lifecycle fn is also called but `SimpleVal` doesn't need to do anything. The `SimpleVal` instance will only be created once, whenever any dependencies of that hook were changed the component will run the hook function again and call `hook-deps-update!` with the return value, which will just replace the hook-value and tell the component whether it needs to update others that depended on the hook.

When the component is destroyed it'll also call `hook-destroy!` in case some cleanup needs to be done.

Note that every hook must return the same "type" of value as once constructed a hook instance remains for the entire lifecycle of the component. `react` hooks have the same restrictions. Simple values can change over time but hooks returning `IBuildHook` implementations must always return that type and a hook may know how to change itself but it cannot change to another type.

Another simple hook is used in the implementation of `watch`. It just returns an instance of `AtomWatch` which implements the hook protocols and also acts as its own builder.

```clojure
(defn watch [the-atom]
  (AtomWatch. the-atom nil nil nil))
```

```clojure
(deftype AtomWatch [the-atom ^:mutable val component idx]
  p/IBuildHook
  (hook-build [this c i]
    (AtomWatch. the-atom nil c i))

  p/IHook
  (hook-init! [this]
    (set! val @the-atom)
    (add-watch the-atom this
      (fn [_ _ _ _]
        ;; don't take new-val just yet, it may change again in the time before
        ;; we actually get to an update. deref'ing when the actual update occurs
        ;; which will also reset the dirty flag
        (comp/invalidate! component idx))))

  (hook-ready? [this] true) ;; born ready
  (hook-value [this] val)
  (hook-update! [this]
    ;; time to actually deref, any change after this will invalidate and trigger
    ;; an update again. this doesn't mean the value will actually get to render.
    (set! val @the-atom)
    true)
  (hook-deps-update! [this new-val]
    (throw (ex-info "shouldn't have changing deps?" {})))
  (hook-destroy! [this]
    (remove-watch the-atom this)))
```

Again the same flow of events. First the `hook-build` fn is called, this time keeping a reference to the `component`. Then the component calls `hook-init!` which will do the initial `deref` and then add the actual watch to `the-atom`. Whenever the watch triggers it will invalidate the hook value which will tell the component that it needs to update this hook before it can render again. So whenever the component gets to this point it'll call `hook-update!` which then do the `deref` again. When the component is destroyed the `hook-destroy!` will make sure the watch is removed as well.

I don't expect the user to actually write hooks. They'll likely be provided by libraries/frameworks. The above already enables a basic `reagent` style model.

Just like `react` hooks there are some rules for hooks. Luckily the macro already enforces that things are called in the correct order. Basically the only thing that must be avoided is conditional results. So no `thing (when one-thing (watch foo))` since that might return `nil` which would turn it into a `SimpleVal` hook. Once created the type of the hook must remain consistent and cannot be changed. It cannot be turned into an `AtomWatch` later.

A dummy todomvc example can be found [here](https://github.com/thheller/shadow-experiments/blob/master/src/dev/todomvc/simple.cljs), compiled demo [here](https://code.thheller.com/demos/todomvc/).

Note that this is all still extremely experimental.

To be continued ...