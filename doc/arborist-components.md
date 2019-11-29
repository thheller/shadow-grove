# shadow.arborist - Components

The goal of the Component design is to gain access to incremental computation based on application events (eg. state changes). It is based on a simple macro to make them easy to write. It all looks like regular function calls from an API perspective, the complicated parts are implemented as reusable/composable hooks.


```clojure
;; shouldn't have global state, but convenient for demo purposes
(defonce bad-global-atom (atom {:foo 0 :bar 0}))

(defc ui-foo [{:keys [name] :as props}]
  [{:keys [foo] :as data}
   (watch bad-global-atom)

   bar
   (compute-expensive name)]
  
  (<< [:div foo bar]))
```

If it helps you can compare this syntactially to a regular `defn`.

```clojure
(defn ui-foo [{:keys [name] :as props}]
  (let [{:keys [foo] :as data}
        (watch bad-global-atom)
        
        bar
        (compute-expensive name)]

    (<< [:div foo bar])))
```

If you've written any code using `react` hooks you'll pretty much always have one `let` as the first thing the function calling the actual hooks and then the body of the let constructing the actual react elements.

The macro just does away with the let but still keeps a special bindings vector that will be sliced into smaller functions to allow incremental computation. Each binding has can run any arbitrary CLJS code but share some of the same rules that `react` hooks have. Part of that is basically already enforced by the `let` structure anyways.

So under the hood the component is actually defined as a sequence of functions that will be executed in order when needed and finally produce a render updating the actual DOM. Each hook will also be able to suspend work (eg. when needing to do something async) but that is not implemented yet.

As somewhat pseudo-ish code the above `defc` will produce something like

```clojure
(def ui-foo
  (make-component-config
    "some.ns/ui-foo"
    [(fn [comp] ;; hook #0 - provides name
       (let [props (sac/get-props comp)]
         (get props :name)))

     (fn [comp] ;; hook #1 - provides data
       (watch bad-global-atom))

     (fn [comp] ;; hook #2 - provides foo
       (let [data (sac/get-hook-value comp 1)]
         (get data :foo)))

     (fn [comp] ;; hook #3 - provides bar
       (let [name (sac/get-hook-value comp 0)]
         (compute-expensive name)))]

    ;; render, not a hook but same structure
    (fn [comp]
      (let [foo (sac/get-hook-value comp 2)
            bar (sac/get-hook-value comp 3)]
        (<< [:div foo bar])))))
```

Each function represents one "compute" step. Each destructured value becomes its own hook and all "dependencies" are assigned as locals in the functions. This makes them simple to call again later when needed. They'll just get what they need themselves, the component just calls the function.

To make things actually composable the functions can return values that implement a simple protocol which will give them access to the component lifecycle. In fact there is one default implementation for this protocol that will be used unless overriden. It is also the simplest hook implementation.

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

Once constructed the `SimpleVal` will just hold on to the actual val and return it in the `hook-value` (which the previous `get-hook-value` call delegates to). Initially the `hook-init!` lifecycle fn is also called but `SimpleVal` doesn't need to do anything. The `SimpleVal` instance will only be created once, whenever any dependencies of that hook were changed the component will run the hook function again and call `hook-deps-update!` with the return value, which will just replace the hook-value and tell the component whether it needs to update others that depended on the hook.

When the component is destroyed it'll also call `hook-destroy!` in case some cleanup needs to be done.

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


A dummy todomvc example can be found [here](https://github.com/thheller/shadow-experiments/blob/master/src/dev/shadow/experiments/grove/test_app/todomvc.cljs), compiled demo [here](https://code.thheller.com/demos/todomvc/).

Note that this is all still extremely experimental. The todomvc stuff uses the maybe beginnings of a framework/library I might build on top of what I described here or previously. 

To be continued ...

## Open Questions

Not so open anymore .. decided to go with multiple props, no state. Makes API nicer to use IMHO.

### Props?

Should Components be limited to one argument map? React forces components to adopt the one "props" object. Technically this isn't necessary for us given that Components should be used as regular functions anyways. They aren't actually functions but they do implement the `IFn` protocol.

```
(defc ui-foo [{:keys [foo bar] :as props}]
  []
  (<< [:div foo bar])))
```

Using this via `(ui-foo {:foo "foo" :bar "bar"})` uses the forced "props" map just because React does it this way. Technically there is nothing stopping it from being

```
(defc ui-foo [foo bar]
  []
  (<< [:div foo bar])))
```

And `(ui-foo "foo" "bar")`. Named arguments are generally better if you have many of them but allowing multiple may make for an easier API. The user can decide but that may make for an inconsistent API if one library decided to use maps only while another splits it into multiple args.


### State?

Should components have inherent managed "state" or should this be done entirely via hooks? I'm currently leaning towards pure hooks since that opens up using multiple "props" from above. Otherwise there needs to be one place where state is "declared"

```
(defc ui-foo [props state]
  []
  ...)

(defc ui-foo
  {:init-state {:foo 1}}
  [props state]
  []
  ...)
```

vs

```
(defc ui-foo [props-a props-b]
  [state (use-state ::key {:foo 1})]
  ...)
```

The above example using atoms already allows using state in a simple way without the component having to implement it but makes hot-reload hard if the atom is created in a hook.

State should be kept in a DB in some way as much as possible but local-state is a useful optimization for highly dynamic UI components that want to store DOM related data where writing that to a DB is overkill since it is pointless without the actual DOM element. (eg. tracking element size for animations).