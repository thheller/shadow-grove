# Why Operators?


**I no longer believe these are a good idea. Basically all they create is mutable objects all over the place. While that may have its uses its certainly not very clojure-ish.**

Everything here is a rough shape and will most certainly change, or be completely removed without replacement. **Beware, while I figure this out.**

This is about ideas that have been bouncing around in my head for many years, with never enough time to actually think about them. Trying to write up all my notes into some kind of coherent story, and actually write some code.

Probably overthinking all of it. The total code so far is less than this document.

## The Problem

Using EQL seemed good at first, but in practice is lacking. It is a nice query language, but not a great foundation for dynamic UI data.

The current solution for data handling in shadow-grove is based around a normalizing DB, that only events can write to in a somewhat transacted manner. Everything can read everything, when they know the key.

What has been bugging me about this is that "nothing" is ever truly in charge of a specific piece of DB data. Many different events can write to a specific place, but they always have to check what might already be available.

The code doing so is also all over the place, and knowing where something came from in the first place can become uncertain. Some event may load something from the backend and create thousands of keys in the DB. Who cleans them up? Who knows what is actively being used/displayed in the UI?

It might be my own discipline, but the code this creates might be functionally "pure" yet very hard to follow. It also necessitates the use of "idents" and normalization, which I have been told are hard concepts to internalize, which I would confirm. They also create somewhat verbose code.

UI Components are definitely not the correct thing to manage shared data. Their lifecycle can be useful, but as soon as two components want to use the same piece of data that breaks.

## The Exploration

So, the thought is: What if the one atom for everything approach is actually bad, and what if state instead was more like Clojure STM, e.g. `ref`. A different reference type, only responsible for its own data. Updates are triggered via actions, but only the thing managing that data can change it. Signals and other "reactive" solutions have state all over the place, so it can't be that bad right?

That is of course nothing like `ref`, since everything can `alter` them, but more on that later. STM-like transactions might still be useful. 

I haven't found any implementation that does exactly what I have in mind right now. I'm sure it exists though. I doubt that I'm the first one to think this way. If this sounds exactly like something you know, please tell me about it. I might be going through a bunch of bad ideas and ultimately arriving at stuff that already exists.

# Enter [Operators](https://matrix.fandom.com/wiki/Operator)

The idea being that they are the thing that operates on a specific piece of state. They have identity and can be shared. They have a lifecycle of their own. To mirror that idea to the shared app-db world, think of an Operator as something managing the `[:products 1]` path in that DB, which is exactly what they may do.

They are sort of an abstraction layer above signals, as any operator may opt to use Signals internally.

They are not like re-frame Subscriptions, since that only has a read function, which just runs again if its dependencies change and is cleared up when no longer referenced. They have identity, but no "life of their own".

React `useReducer` is somewhat close, but also doesn't have a lifecycle and can only "react" to externally dispatched actions, they may not change their own state.

To be honest I don't know what they are. They might be a Frankenstein creation of mixed concepts, without ever actually fully embracing one. So far they seem to be closest to what some might call `actors`, or maybe Clojure `agent`. These imply async for me though, and I want this to be sync by default since we can't do any blocking deref in JS. Any action may return async results though.

# The Goals

I feel like it might be more useful to know what this is for, before knowing how I ended up doing it.

### Goal: Maximum Flexibility

While it is good to have rules, they can often get in the way. Functional Purity is nice, but we are in a completely mutable environment. No need to strife for absolute purity, when nothing else is. It should still look like somewhat idiomatic Clojure code when possible though. The goal is to do what is required, without enforcing too many rules and restrictions on how. Each operator manages its own state and nothing else, let them chose how.

### Goal: Managing UI Data, not UI

Operators should have no knowledge of what the UI actually is. They operate on their data only, not how its displayed. Components wire things together.

Although a nagging thought is that operators might be generic enough to also handle UI rendering at some point.

### Goal: Data Loading

Data needs to come from somewhere. **"Where from?"** isn't really the relevant part. **"When?"** is.

In the UI Context we might want to list some "products" from our shop. First we need that list. Then the user clicks one product to see more details. The code that loaded the list may not have loaded that data yet, because loading that for everything would be overkill. So, we need a way to load things on demand that we only have prior partial knowledge off (e.g. an `id` of something).

So, tying it to a UI component at first seems to make sense. Load it when the component mounts. If we use local state that state will be also be cleaned up. But what if you want to share that piece of state? Passing it down the Component Tree might be difficult when a different branch may want access to it. Say the user puts the viewed product into their "shopping cart", which is very likely on a different branch of the UI tree. It is still the same product, but may no longer care about the details previously loaded.

Also, UI components use UI data, which might be a derived form of some backend data. Not all backend data is immediately useful for UI purposes, some processing may need to be done.

### Goal: Identity

Everything can refer to any Operator, but when accessed with the same parameters they each get the same one. Only one instance is created. Identity being defined by combining the Operator Definition with one additional optional value. Basically that combination represents what `ident` did previously, think `[:product 1]`. An operator definition is relying on exact `identical?` checks, `=` is never checked. So, it makes sense to use regular `defn` or `def` for this, as that also plays nicely with hot-reload.

### Goal: Lifecycle

An Operator is initialized **once** when first referenced, then remains alive while something references it. It may respond to events while alive. Once the last reference is removed it may be "garbage collected". Lifecycle doesn't mean they are always long-lived, a short term reference may be fine.


**Never actually implemented the following.**

Optionally an Operator may want to stay alive for a certain amount of time, even if nothing is referencing it. As sort of cache, it may want to stay around for 5 minutes. A user may revisit the product previously looked at, no need to load everything all over again. Only because the UI temporarily lost interest doesn't mean we should throw everything away immediately

Optionally they may also do kind of suspend/resume? Store their state in localStorage or so, and pick it up again when "revived".

### Goal: Reference Tracking


Sort of required for the above, but I think it could also be useful for dev tooling. Knowing what references what and visualizing that in some way.

### Goal: IDeref/IWatchable

These are a reference type, much like `atom`. An Operator once initialized must always have a value. Others may also `add-watch` to be notified of changes. I want to try this without any "deref tracking" and see how far that'll go. Might make using them a bit noisy.

### Goal: Actions/Events

Things that have a reference to an Operator, may trigger their actions. They may do so synchronously and get the result the handler returned back immediately. They may also "fire-and-forget", if they don't care about an immediate result.

Handlers may return promises, letting others wait for their completion. Async sends should maybe always return a promise, or be truly fire-and-forget without any return value. Maybe 3 methods make sense.

### Goal: Operator Linking

**Ended up using WeakRef, with no actualized linking, since otherwise cleaning up is a mess. Circular relationships will be common, and if things "link" and then don't properly "unlink" themselves things get messy quickly.**

One operator may depend on other operators. An operator cannot be cleaned up while others depend on it. There may need to be a thing like weak references though.

Not yet sure if cycling linking is absolutely required, but seems useful. Easy way to get into endless loops though.

### Goal: DB Linking

**Removed: Can't have the same state living in two places. The extra `add-watch` this required ends up eating all the performance. With 10000 rows in the benchmark this means 10000 watches that execute on every update.**

Operators could act as "lenses/cursors", where the data they manage is linked to a place in a shared "app-db" atom. This could act as a bridge, since I'm not sure Operators are useful for everything and shared app-db also has its perks.

Any changes made to that place in the DB should directly replace the state of the operator and vice versa.

**PROBLEM:** This currently presents a problem when using regular old events, which transact the database. Any change made to by an Operator wants to write to the DB atom, breaking the transaction as the event handlers only have an immutable view of the db that then isn't current anymore. Only a problem when mixing approaches though.

### None-Goal: Hot-Reloadable

Would be desirable to keep the good old hot-reload workflow, and in most ways it does.

In the current implementation reloading a namespace with operators changes their definition, thus the old one no longer share the same identity. This might be a good thing, but may lose some data on reload. DB Linking might help, so that at least some state is retained.

It is way too complex to retain all transient state, so it is not a priority to try. *Anyone that has looked at react-refresh, or any JS hot-reloading mechanisms in general, probably understands.*

Operators only lose state if their definition is reloaded, so moving them to a separate namespace means they maintain their state while working on UI components that use them. This will probably be best practice anyway.

It sort of represents a problem with many deeply interlinked operators, from many different namespaces, but a page reload will fix it regardless. It is a dev-only problem, which may have a solution, but it hurts my head to think about currently.

### Nice To Have: Navigable

(apparently that is a word?)

The whole idea of normalizing the DB was that data stays mostly flat. EQL then could recognize idents and follow them, to resolve attributes from other entities. A Product might have a Manufacturer and the UI might want to display both names. Would be nice to be able to navigate from the Product to the Manufacturer in some pre-defined way. Much like Om Cursors could "derive" from each other.

### Nice To Have: Remote

This is still a bit vague, but I kinda think the implementation could work as a remote mechanism using message passing. Link over Websocket to a server, or `postMessage` to/from a Worker.

### Uncertain: Transactional

Not having used this much in practice I don't know how relevant this really is. The idea being that on Operator action may trigger actions in other operators. State changes might need to roll back if something goes wrong. Might be useful if things apply all at once, so the user doesn't see intermediate changes. Not yet sure how to make async transactions work though.

### Uncertain: State Machine

Something akin to [gen_statem](https://www.erlang.org/doc/man/gen_statem) seems useful, although the developer could do that within the bounds of the operator. Not sure if it needs to be baked into the operator itself.


## The Implementation

This is very much uncertain, just documenting what I have now and why it is this way.

Operators are defined by their init function. Once an Operator is created this function will initialize it. The return value of this function is ignored, so everything done basically just mutates the operator.

```clojure
(defn &foo [op val]
  ...)
```

One open question I'm currently undecided on is whether a map might be more useful.

```clojure
(def &foo
  {:init (fn [op val])
   :something "else"})
```

I think it might be, so we can maybe assign spec/schemas or add some other identifying data. A lone function is somewhat opaque.

#### Aside: On Naming

Naming stuff is hard, but I think it would be useful for some kind of convention on how operators are named. There will be commonly three things you'll be using when it comes to operators: the definition, the instance, their current value.

```clojure
(defn &foo [op val] ...)
(def foo-op (op/get-or-create ... &foo 1))
(def foo @foo-op)
```

I kinda like the `&` prefix, for operator definitions currently. They are of course just normal vars, with no special meaning whatsoever as far as CLJS is concerned.


### Init Patterns

The init function should mutate the given operator, and things created in it can do that over all its life.

#### Operator Data

Operators themselves implement `ISwap/IReset`, so you may modify their "public" data via `swap!` and `reset!` just like any atom.

```clojure
(defn &foo [op val]
  (reset! op {:hello "world"})
  ;; or
  (swap! op assoc :foo "bar"))
```

When others `@foo-op` they get whatever the current value is.

#### Getting Others

Commonly operators may need to reference data from others.

```clojure
(defn &bar [op val]
  ...)

(defn &foo [op val]
  (let [bar-op (op/get-other op &bar)
        bar @bar-op]
    
    ;; deref at any point, or watch
    
    (op/watch bar-op
      (fn [oval nval]
        ))
    ))
```

#### Actions/Events

Others may trigger actions in the operator, such as `(foo-op :some-action :with "arguments")`. A callback to decide what to do can be added via `op/handle`.

```clojure
(defn &foo [op val]
  (op/handle op :some-action
    (fn [a b]
      ;; a :with :b "arguments"
      )))
```

The caller gets whatever the callback returns.

#### Local State

Sometimes more internal state may be useful, that shouldn't be part of the exposed value. Since operators only initialize once, we can just create a local `atom` for this with just `let`.

```clojure
(defn &foo [op val]
  (let [state-ref (atom {})]
    ;; work with atom as usual
    ))
```

#### "Attributes/Flags"

*I'm uncertain on this, but it seems useful.*

Operators may set attributes on themselves, which others can only read. This could be used for all sorts of things, since sometimes things may need to know additional things about an operator. It might even be better to only use this and remove the implicit "state" management.

These are by design not watchable and there is no way to be notified by a change to these. If something is needed that can be watched by others it could `op/set-attr` and `atom` and modify that.

Could be used as an indicator that the operator is maybe still loading some data.

```clojure
(defn &product [op id]
  ;; a util method could handle the :loading? of course
  (op/set-attr op :loading? true)
  (js-await [data (fetch-from-server {:product id})]
    (op/set-attr op :loading? false)
    (reset! op data)
    ))
```

Attributes can be accessed via `(:loading? that-operator)`.

Could be used to expose other non-data things, that shouldn't be mixed with the data, such as links.

```clojure
(defn &foo [op id]
  (let [bar (op/get-other op &bar)]
    (op/set-attr op :bar bar))
  ...)
```

Which sort of makes things "navigable", by exposing the internal relationships between operators. Saves others having a re-establish those themselves. `(:bar op)` may be better than `(op/get-or-create ... &bar)` (again).

Might also be useful for debugging purposes, describing the internal state of something for humans. With an additional helper that could be removable in `:advanced`.

```clojure
(op/set-attr op :debug-label (str "Product:" id))
(op/set-attr op :debug-progress (str "Currently loading product: " id))
```

Not sure how useful this is, until some actual dev tooling exists.

### Why So Mutable?

A different possible approach is having the init functions return a map which is then later referenced for various purposes. So, instead of `(op/handle op :foo! ...)` you'd return `{:foo! ...}` as part of the map, `db-link` becomes `:db-link ...` in that map and so on. Where some known keys have a predefined meaning, handled elsewhere. That might still be the way to go, but given that maps are immutable there would be no way to ever change it after `init`. Adding something that would allow that effectively makes things mutable again.

So, it seemed better to embrace mutability. At least in theory it is contained and not leaking outside.