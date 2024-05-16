## Write framework using a web-worker?

In a discussion with @jacekschae about web frameworks in general and why Workers aren't used more I just dismissed it outright claiming that serializing data back and forth adds more overall cost than what you'd gain by moving code into a worker. Shortly after I however realized that I didn't test this in a long time and the last time I tested this was using EDN and naively just shipping the entire app-state map in each frame. That of course blew the 16ms budget with even very small app-state maps.

I decided to revisit my assumptions about all of this and starting adapting the "grove" framework to allow moving all "application" code to a worker and only keeping UI "views" in the main thread. That actually turned out to be real easy given that the component code already is designed to abstract away async code as much as possible.

There are now two variants for "todomvc". One using the worker split and one with no split.

- https://code.thheller.com/demos/todomvc/
- https://code.thheller.com/demos/todomvc-split/

Code in `src/dev/todomvc`.

The "views" and "tx" (state ops) code is almost identical. It isn't currently since I need to sort out some framework namespaces and probably introduce a protocol or something. They can be 100% identical.

So whats left is a bit of glue code either starting a worker or not and ensuring that code can be split properly so the worker doesn't end up loading view code (which may contain DOM interop code it can't run).

## API Design

`defc` components currently use `sg/query` to "query" the application state. In the default variant that just accesses the data directly. In the worker variant it instead sends the query (already data) to the worker using transit. The worker then processes the actual query and sends back the data. The query hook "suspended" when it sent the query and can "resume" processing when the data arrives.

```clojure
(defc ui-sample [todo-ident]
  [data
   (sg/query todo-ident
     [::m/todo-text
      ::m/editing?
      ::m/completed?])]

  (<< [:div ... data]))
```

The worker variant obviously makes everything async but from the User API perspective this looks identical. The implementation takes care of async and the `defc` macro makes it look like regular sync code.

It does make scheduling/coordinating all that work a lot harder but that work needs to be done anyways since queries are supposed to be async in non-worker code too (to support fetching remote data). So its really just an implementation detail, the component author shouldn't see it.

The assumption is that everything can go async anyways, so actually doing it to talk to a worker might not be that bad.

### Moving to Worker

Since queries allow us to only ship small chunks of data at a time (and updating that incrementally) the "main" thread never needs the full database. Turns out `transit-cljs` is already quite fast and probably fast enough to not worry about it. So serialization performance is actually not the limiting factor anymore and could be optimized further if needed.

Overall however for a simple app like todomvc using the worker split actually still makes the app slower. I do however believe that larger actual apps could benefit a lot from moving more code to the worker. Enough so that I'll will try to explore this as the default for the framework. Its about time we moved off the main thread anyways.

todomvc is "slower" since startup basically happens twice. Since we need `cljs.core` available in both the main thread and the worker we'll have a `shared.js` containing all shared code. `cljs.core` and `transit-cljs` at the very least. The non-worker code didn't use `transit-cljs` at all so was obviously smaller but real apps likely have that dependency anyways. The `shared.js` however needs to be loaded and eval'd in the main thread and the worker. I think the Browser will be smart enough to actually only send out the request once but it'll still load the code twice. This isn't noticeable on my desktop but running with 6x slowdown (emulating a slow mobile) this is still noticeable. I also can't seem to get the Worker to actually start faster/sooner. It always seems to take a bit of time longer thus causing a bit of an additional delay.

On the desktop none of this is noticeable (at most 100ms difference on start). Making everything async actually performs better when using 6x slowdown since the "blocking" periods are smaller but the startup delay is also longer.

### Work TBD

- The UI code will need a "Suspense" style component so the UI becomes less "jumpy" but even without just looks like a regular UI talking to the network. This will need to exist for other async IO purposes anyways so it isn't worker related.

- Framework re-org so that using a worker becomes opt-in/out and doesn't require any changes to UI code. Currently this requires changing a `ns` `:require` but shouldn't.

- The DB should be separated anyways but there should be ways to "force" that no view code can access DB code directly and has to go through `sg/query`.

- Can't use the `fulcro` patterns either since the Worker code can't access the View Components either. Also creating one big root query is a problem for serialization so instead of passing "data" down we only pass down idents that child components then query for data. Maybe there should be a "shared" schema of sorts.

- Limiting the "event" system. The mutable JS `event` object can never cross over to the worker but the main thread should still be able to do everything it needs. This works already but is a bit ugly.

