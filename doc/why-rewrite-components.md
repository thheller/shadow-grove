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

  ;; optional, cleanup
  (on-destroy [state])

  ;; optional, react useEffect always
  (on-render [state])
  
  ;; optional, only on specific changes?
  (on-render :bar [state old new])
  (on-render #{:foo :bar} [state old new])

  ;; optional, react useLayoutEffect?
  (on-before-paint [state])

  ;; react to changing of arg value
  ;; without this the arg change is just ignored?
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
    (db/query-ident ident)
    state)

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

How does this handle query update though? Maybe a change in state wants to adjust the db query? In the above that gets tricky. So, maybe it needs to take query-id as an arg to identify the query and updatable by reference?

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

## Other problematic things

One thing that bothered my about the current EQL implementation is abusing the `eql/attr` attribute to load data on first access. Sometimes a query cannot be fully answered locally. In case of the shadow-cljs UI I opted to do this via `eql/attr` hacks.

https://github.com/thheller/shadow-cljs/blob/ac8b33e7643d187845b68f6ac4acb4fc3e043949/src/main/shadow/cljs/ui/db/inspect.cljs#L186-L200

```clojure
(defmethod eql/attr :summary [env db {:keys [oid runtime-id summary] :as current} query-part params]
  (cond
    summary
    summary

    (or (not oid) (not runtime-id))
    (throw (ex-info "can only request obj-preview on objects" {:current current}))

    :hack
    (do (relay-ws/cast! env
          {:op :obj-describe
           :to runtime-id
           :oid oid})

        :db/loading)))
```

So, if a components queries `(sg/query-ident obj-ident [:summary])` it'll first get a `:db/loading`, which suspends the component. The read also triggers a websocket message to fetch the summary over the websocket.

This works but is ugly. I never found a proper place for this. I always (and still) thought that the component is not the right place for this, but maybe it is?

The question that needs an answer is: How do you load data on demand when you need it and not before? The component mounting is the ideal "moment", since you are exactly in the place where the data is needed. However loading it at this time also means that you have to show something else while its loading. Suspense helps but is not perfect.

I always thought that the data should handle that in some way. Since the data is driving what is getting rendered, we technically know that this component is going to mount without actually needing to mount it first. However, expressing this in a reasonable way in code is hard.

Doing it in the component at least means you don't need to replicate what it needs elsewhere. Also makes it the correct place to deal with Suspense. Who sets the `Suspense` flag though?

```clojure
(defc ui-component
  (initial-state {:object nil})
  ;; handle arguments that aren't supposed to change
  ;; or rather a change indicates that the component should unmount/mount to start fresh?
  (arg ident {:validate db/ident? :immutable true})

  (on-init [state ident]
    (db/query-ident ident [:summary])
    state)

  ;; very verbose and ugly
  (on :db/query-result! [state {:keys [summary] :as result}]
    (-> state
        (assoc :data result)
        (cond->
          (not summary)
          (-> (load-summary ident)
              (suspend!)))))

  ;; looks cleaner
  (on :db/query-result! [state {:keys [summary] :as result}]
    (when-not summary
      ;; needs to guard against repeating loading
      (load-summary ident)) 
    (assoc state :data result))

  ;; this is not needed, if the load-summary just writes directly to DB
  ;; would just get the :db/query-result again with summary present
  (on :summary-loaded! [state summary]
    (assoc-in state [:data :summary] summary))

  (render {:keys [data]}
    (<< [:div (pr-str data)])))
```

Not sure if this is any better than doing it in `eql/attr` though.

Another thing the EQL abstraction does is couple the component implementation directly to the DB structure. Thinking in a normalized DB is not always fun, but it is necessary to allow components efficient updates later. A parent component might only need a list of idents, and then passing that ident to the child component. The child component can query whatever it needs. If the ident data changes the parent does not need to update, since the ident remains identical.

`re-frame` subscriptions hide db internals from the component. Which in certain aspects is nice, since you can just change the implementation while keeping the "contract" or the shape of data the subscription returns. So, you may not need to touch the component when changing your DB structure. I'm not sure how necessary that really is though.

The "database" is your UI database after all, there is no need for it to match your backend db schema. So, directly coupling components to this schema might actually make things easier. I'm not sure adding this subscription abstraction is needed. It is possible to do with EQL, but reshaping data too much gets expensive quick.

I've never written any real app with `re-frame`, so my judgements are likely off. The point always was that EQL is an option not a requirement. It should be possible to use something similar to the re-frame model.

How would this look if I still don't want any refs and hidden deref side effects?

```clojure
(reg-sub :b-query
  (fn [db _] {:hello "world"}))

(reg-sub :a-query
  (fn [db _] {:hello "world"}))

(defc ui-thing
  (on-init [state]
    (subscribe [:a-query])
    (subscribe [:b-query])
    state)

  (on :a-query [state data]
    (assoc state :a data))
  
  (on :b-query [state data]
    (assoc state :b data))

  (render {:keys [a b]}
    (<< [:div "rendering" a b])))
```

Very similar to EQL. Well, technically there is a hidden runtime ref accessed in subscribe. But no deref's anywhere. `(subscribe state [:a-query])` would mean no runtime binding needed either.

The `subscribe` call could also return data immediately

```clojure
  (on-init [state]
    (-> state
        (assoc :a (subscribe state [:a-query]))
        (assoc :b (subscribe state [:b-query]))))
```

but I feel like that might lead to more duplicated code.

The plan is to make events queue up while inside an event handler. So, say `subscribe` has data immediately available. It would trigger the associated event. We are in `on-init`. The event handler runs as soon as `on-init` completes. It cannot run immediately since then changes to `state` would get lost, because `on-init` doesn't have them. Merging in some magic way sucks.

There could also be an explicit "eager" event drain mechanism, so that we can work with the data immediately?

```clojure
  (on-init [state]
    (subscribe state [:a-query])
    (subscribe state [:b-query])
    (-> state
        (drain-pending-events!)
        (do-stuff-with-a+b-being-available)))
```

Not sure if this is actually necessary but could be useful. I really don't like passing around `state` all over the place from an API perspective, when it is supposed to be data. But adding an extra argument is also noisy. So, maybe have to give into using a runtime binding?

```clojure
  (on-init [this state]
    (subscribe this [:a-query])
    (subscribe this [:b-query])
    state)
```

Not great either.

```clojure
  (on-init [state]
    (subscribe [:a-query])
    (subscribe [:b-query])
    state)
```

Definitely looks best. `subscribe` could also return a handle, so we can reference the query later, or we pass in such a handle via arguments.

```clojure
  (on-init [this state]
    (-> state
        (assoc :a-query-handle (subscribe [:a-query 1]))
        (assoc :b-query-handle (subscribe [:a-query 2]))))
```

```clojure
  (on-init [this state]
    ;; same sub with different args
    (subscribe [:a-query 1] {:query-id :a})
    (subscribe [:a-query 2] {:query-id :b})
    state)

  (on :a [state result])
  (on :b [state result])
```

Probably like this more.

I think if the runtime binding provides some kind of controlled "hook" mechanism we can just about do anything. The only thing implementations need to do is maybe attach some of their own state, an unmount/destroy callback, and a way to dispatch events to the component.


## Push vs Pull?

Should "hooks" push events to the components or should be components pull data from hooks? Hooks could just signal that they have events available they want to execute. The component could then pull those events when it is ready to process them. The current `defc` is pull.

The component should already queue events anyway, but it may not be able to decide if events can be dropped? ie. if two query results arrive before the component even rendered an update. It could drop the first? So, pull seems like the better design. The implementations can always decide if it wants to return all, first, latest or some aggregate? Under normal circumstances this won't matter since we are likely to process events fast "enough". Should there be some kind of back-pressure or is that overkill?

Events ideally also have some kind of priority? Process high priority stuff first, while potentially doing offscreen stuff "later".


## Execution order

One thing `defc` ensured was ordering of execution. Each bind would execute in order, when needed. You could ensure that one block finishes before the next one starts.


```clojure
(defc ui-thing [ident]
  (bind data (sg/query-ident ident))
  (bind result (compute-with data))
  (render ...))
```

The loop thing cannot ensure this, and may become a little messy?

```clojure
(defc ui-thing
  (arg ident)
  (compute :data [state data]
    (assoc state :result (compute-with data)))
  (on-init [state ident]
    (sg/query-ident ident)
    state)
  (render
    ...))
```

It could maybe ensure that `render` is last and `on-init` before any `compute` but the whole thing becomes very "callback" based potentially.

```clojure
(defc ui-thing [ident arg]
  (bind data (sg/query-ident ident))
  (bind a (compute-with data))
  (bind b (compute-else arg))
  (bind c (+ a b))
  (render ...))
```

This order is ensured and the code is concise. It also can only get to `render` after running through all `bind`.

It gets more noisy with the loop thing.

```clojure
(defc ui-thing
  (arg ident)
  (arg arg)
  (on-init [state ident arg]
    (sg/query-ident ident {:query-id :data!})
    (assoc state :ident ident :arg arg))
  (on :data! [state result]
    (assoc state :data result))
  (on-arg-change arg [state old new]
    (assoc state :b (compute-else new)))
  (compute :data [state data]
    (assoc state :a (compute-with data)))
  (compute #{:a :b} [state old {:keys [a b]}]
    (assoc state :c (+ a b)))
  (render {:keys [c]}
    ...))
```

Not too bad, but very verbose compared. It can also get to render before any of the `compute` because they trigger based on `:data` which may come at any point, unless the query implicitly suspends? The hook impl suspends if a query cannot be immediately answer?

Could make things a little more compact by making the macro smarter?

```clojure
(defc ui-thing
  ;; (arg ..) not needed, because inferred from binding names in init?
  ;; initial state created here?
  (on-init [ident arg]
    (sg/query-ident ident {:query-id :data!})
    {:ident ident :arg arg})
  
  ;; non-state maps returned are merged into state?
  (on :data! [state result]
    {:data result})
  (on-arg-change arg [state old new]
    {:b (compute-else new)})
  (compute :data [state data]
    {:a (compute-with data)})
  (compute #{:a :b} [state old {:keys [a b]}]
    {:c (+ a b)})
  
  ;; or flipping how compute works
  ;; in theory the dependencies of a compute can be inferred?
  ;; so instead declare which attr the compute will provide?
  (compute :c [{:keys [a b] :as state} prev-state]
    ;; often won't need prev-state?
    ;; prev-state represents the last return value of this compute?
    ;; or the actual previous state it received? diff calcs are useful sometimes
    ;; results in (assoc state :c result-of-compute)
    (+ a b))

  ;; compute multiple things? 
  (compute [{:keys [a b] :as state} prev-state]
    {:c (+ a b) :d (- a b)})

  (render {:keys [c]}
    ...))
```

The loop things definitely wins as soon as local state is needed though. Since it is threaded through everything, adding/changing/removing it is trivial.

It also wins for changing args/state because the old/new is provided automatically. Possible with `bind` but trickier.

## What if: Keep defc, but rewrite how hooks work?

The main issue I have with hooks is the protocol stuff. What if this is just replaced with runtime binding, which the executing code can talk to. `bind` could create a "register", as in memory storage. Code executing in the associated "block" can "claim" that register and write data to it. On write the component triggers further updates.

```clojure
(defn query-ident [ident]
  (let [register (reg/claim!)
        ;; just to only allow one claim per bind
        ;; can't have two things trying to write a value to it
        
        ;; persistent storage between runs?
        ;; maybe register is just a ref type internally to swap!/reset!?
        query-id (reg/use-state register :query-id #(random-uuid))]

    ;; this runs again whenever the "dependencies" change, so need to check
    ;; if this did run before. register could maintain a counter, so we can easily
    ;; tell the first run?
    (when-not (reg/cleanup-set?)
      (reg/on-cleanup register #(db/remove-query query-id)))

    ;; when the block re-runs replace a potentially existing query
    (db/query-ident query-id
      (fn [data]
        ;; nice to have a specific place to write to, instead of a generic event?
        (reg/write! register data)))))

(defc ui-thing [ident arg]
  (bind data (query-ident ident))
  (render ...))
```

This is still substantially easier to write than the IHook protocol mess.

But how does this handle side effects during read that should only happen once?


```clojure
(defc ui-thing [ident]
  (bind {:keys [summary] :as data}
    (sg/query-ident ident))
  (bind _
    (when-not summary
      (load-summary ident)))
  (render ...))
```

Assuming the `load-summary` ends up eventually writing to the DB. Then the query will invalidate the first register. That'll move `summary` from `nil` to data, the second bind block will re-run, and do nothing because of the `when-not`. Conditionals are problematic with hooks, are they here?

```clojure
(defc ui-thing [ident]
  (bind data
    (sg/query-ident ident))
  (bind _
    (when-not (:summary data)
      (load-summary ident)))
  (render ...))
```

This will also run the second bind whenever `data` changes, which might be often?

```clojure
(defc ui-thing [ident]
  (bind data
    (sg/query-ident ident))
  (hook
    (when-not (:summary data)
      (load-summary ident)))
  (render ...))
```

Could keep the separate thing for something that doesn't need a register?


## What if: Do both?

- Keep `defc` for "def component", with maybe rewritten hooks but otherwise the same.
- Add `defsc` for "def stateful component", which always has managed local state and doesn't need refs?

Let time figure out which one is better?

Problem is that queries must work differently and therefor would need two separate `sg/query` variants which is not great? Most hooks for that matter need two variants? Too many options also may not be a good thing? Leaves users confused on what they should use.