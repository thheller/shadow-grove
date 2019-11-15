# shadow-arborist

> Arborists generally focus on the health and safety of individual ... trees

https://en.wikipedia.org/wiki/Arborist

`shadow-arborist` is an experimental/research library exploring what a world without `react` would look like in CLJS. **This is not something you should build upon (yet).**

## Motivation

The goal is to have a pure CLJS implementation for managing complex DOM trees and updating them in response to changing application state. `react` is widely adopted in the ClojureScript community and it is working reasonably well. There are however certain performance implications and more fundamentally we will always be constrained by what `react` offers us since `react` itself is not extensible.

`react` first popularized the "Virtual DOM" where each actual DOM node is first represented by a virtual pure JS object representation that will be diffed to find the minimal set of changes necessary to update the actual DOM.

Representing and diffing every single DOM node as a virtual node however isn't necessary if we can infer which nodes can actually change by doing a simple analysis of the code. The [svelte](https://svelte.dev/) framework wrote a completely custom compiler for this but in CLJS we can do this via macro.

## Architecture

My current mental model does not go quite as far as `svelte` did and does keep some "virtual" representation of nodes. Each virtual Node however can represent one or more actual DOM nodes.

Each "virtual" node can be turned into a "managed" node via a simple protocol implementation. The managed instances than becomes responsible for managing the actual DOM nodes and can take further "virtual" representations of itself to perform incremental updates.

The basis for all this are a couple simple protocols:

```clojure
(defprotocol IConstruct
  (as-managed [this env]))

(defprotocol IManageNodes
  (dom-first [this])
  (dom-insert [this parent anchor]))

(defprotocol IUpdatable
  (supports? [this next])
  (dom-sync! [this next]))

(defprotocol IDestructible
  (destroy! [this]))
```

While these aren't final and will most likely change in some fashion they suffice to build a simple foundation that is easy to extend.

While there will be many different implementations of these protocols we can start with its simplest implementation as an example first.

The first "virtual" node will be a simple String: `"foo"`. The implementation for numbers and `nil` is actually the same so I'll just include them in this example.

First the part that creates the "managed" version.

```clojure
;; see src/main/shadow/arborist/common.cljs

(defn managed-text [env val]
  (ManagedText. env val (js/document.createTextNode (str val))))

(extend-protocol p/IConstruct
  string
  (as-managed [this env]
    (managed-text env this))

  number
  (as-managed [this env]
    (managed-text env this))

  ;; as a placeholder for (when condition (<< [:deep [:tree]]))
  nil
  (as-managed [this env]
    (managed-text env this)))
```

`env` is actually a CLJS persistent map which is similar to `Context` in `react`. It will just be passed down from the root to avoid relying on outside global state. This however is truly immutable and cannot be changed once the node has been created. Will cover this in more detail later ...

So `"foo"` will be turned into a `ManagedText` instance which itself holds onto the `env` and the initial "virtual" node that create it. It also creates an actual DOM `#text` node directly which it is then responsible for.

The creation of the node and adding it to the actual DOM tree is done in separate steps which will be invoked by the runtime at separate times.

We can test this simply via a browser REPL (eg. `npx shadow-cljs browser-repl`).

```clojure
(require '[shadow.arborist.protocols :as p])
(require '[shadow.arborist :as sa])

;; not too important now
(def env {})

;; turn "foo" into a ManagedText instance
(def node (p/as-managed "foo" env))

;; insert it into the actual dom
(p/dom-insert node js/document.body nil)
```

You can verify that the Browser REPL page now actually shows the "foo" text.

The `dom-insert` method mirrors the DOM `parent.insertBefore(node, anchor)` [method](https://developer.mozilla.org/en-US/docs/Web/API/Node/insertBefore). With a `nil` `anchor` this will append as the last element instead. So effectively the `node` was just appended to the DOM. This is done via a protocol since the "managed" instance may want to add many nodes instead of just one and `.insertBefore` allows doing that.

To simulate an update we first check if the managed node actually supports the new "virtual" node.

```clojure
(p/supports? node "bar") ;; => true
```

Then to apply the actual update
```clojure
(p/dom-sync! node "bar")
```

The actual document is now updated and `"foo"` was replaced by `"bar"`.

If the `supports?` check fails the engine will instead create a new "managed" instance of the "virtual" node and then replace the old one in the actual DOM.

```clojure
(require '[shadow.arborist.interpreted]) ;; basic hiccup support (actually optional)

(def new-vnode [:h1 "hello world"])

(p/supports? node new-vnode) ;; => false

(def new-node (p/as-managed new-vnode env))
```

The `new-node` now exist but is not in the actual DOM yet. To get it into the correct position we need the `dom-first` method which gives us access to the first node the "managed" node is responsible for to serve as the `anchor` we need to insert. In this case it'll be the `#text` node we created earlier.

```clojure
(def anchor (p/dom-first node))

;; we need the parent node which we easily get via .-parentNode
;; actual implementations will often know the parentNode but this suffices
(p/dom-insert new-node (.-parentNode anchor) anchor)
```

When looking at the browser both `new-node` and `node` are now actually in the DOM. To finish the actual replace we still need to remove the old version from the actual DOM which is covered by the `destroy!` protocol.

```clojure
(p/destroy! node)
```

`destroy!` kind of implies that actually something desctructive will happen but actually the `node` will just remove all the nodes it is responsible for from the DOM. We could hold onto the managed instance and insert it into the DOM later or elsewhere. Most commonly we'll just forget about it and let it be garbage collected.

We now have created one node and replaced it with another with only a very few basic ops. The actual "complicated" parts are nicely covered by separate protocol implementation that the basic algorithm doesn't need to know about.

The `PersistentVector` implementation will actually only support updating nodes of the same time since we cannot turn an actual DOM `H1` node into a `DIV`. The replace logic will take care of this nicely though.

```clojure
(p/supports? new-node [:div "foo?"]) ;; => false
```

With the basic "interpreted" hiccup-style vectors however we still are where we currently are with `react`. It is implemented in pure CLJS but we didn't gain anything new just yet. The whole benefit of this will only become clearer once we explore other implementations of the basic protocols.

## The "Fragment" Macro

In DOM (or `react`) terms a fragment is a collection of zero or more DOM nodes. `react` supports this in JSX via the `<>` element and in Reagent this would be the `:<>` special keyword. I opted to use the `<<` symbol instead but the argument could be made to keep `<>`.

```clojure
(require '[shadow.arborist :as sa :refer (<<)])

;; just a basic example of how the update logic looks in actual code
;; the library actually covers this, just for explanation purposes
(defn update-dom! [env old next]
  (if (p/supports? old next)
    (do (p/dom-sync! old next)
        old)
    (let [new (p/as-managed next env)
          anchor (p/dom-first old)]
      (p/dom-insert new (.-parentNode anchor) anchor)
      (p/destroy! old)
      new)))

(def new-vnode (<< [:div.foo [:div.bar [:h1 "hello fragment"]]]))

;; update the dom with a variety of elements
(def node (update-dom! env new-node new-vnode))
(def node (update-dom! env node [:h1 "foo"]))
(def node (update-dom! env node new-vnode))
```

So what is so different about the "fragment" macro? During macro expansion it can analyze the code and it will create 2 functions. One that will create the actual DOM nodes and one that will update them. In the example above all nodes are static so the update will just be a noop.

So lets make things a bit more interesting:

```clojure
(defn render [{:keys [title body]}]
  (<< [:div.card
        [:div.card-title title]
        [:div.card-body body]
        [:div.card-actions
          [:button "ok"]]]))

(def node (update-dom! env node (render {:title "hello" :body "world"})))
(def node (update-dom! env node (render {:title "foo" :body "bar"})))
```

Since a `symbol` is not a constant value the macro can actually extract those parts and the `render` fn will actually return a "virtual" fragment node which will contain a reference to the code for the actual fragment and a simple JS array holding all the state variables that can actually change. Pseudo-ish code looks something like

```clojure
(defn render [{:keys [title body]}]
   (FragmentNode. (array title body) create-fn update-fn))
```

The actual `create-fn` implementation might look a bit verbose but it just the minimal amount of steps to create all the actual DOM nodes. I assure you that after `:advanced` optmizations this will match hand-written manual DOM construction code.

```clojure
(fn [env23497 vals23498]
  (let [el0_div (sf/create-element env23497 :div)
        el1_div (sf/create-element env23497 :div)
        d2 (sf/create-managed env23497 (aget vals23498 0))
        el3_div (sf/create-element env23497 :div)
        d4 (sf/create-managed env23497 (aget vals23498 1))
        el5_div (sf/create-element env23497 :div)
        el6_button (sf/create-element env23497 :button)
        G__23494 (sf/create-text env23497 "ok")]
    (sf/set-attr env23497 el0_div :class nil "card")
    (sf/append-child el0_div el1_div)
    (sf/set-attr env23497 el1_div :class nil "card-title")
    (sf/append-managed el1_div d2)
    (sf/append-child el0_div el3_div)
    (sf/set-attr env23497 el3_div :class nil "card-body")
    (sf/append-managed el3_div d4)
    (sf/append-child el0_div el5_div)
    (sf/set-attr env23497 el5_div :class nil "card-actions")
    (sf/append-child el5_div el6_button)
    (sf/append-child el6_button G__23494)
    (array
      (array el0_div)
      (array el0_div el1_div d2 el3_div d4 el5_div el6_button))))
```

The `create-fn` takes two arguments: The `env` since it needs to be passed down to all other managed nodes and the `vals` array which contains `[title body]`. Since the same fragment will always produce the same array in the same order we'll just access the values by index. This could be a `PersistentVector` but since we are not never going to modify it using an `array` is fine and faster.

The function will return 2 arrays. One for the actual root nodes that we'll need to add to the DOM later and one with all the nodes we are managing. This is an implementation detail and will most likely change.

The `update-fn` will contain the minimal amount of steps required to get the data into the actual DOM. In this case something like

```clojure
(fn [env roots nodes oldv newv]
  (sf/update-managed env roots nodes 2 (aget oldv 0) (aget newv 0))
  (sf/update-managed env roots nodes 4 (aget oldv 1) (aget newv 1)))
```

It is purely for side-effects so the return value can be ignored. The arguments passed in will be

- `env` as usual
- `roots` the root nodes array created in `create-fn`
- `nodes` the nodes array in `create-fn`
- `oldv` the old values array, from the old virtual node
- `newv` the new values array, from the new virtual node

The implementation will first check if we are updating the `identical?` fragment and if not trigger the `replace` logic.

In this case the implementation will update the actual live node at `nodes[2]` (and `4`) an update/replace it similar to the `update-dom!` logic. It will mutate the live DOM nodes and update the mutable array in place. No point in using persistent datastructures here since DOM is mutable anways.

The implementation is likely to change a bit but it matches minimal code you would be writing by hand otherwise. We did not "diff" any of the `:div.*` elements since we know they can't change. So in all likelyhood we only update the `title` and `body` text assuming they changed. If not all we did is a few array lookups and compare two strings.

I didn't do any real benchmarks yet but this should outperform `react` by quite a bit in cases where one fragment manages several nodes. Just look at some `svelte` benchmarks if you have any doubts. We'll be a bit slower than `svelte` probably but not by too much.

The macro implementation is really basic and I wrote it in a weekend. We could probably do some very fun and sophisticated stuff there but the basic proof of concept already works quite nicely.

## Dealing with Collections

Often we'll have collections of things that we'll want to display. In `react` there is only-one generic implementation for this is it is handled by having an array of React Element instances where each should have a `.key` property. This key property is used by the implementation to figure out if entries need to be re-ordered. Without a `.key` property the implementation will just render "over" the the elements which may result in many more DOM ops so it can be a lot less efficient than just re-ordering a few nodes.

What always felt odd to me in `react` is that the User is responsible for setting the `.key` property on those React Element which will require iterating the collection which the implementation will then iterate over again.

In `shadow-arborist` the control is flipped and instead the library will handle the `.key` extraction. All we need is the collection, a `key-fn` and a `render-fn` which will be applied to each item.


```clojure
;; simpler interop than update-dom! from above
(def root (sa/dom-root (js/document.getElementById "app") env))

(defn render [items]
  (<< [:div "items:" (count items)]
      [:ul
       (sa/render-seq items identity
         (fn [item]
           (<< [:li "foo" item])))]))

(p/update! root (render [1 2 3 4 5]))
(p/update! root (render [3 5 1]))
```

The above example uses `identity` as the `key-fn` since the item is just a simple number and serves find as the key. In actual apps most commonly this would be a keyword and the items would be a sequential collection of maps. (eg. `[{:id 1 :text "foo"} ...]` with `:id` as `key-fn`).

The current implementation is just a function call but that could be enhanced by a macro for some syntax sugar.

The proof of concept implementation is quite simple and works well enough for demo purposes. It could be a bit more efficient and handle perserving active focused elements. Since this is built on the same simple protocols from above we could easily implement other implementations which are optimized for different use-cases. (eg. Drag-n-Drop sorted collections, append-only chat style). The implementations could also easily handle animations for moving items since they are much closer to the DOM and don't have to rebuild everything in the VDOM.


## Unshackled from React

At this point we can do most of the things that `react` (and `react-dom`) actually do for us. The whole React "Fiber" architecture can be replicated using these simple protocols. This is the an area of active exploration and I haven't settled on anything yet. It works and is probably fast enough but I want to explore more.

There are a lot of interesting topics to consider and ultimately the ideas behind React Fiber still apply even if the whole DOM aspect is faster. At some point we'll still want to skip some updates or delay them so higher priority updates can happen first. [Dan Abramov](https://twitter.com/dan_abramov) (React core dev) [tweeted](https://twitter.com/dan_abramov/status/1120971795425832961) a nice summary of why that architecture makes sense. This was somewhat in response to the [talk](https://www.youtube.com/watch?v=AdNJ3fydeao) given by [Rich Harris](https://twitter.com/rich_harris) (svelte author). I think the best approach is actually somewhere in the middle.

`svelte` has some fantastic ideas like built-in transitions and `react` has some very nice ideas about more advanced scheduling (eg. delaying DOM updates to process user input faster, delaying low-priority offscreen DOM updates, etc).

The goal of implementing all of this in CLJS is to make choices that make the most sense for CLJS. Instead of trying to make everything fit into the JS model of `react` we can save a whole bunch of work even if we just skip the translation steps.

## Things not mentioned yet ...

There are a lot of other topics I didn't cover yet and will add later. 

- Components
- Server-side Rendering using Clojure
- `svelte` like transitions and other DOM stuff

## TBD

Needless to say there is a lot of work to be done. I'm exploring this in my limited spare time so don't expect anything usable out of this anytime soon. I'm always looking for feedback and maybe you have some ideas I didn't think of.

Please note that all of this is an experiment. The conclusion in the end may be to just use `react` but I so far I like the prospect of not having to. Since all of this is predicated on simple protocols we can also probably just swap out the macro an have something to spit out React elements in the end if needed.
