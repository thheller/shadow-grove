# The Problem Components Solve

This is all `shadow-grove` in mind. Components in other things may have different roles, which may or may not apply.

## The Problem

The promise of React was something like `ui = render(data)`, as in building your UI as a function of your data. I think that was what made it especially popular with the CLJS community, it certainly did for me. It also enabled the ever popular hot-reload REPL workflow, popularized by figwheel. Not something we want to give up.

Too good to be true? Kind of. It removed a bunch of very annoying code, but it also introduced a couple new problems. The most major one I see is "What The Heck Just Happened (WTHJH)".

To get what we need, we cannot just `render` and be done. Re-creating everything for every update is prohibitively expensive in Browsers. Game engines and other "Immediate Mode" platforms can do this, but Browsers were not designed to do this. Sure, we can fall back to WebGL and render everything into a canvas, but that is not something that is reasonable for most Browser things. It also has its own issues, so I'll ignore this entirely and pretend DOM is the only "choice".

Instead, we render and compare (a.k.a. `diff`) to the previous render result, to actually find what needs to update. Basically it becomes `ui = render(data)` then `ui2 = render(data2)` and `changes = what_the_heck_just_happend(ui, ui2)`. In practice that implementation can take many different forms, but what can become the bottleneck is asking WTHJH too many times.

Let's say we use the ever popular hiccup `[:div {:id "foo" :class "foo"} "Hello World"]` and want to update that to `[:div {:id "foo" :class "foo"} "Hello World!"]`. We can see somewhat easy what the actual change was, but the diff mechanism is generic and has to ask WTHJH over and over again. Is the vector still a vector? Is the attr map still a map. Is the value for `:id` still equal? Is the value for `:class` still equal? Are there any new keys in the map? Were some prior ones removed? Is there still only one "child"? Is that child still equal?

It is absolutely true that WTHJH is often extremely cheap to answer, and that CLJS datastructures can often reduce some of its cost. Yet, it is often a "death by a thousand cuts" that ends up killing performance. It is also true that computers are often "fast enough", for this to not matter at all.

***Opinion:** I strongly believe however that wasted work is just that: Waste. I therefore consider it absolutely essential to reduce the amount of times WTHJH has to be asked in the first place. At the very least the Computer will get less hot. Or maybe even safe some battery life. This belief drives pretty much every choice in Grove. In the end some imperative code, doing only what is needed, and not having to ask anything at all, will perform best. So, the goal is to get something that has a good developer experience without too much sacrifice. YMMV.*

Fragments in Grove optimize most of this away for the actual DOM manipulations, since they can tell at compile time what can possibly change and only ask WTHJH for that. Even with that optimization a sufficiently large nested UI tree may still need to ask WTHJH many times over when processing from the Root.

## Enter Components

Components act as a special node in the UI tree. They manage data and events. Often they introduce new data into the tree, which they didn't get from their ancestor. When their data changes, components can kick off their own render/diff cycle, BUT they do this from their own branch in the tree. It does not need to start from the root, which potentially reduces the amount of WTHJH questions significantly. It also works the other way, in cases where a render does start from some ancestor (or the root). The component manages its state, so often it can answer WTHJH with "nothing" and just skip a bunch of work by not "diffing" its whole subtree.

In addition, Components also have a lifecycle that can be useful for UI work. The Component implementation primarily exists to reduce the number of WTHJH questions that need to be asked to figure out what to do.

... to be continued

