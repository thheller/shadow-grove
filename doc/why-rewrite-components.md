# What is wrong is defc?

After using grove for a while now I'm questioning some design choices. Sort of a brain dump before I write actual code.

```clojure
(defc ui-thing [a b]
  (bind c (magic-thing b))
  (bind d (+ a b))
  (render
    ...))
```

The design goal of the "first" `defc` macro was gaining somewhat managed incremental updates, in the face of potentially changing arguments and more importantly having a place to "hook" into the component lifecycle, so that external things (eg. db queries) can feed data into the "tree" where it is needed.

So, in the above example `(magic-thing b)` is called once on mount, then only again if `b`, an argument passed from the parent, changes. Changing defined as `not=` when the component "updates".

On the surface this seems easy enough and straightforward. However, the "hook" part is far too complicated for its own good. The implementation checks if the value returned by `(magic-thing b)` implements the `shadow.grove.protocols/IHook` protocol. If so it'll do the "upgrade" process and give the implementation access to some component internals. It may now call specific lifecycle functions to trigger a component update to re-render. When doing so all additional `bind` steps that depended on the `bind` result will also re-run, leading to an eventual updated render.

It works, but seems far more complicated than it needs to be in hindsight. This design is different from something like react hooks in that it doesn't design on any magic runtime `binding` that running code can access. Therefor it avoids any of the complication resulting from that. You can run whatever piece of code here and only the return value is important. The compiler figures out which previous bindings or args may affect this, and minimizes re-runs.

```clojure
(def a-query (sg/query [:some :attrs]))

(defc ui-thing []
  (bind data a-query))
  (render ...)
```

This is absolutely fine, and you can re-use `a-query` in any component. However, this seems like it would be the same query instance shared, but due to the upgrade process this creates a new separate instance in each component.

What is also confusing is that `data` doesn't end up being `a-query`. So, this behind the scenes switching of the value is sort of counterintuitive. Of course, it is necessary for all this incremental computation to make sense.

Given that this is built on the return value there can only ever be one per `bind`. This is fine but hurts composition is a bit complicated.


## Comparing React Hooks

What react hooks get absolutely right is the composition aspects. It is very easy to build very complicated `useSomething` hooks by composing together the primitive `useState`, `useMemo`, etc.

Where it fails completely is that all of these need to always run. In the react design every re-render of a function runs the whole thing again from top to bottom. This leads to either doing too much work or too little. It all depends on how much `useMemo` and how well you are keeping track of the "deps".

react seems to plan to address this via custom compiler codename [react forget](https://www.youtube.com/watch?v=lGEMwh32soc). Don't know what the state of this is, but we'd have a much easier time doing the same given that we have macros.

## Signals?

Signals are the hype again, and it feels like the JS world just re-discovered ref types, i.e. clojure `atom`, `ratom`, or other JS stuff predating that. shadow-grove has support for atoms, but they are a bit clunky to use.

```clojure
(defc ui-thing [arg]
  (bind state-ref (atom {:hello "world"}))
  (bind state (sg/watch state-ref))
  (render
    (<< [:div (:hello state)])))
```

Since `bind` blocks are only executed once this creates the `state-ref` once. The watch then ensures that the component updates if the ref value changes. Beyond that it is just a regular CLJS `atom`, that you may `swap!` or `reset!` from anywhere.

I designed it this way to avoid having an implicit side-effect when `deref`'ing an atom. Signals and `ratom` look at a runtime `binding` when you access them, and if found make that runtime binding "watch/subscribe" to that atom/signal. Given the "re-render runs everything" react style this has to check on every render again, which is very cheap but leads to maybe "unexpected" behavior.

Then there is the problem of re-running the `atom` creation. In grove this will fail, but there are cases where you'd actually want to allow something like this following:


```clojure
(defc ui-counter [initial-count]
  (bind state-ref (atom initial-count))
  (bind state (sg/watch state-ref))
  (event ::inc! [_ _ _]
    (swap! state-ref inc))
  (render
    (<< [:div {:on-click ::inc!} "count: " state])))
```

What should this do if the supplied `arg` from the parent changes? What if there were clicks and the local state is at `5` but `1` was passed in? Dumb example using a number here, but maybe you want to merge part of a map or so. We instead of `atom` there could be something that also takes a merge function to handle this case, but I'd rather not.

SolidJS and similar things don't have that problem because the component function only runs once, but it then has to "react" to any read/write of the atom/signal.

## Go More Reactive?

On the surface I don't want a reactive system, since it breaks the "props flow down, events flow up" model which made react promising to begin with. Hooks already sort of break that, but you definitely need ways to inject data into the flow from elsewhere. Flowing everything down from the root is cumbersome and very inefficient.

We don't need the "performance" aspects of signals when it comes to DOM construction since the fragment macro is already very efficient.

I also don't like that you need to remember whether something is a value or a ref to a value all over the place.

Another Problem with atom/signals is that they break the hot-reload flow. If all state is in the app db this is not problem, but local state is lost when the component is redefined and consequently unmounted/remounted on reload. Maintaining local state between hot-reloads is possible, but how do you merge actual changes to the component structure? The presumption is that this structure is going to change a lot when working on them. So, the merge problem is the norm rather than the exception.

Of course there is the `re-frame` model which is proven to be effective. Maybe adapt that and sprinkle a little normalized DB into it. The only aspect I don't like is this `@/deref` side effect and runtime bindings, but that's not all bad. Certainly makes the API a bit cleaner on the surface, since the user has to pass around one thing less.


## Other Issues

`env` was meant to be the thing that gave you access to everything from way up in the root. It is a write-at-mount-only `context`, which hooks could directly access.

What bothers me a bit is the constant repetition of the  `env` variable in many location, when it is rarely used. The signature is always `(event ::that-kw! [env ev e])` and you only need `env` so you can call `run-tx` or `dispatch-up`. I think this would be better solved by just letting `event` return a potentially modified map to indicate that this event should continue up. There also shouldn't be `run-tx` and `dispatch-up`. Only one of them.

Maybe `env` is better as a runtime binding to reduce "noise" in the DX?


# What if: Loop as the design basis?

In essence each component represents a loop. It starts with some arguments, waits for events or new arguments. Each event may advance its internal state, and each loop cycle ends with rendering this new state. Eventually it unmounts.

Maybe the entire approach of trying to make this look like a regular function call is causing most of the problems? What if instead we express a loop, where a changing argument is expressed as its own event?

```clojure
(deflc ui-counter
  ;; define component initial state
  (initial-state {:foo 1})
  
  ;; optionally declare arg types with validation or default value?
  (arg bar {:validate string? :default "bar"})
  
  ;; modify initial state on mount based on args
  (on-init [state bar]
     (assoc state :bar bar))

  ;; cleanup?
  (on-destroy [state])

  ;; react useEffect always
  (on-render [state])
  
  ;; only on specific changes?
  (on-render :bar [state old new])
  (on-render #{:foo :bar} [state old new])

  ;; react useLayoutEffect?
  (on-before-paint [state])

  ;; react to changing of arg value
  (on-arg-change bar [state old new]
    (assoc state :bar new))

  ;; ev = event data
  ;; e = dom event, optional, sometimes needed to get stuff out of dom event
  (on ::inc! [state ev e]
    (update state :foo inc))

  ;; (render state ...)
  (render {:keys [foo bar]}
    (<< [:div {:on-click ::inc!} "count: " foo " " bar])))
```

Looks simple enough, but how does it look with more complicated things.

```clojure
(defc ui-component
  (initial-state {:data nil})
  ;; handle arguments that aren't supposed to change
  ;; or rather a change indicates that the component should unmount/mount to start fresh?
  (arg ident {:validate db/ident? :immutable true})

  (on-init [state ident]
    ;; reads runtime binding, to hook into component lifecycle
    ;; and dispatches data event initially and when changed?
    ;; or maybe just takes state arg which carries some special key/metadata
    ;; which reference back to component?
    (db/query-ident ident))

  (on :db/query-result! [state result]
    ;; can compare previous :data if needed?
    (assoc state :data result))

  (render {:keys [data]}
    (<< [:div (pr-str data)])))
```

Not actually complicated. Given the `(on :some-kw! ...` structure is very generic, we can react to any event we want.

`state` mirrors local state just fine. hot-reload could maybe maintain the state between hot-reloads as long as the initial state remains the same? Or just always and if you want a reset you reload the page?

No ref anywhere, so `render` only receives data and only passes data down.
But what does `db/query-ident` actually do? It needs a reference to the component. I think it also needs some way to store something in state, so it can reference itself later? This could be `state` or some internal hidden component thing, reserved for hooks?

```clojure
(defn query-ident [ident]
  (let [query-id (random-uuid) ;; only runs once?
        component (comp/get-current!) ;; from binding, throws when called incorrectly
        db (:db (get-in component [:env :db]))]

    (comp/on-destroy component
      #(db/unsubscribe db query-id))
    
    (db/subscribe db
      query-id ident
      (fn [data]
        (comp/dispatch! component :db/query-result! data)
        )))) 
```

This way it has no meaningful return value, could also have a return value in case things are available immediately.

```clojure
(defn query-ident [state ident]
  (let [query-id (random-uuid)
        component (comp/get-from-state state)
        db (:db (get-in component [:env :db]))]

    ;; would need ot pass some kind of key, so we can also remove on-destroy hooks
    ;; eg. when the query is changed?
    (comp/on-destroy component query-id
      #(db/unsubscribe db query-id))
    
    (db/subscribe db
      query-id ident
      (fn [data]
        (comp/dispatch! component :db/query-result! data)
        )))
  
  state) 
```

This would also work and not require runtime binding. Implementation can decide which path they want to take. I don't think a `queue-fx` style separating side effects is necessary. Might get annoying to always having to pass around `state` and it immediately becoming "stale". So, might be best to stick with binding? It could always read the latest state from the component if needed.

How does this handle query update though? Maybe a changing in state wants to adjust the db query? In the above that gets tricky. So, maybe it needs to take query-id as an arg to identify the query and updatable by reference?

```clojure
(on-init [state ident]
  (db/query-ident :my-query ident '[(:some-attr {:x 1})])
  (assoc state :ident ident))
  
(on ::some-event! [state ev]
  ;; update existing
  (db/adjust-query :my-query :some-attr {:x (:x ev)})
  ;; no longer care about updates?
  (db/remove-query :my-query)
  ;; or just replace it
  (db/query-ident :my-query (:ident state) '[(:some-attr {:x 1})]))
```

## memo?

How does this handle memoization of computed stuff? Sometimes computation is dependent on multiple things in `state`, and remembering to re-compute this everywhere this may change becomes annoying fast. Definitely need generic way for computed things. Can express this without cascading ref watches.

```clojure
(defc ui-component
  (initial-state {:data nil})
  ;; handle arguments that aren't supposed to change
  ;; or rather a change indicates that the component should unmount/mount to start fresh?
  (arg ident {:validate db/ident? :immutable true})

  (on-init [state ident]
    ;; reads runtime binding, to hook into component lifecycle
    ;; and dispatches data event initially and when changed?
    ;; or maybe just takes state arg which carries some special key/metadata
    ;; which reference back to component?
    (db/query-ident ident)
    
    ;; must always return state? could be optional if no change is desired
    state)

  (on :db/query-result! [state result]
    ;; can compare previous :data if needed?
    (assoc state :data result))

  ;; whenever :data changes compute something
  ;; can put anything into state, it might want to preserve between computations?
  ;; can error out if it tries to change :data to avoid triggering itself
  (compute :data [state old new]
    (assoc state :computed (expensive-calc state new)))

  ;; maybe want to observe multiple keys?
  ;; old becomes {:a old-a :b old-b}
  ;; new becomes {:a new-a :b new-b}
  ;; changed? could be a set to indicate what actually changed #{:a}
  ;; maybe saves having to figure that out in code?
  (compute #{:a :b} [state old new changed?])

  (render {:keys [data]}
    (<< [:div (pr-str data)])))
```

Could even force this computation early if needed, just `(-> state (assoc :data 1) (comp/force-compute!) (use-computed-things))`.

The DB needs something like this too still.

## Further Thoughts?

In some sense this is just `react` `useReducer` disguised as a component? Is that a bad thing? Should it be factored out and be separate from components altogether?

Should component arguments be positional or maps?

```clojure
(ui-component ident)
;; looks slightly nicer than forcing a map
(ui-component {:ident ident})
;; forcing this map also makes things slower overall since this has to be constructed each render
```

The `state` could re-use the `db/transacted` implementation, to easily track what changed. Also, would guard against people trying to do stuff they shouldn't. It is just data though, so what could they potentially do? render could be passed `db/observed` to easily track what was used in render, and potentially skip renders completely?

What about dom refs? Would be nice to keep mutable DOM nodes out of `state`? But it also might be useful to re-use existing facilities to handle its management.

```clojure
(render
  (<< [:div {:dom/ref :container} "hello world"]))
```

assigns `:container` in `state`?

```clojure
;; can do stuff on change?
(compute :container [state old new])
;; anything can just destructure it?
(on :some-event! [{:keys [container]} ev])
```

Maybe this could incorporate a state machine, something like erlang `gen_fsm/gen_statem`? I think that is sufficiently possible by just having your own `:state` attribute in the managed state?

Good thing that all of this can exist side by side with existing `defc`. No need to change any existing implementations or protocols.


