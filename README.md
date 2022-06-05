# HIGHLY EXPERIMENTAL CODE

Only use for actual experiments. This is not production ready.

## What is this about?

I'm exploring building a framework for building "apps" using web technologies (DOM, CSS, etc) with purely CLJS.

## Motivation

The goal here is to build something that works without external JS dependencies (eg. react, react-dom) and that takes full advantage of all features CLJS has to offer. JS libraries/frameworks like react are written within the constraints of what JS developers using these tools can reasonably write by hand. This is already fairly limited and requires compiler support for JSX. All CLJS wrappers for react inherit some of these constraints and have to pay an extra translation cost to convert CLJS data to JS and back.

Other frameworks such as Svelte or Vue lean more heavily on specialized compilers to generate JS. These are not compatible with CLJS in general and that is why react or variants thereof have been pretty much the only adopted JS frameworks in the CLJS space.

In CLJS we can use macros to give us most of the benefits while still neatly staying within CLJS itself, no new toolchains needed.

All of this is built to use the current and well supported "web" features. I have no intent of making variants for use in "native" frameworks such as react+react-native. There is also no intent of trying to re-invent web stuff like rendering into canvas elements (eg. like flutter). It is still possible to do that within the constraints of the framework but the focus is mostly on "boring" web stuff that is usable today. Should any viable alternatives arise (eg. webgl/wasm) I might reconsider in a few years. 
 
I'm happy if this leads to something that is reasonably fast and stable. The UI should be as declarative as possible while still allowing direct DOM access when necessary.

## Status

I'm still calling this **highly experimental** and the APIs may change at any time but I have started porting the shadow-cljs UI over to use this code as the first real-world example application. I already created a few dummy examples (eg. todomvc) but these are too simple and don't really reflect more complex application needs.

Everything only does the bare minimum to make the intended features work. Development support and documentation is non-existant and will stay that way for a while probably.

I'm mostly writing some of this down for my own notes and some people have expressed interest in a purely CLJS framework and might be interested in this. I strongly suggest that you don't use this for anything real at this point, maybe even never. I don't really want to maintain a full framework stack over time but I'm also no longer interested in using react so I might have to.

## Ongoing Experiments

### shadow.arborist

> Arborists generally focus on the health and safety of individual plants and trees.

The underlying API abstraction for DOM interop. This is fairly stable by now but might undergo a few API simplifications and renames. It works on top of a few simple protocols and therefore is pretty easily extensible. It doesn't even have its own component API since that can be built on top of those protocols.

### shadow.grove

> grove - a small wood or forested area (ie. trees)

The "framework" that provides a component API and other extension points which applications may need. This is still changing a lot. When arborist can be compared to react+react-dom this would be something like fulcro or re-frame.

It includes basic support for "Suspense" type "offscreen" rendering to avoid displayiong too many cascading "loading" states. A basic scheduler is supported which will ideally schedule work more effectively in the future when needed (eg. support `requestIdleCallback`, `isInputPending`, etc).

The "framework" also provides a basic EQL query and transaction layer. The intent is to keep all UI related things separate from all data/backend things. Components can get their data in a declarative way without being coupled to where that data is actually coming from.

### shadow.grove.worker

The first provided "data" layer. This experiment of this is to move all data processing to an actual WebWorker and thus moving as much work off the main thread as possible. The "frontend" can query data and the worker can push updates to the frontend whenever needed.

Workers already live in their own isolated world which means it is straightforward to move the data processing elsewhere if needed. Other implementation could move all processing to the server over a websocket for example. This is something I might explore for the shadow-cljs UI in the future. Other platforms such as Electron already have a "main" and "renderer" separation which fits this model nicely. It might also be interesting to use a SharedWorker to let multiple browser tabs you the same data "backend".

It is still an open research question of how practical this actually is. We are replacing the cost of data processing with the cost of a bunch of data serialization back and forth. For simple apps this might actually be more work overall. Given that this is intended to be easily swappable the data processing should be easily movable to the main thread when needed.

So far it looks promising though.

### shadow.grove.db

A very basic db normalizer and query engine. It supports basic EQL queries. I might switch the query processing to Pathom at some point since that already covers so many more very useful features.

The underlying DB is organized as a regular flat Clojure map. Instead of using nested maps for entities (like fulcro) all data is kept in the main map to mimic a simple key/value store API. So instead of `{:my.app/product {1 {:title "foo"} 2 ...}}` we have `{[:my.app/product 1] {:title "foo"} ...}`.

This is done to keep data dependency tracking simple. When a query is executed it is handed a `db` instance which is a "proxy" (see `db/observed`) that delegates to the actual map while also recording which keys were accessed. Tracking multiple levels would be much more complicated but might be added in the future. A query will remember all the keys it accessed.

Transaction processing is handed a similar proxy (see `db/transacted`) that delegates to the actual map but records which keys were added, updated or removed. When a transaction completes the recorded keys are compared with the keys used by the queries the then queued for refresh when needed.

When a query is refreshed it compares with its previous result and only notifies the UI when actually needed. Since all this work is happening in the worker the main thread is not blocked by any of it. As far as the user is concerned it looks like working with a regular clojure map (ie. `assoc`, `update`, `update-in`, `dissoc`).

The transacted proxy also keep track of all entities of a certain type. This is done to avoid having to traverse the entire map to find all entities of a given type. The user will likely maintain such a list anyways so this might be removed at some point.

The data is also configured with a simple schema to enable some normalization helpers. I might add support for data validation based on the schema but that is not yet supported. The db normalizer in fulcro is much more sophisticated but I couldn't figure out how to make it work without using the parts that are coupled to react.

This is all working well but will likely change a bit. I'm not too happy with the current approach for handling async queries that load data when first requested.

It does however look very promising overall. It is never necessary to diff all the data to find out what a transaction did. The keys are always known.

Since query engines are pluggable this could be replaced by an entirely different implementation without touching the UI at all.

### shadow.grove.main.vlist

A virtual list component that can render a large amount of elements efficiently by only actually rendering what is visible on the screen. So for 10000 elements it would only ever render the 50 or so that fit on the screen. The backend keeps all the data and the frontend only receives the chunks needed to display.

The implementation is naive but works fine for now. It currently only supports fixed heights since using variable heights makes the implementation much more complex.

### shadow.grove.main.stream

A stream is intended to be used to optimize some common UI operations where an element is only ever added at the top or bottom and "streaming" in from another event source. A common example would be a chat-type application where the chat-log is rarely re-ordered.

Not sure about this one. Seems to make sense in some areas. Needs more testing.

### shadow.grove.main.atoms

Basic support for regular `cljs.core/Atom` and updating UI when they change. This is pretty much final. 