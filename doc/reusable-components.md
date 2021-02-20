## Reusable Components

This is a braindump. Rough ideas floating arround in my head that I have been thinking way too much about. I'm writing this down for me so I can maybe find some direction in those thoughts. Others might be interested in this which is why this is public.

One of the challenges in `shadow.arborist` or the highler-level `shadow.grove` is building components in a way that is flexible enough to allow building reusable components without sacrificing the overall goal of being efficient.

Currently, pretty much all other implementations like `react` suffer greatly in this area and while performance is mostly fine it is also extremely wasteful. Computers are unbelievably fast nowadays, but we still struggle to build UIs than can do 60fps constantly. How is it that computers keep getting faster but almost everything in Software keeps getting slower?

`react` (and others) are not the only thing responsible for this, but they do contribute. Things get especially wasteful when you add a CLJS translation layer such as `reagent` on top.

This is a difficult topic to talk about since it is also kind of difficult to measure. A naive benchmark might give you numbers that look "good enough" and they are indeed mostly "good enough". Just if you add them all together they are no longer "goog enough".

The performance budget is already extremely tight and if you are wasting too much of it on needless work you don't have enough left to do actual work. A rough guideline is that you want to achieve 60fps which means you have about 16.66msec to get stuff done. Modern displays have even faster refresh rates (even on mobile) so if you move that to 120hz/fps you get only half that. This is already basically impossible to achieve with most modern frameworks. Modern hardware like the Apple M1 might run things faster for a bit so you don't notice bad performance but older hardware even from a couple years ago already struggles a lot. I'm almost certain we will manage to eat up that added performance very quickly again.

I do hope that there is a better way to do things but finding a good balance between usability and performance is quite challenging.


**BEWARE: Below you'll find some terrible micro benchmarks that will probably not relate to real world applications.** It'll be rather difficult to compare real world applications though.

## Performance Study

I really don't want to pick on any particular framework or library but I do have to use examples to clarify what I'm talking about here. Everything I'm using as an example here does provide value and the performance problem I'm referring to might not be relevant in things you are building. Just about every "reusable component library" I looked at has these problems. I do not want to "judge" these in any way but too much is being sacrified in the name of convenience with little regard to performance. **This might be the correct trade-off for you**, just be aware that you are making that trade-off.

I'm going to use an example from [re-com](https://re-com.day8.com.au/#/introduction) which is a library of ClojureScript UI components, built on top of [reagent](https://github.com/reagent-project/reagent). Specifically the [Gap](https://re-com.day8.com.au/#/gap) example since its sufficiently simple to do here.

The code looks like this:
```clojure
(defn grey-box []
  [:div.border.border-grey-500.bg-gray-100.p-8 "box"])

(defn example-recom []
  [rc/h-box
   :gap      "10px"
   :children [[grey-box]
              [grey-box]
              [rc/gap :size "5px"]
              [grey-box]]])
```

The HTML you end up with is
```html
<div class="rc-h-box display-flex " style="flex-flow: row nowrap; flex: 0 0 auto; justify-content: flex-start; align-items: stretch;">
  <div class="border border-grey-500 bg-gray-100 p-8">box</div>
  <div class="rc-gap " style="flex: 0 0 10px; width: 10px;"></div>
  <div class="border border-grey-500 bg-gray-100 p-8">box</div>
  <div class="rc-gap " style="flex: 0 0 10px; width: 10px;"></div>
  <div class="rc-gap " style="flex: 0 0 5px;"></div>
  <div class="rc-gap " style="flex: 0 0 10px; width: 10px;"></div>
  <div class="border border-grey-500 bg-gray-100 p-8">box</div>
</div>
```

Running this is in an extremely simplistic benchmark setup that just renders it into 10 freshly created nodes I get
```
"Elapsed time: 8.940000 msecs"
"Elapsed time: 1.240000 msecs"
"Elapsed time: 1.110000 msecs"
"Elapsed time: 1.070000 msecs"
"Elapsed time: 1.055000 msecs"
"Elapsed time: 1.010000 msecs"
"Elapsed time: 1.270000 msecs"
"Elapsed time: 1.065000 msecs"
"Elapsed time: 1.060000 msecs"
"Elapsed time: 1.005000 msecs"
```
It starts out fairly slow but once the JIT kicks in it stabilizes at about 1msec. That might not be too bad, but it also does basically nothing. These are `:advanced` optimized numbers, so all "debug" related code should not be active. It is much slower in development, but that is fine.

If I now just take the above HTML string and create 10 new nodes and set this string via `innerHTML` I end up with the exact same HTML on the page with fairly significant performance difference.

```
"Elapsed time: 0.190000 msecs"
"Elapsed time: 0.050000 msecs"
"Elapsed time: 0.035000 msecs"
"Elapsed time: 0.040000 msecs"
"Elapsed time: 0.050000 msecs"
"Elapsed time: 0.125000 msecs"
"Elapsed time: 0.030000 msecs"
"Elapsed time: 0.035000 msecs"
"Elapsed time: 0.035000 msecs"
"Elapsed time: 0.045000 msecs"
```

This gives us a baseline. Adding re-com, reagent, react makes this up to ~30 times slower. Of course you really can't write an app by setting `innerHTML` but given that this is about the most basic example I can come up with this seems too slow to me. The point I'm trying to make here is that all these abstractions have a cost and at a certain point you cannot keep trading "developer convenience" for performance anymore. At least in my view you should not.

## Picking The Correct Abstraction

Bringing this back to the stuff I have been thinking about too much. I have been trying to come up with a way that still provides enough "developer convenience" but doesn't trade away that much performance.

At first glance the above abstraction for `h-box` and `gap` seems unnecessary to me. Yes, it provides the developer with "new" terminology and hides HTML and CSS flex-box. This might even be the motivation behind this library I'm not entirely sure. I firmly believe that you cannot build a proper Browser based application without knowing HTML and CSS. Abstracting this away might work for you but be aware of what you are "paying" for it.

Just removing `re-com` and instead generating basically the same HTML directly via `reagent` turns this into

```clojure
(defn example-react []
  [:div.flex
   [:div.border.border-grey-500.bg-gray-100.p-8.mr-4 "box"]
   [:div.border.border-grey-500.bg-gray-100.p-8.mr-8 "box"]
   [:div.border.border-grey-500.bg-gray-100.p-8.mr-4 "box"]])
```

Of course this isn't 100% the same. It is also using `tailwind` instead of custom CSS but the HTML structure does look simpler to me and the end result visible to the user is almost identical with the only difference being the distances since `re-com` used pixels but `tailwind` uses `rem`. I won't go into this here but I do believe it is a useful abstraction to have.

Of course this might only look simpler to me since I know HTML and CSS inside out, but I do think that anyone can understand this if you can understand `h-box` in the first place. I keep hearing from people that they don't want to learn HTML and CSS and I can absolutely relate to that. There is a waaaaaaay too much to learn if you want to build frontend stuff. I do however think that there is no way around this for now.

Performance for this however looks almost identical to the `re-com` example which means `re-com` is probably fine and the problem arises at a lower level.
```
"Elapsed time: 8.980000 msecs"
"Elapsed time: 1.250000 msecs"
"Elapsed time: 1.135000 msecs"
"Elapsed time: 1.100000 msecs"
"Elapsed time: 1.065000 msecs"
"Elapsed time: 1.020000 msecs"
"Elapsed time: 1.245000 msecs"
"Elapsed time: 1.065000 msecs"
"Elapsed time: 1.015000 msecs"
"Elapsed time: 0.995000 msecs"
```

So the next level of abstraction is the `reagent` `hiccup` level. This translates the CLJS vectors/keywords to `react` elements. Removing that and directly generating those `react` elements gives us

```clojure
(defn example-react []
  (el "div" #js {:className "flex"}
    (el "div" #js {:className "border border-grey-500 bg-gray-100 p-8 mr-4"} "box")
    (el "div" #js {:className "border border-grey-500 bg-gray-100 p-8 mr-4"} "box")
    (el "div" #js {:className "border border-grey-500 bg-gray-100 p-8 mr-4"} "box")))
```

Admittedly this doesn't look very appealing, but it does perform a decent bit better.

```
"Elapsed time: 7.795000 msecs"
"Elapsed time: 0.515000 msecs"
"Elapsed time: 0.400000 msecs"
"Elapsed time: 0.390000 msecs"
"Elapsed time: 0.385000 msecs"
"Elapsed time: 0.370000 msecs"
"Elapsed time: 0.465000 msecs"
"Elapsed time: 0.485000 msecs"
"Elapsed time: 0.370000 msecs"
"Elapsed time: 0.365000 msecs"
```

Allocating those vectors and translating them to react elements is not free and that is why there are so many CLJS macro based libraries that try to do this at macro time (eg. uix, helix, sablono, fulcro, etc). This gets you close to the above performance, but you are trading in some convenience and power. It is however also still about 10x slower than raw HTML generation which seems to be about the `react` tax. This is well worth it but I'm convinced we can do better.

In fact when I run this basic example through `shadow.experiments.grove` I get

```clojure
(defn example-grove []
  (<< [:div.flex
       [:div.border.border-grey-500.bg-gray-100.p-8.mr-4 "box"]
       [:div.border.border-grey-500.bg-gray-100.p-8.mr-8 "box"]
       [:div.border.border-grey-500.bg-gray-100.p-8.mr-4 "box"]]))
```

```
"Elapsed time: 0.300000 msecs"
"Elapsed time: 0.085000 msecs"
"Elapsed time: 0.095000 msecs"
"Elapsed time: 0.070000 msecs"
"Elapsed time: 0.080000 msecs"
"Elapsed time: 0.075000 msecs"
"Elapsed time: 0.070000 msecs"
"Elapsed time: 0.100000 msecs"
"Elapsed time: 0.060000 msecs"
"Elapsed time: 0.065000 msecs"
```

That looks promising. It does however cheat. The fragment macro `<<` will analyze the form and generate raw HTML construction code for those DOM elements. With a few tweaks this could get even closer to the raw `innerHTML` by doing exactly that. In practice however things won't be that static so this comparison is unfair.

I also only used to initial DOM construction code as an example here. The update cycle difference will be even more in favor of `shadow-arborist`. Of course again this is because it is cheating and can analyze that it doesn't need to update anything since the form was completely static. It is however fair to assume that all updates are always going to be faster than `react` or equal at the very worst case.

The point of all this is to show that we can in fact do stuff in CLJS with the help of macros that are difficult to accomplish in `react`. Of course this idea of using the "Compiler" also exists in the JS world and in part inspired what is now in shadow-arborist. The honorable mentions are `Svelte` and `vue`. Since they are almost impossible to use from CLJS, due to being compiler based, I will not be including them in any benchmarks.

## Making Things Re-Usable Again

You may have noticed that the above is no longer a "reusable component" which `re-com` initially was made for in the first place. As I said in the case of `h-box` this "reuable component" is abstracting too much and not needed but there will in be many other components and you want to be reusable. `re-com` has a bunch of those and me picking on `h-box` shouldn't in any way take away from those.

I do however want to focus on `shadow-arborist` related things since I want to solve those there and I'm happy if this ends up being at the same speed as `reagent` currently is. Just getting rid of `react` is a plus for me. It probably won't be for many people but that is fine.

I'm not going to use `h-box` as an example anymore because but lets stick with a simple `box`. To make things more exiting lets have this `box` have a title also. Excuse me for not caring about styling much but the basic thing could look like

```clojure
(defn box [title body]
  (<< [:div.border.shadow-lg
       [:div.p-4 title]
       body]))

(defn example-grove2 []
  (<< [:div.flex
       [:div.mr-4 (box "title a" "box")]
       [:div.mr-8 (box "title b" "box")]
       [:div.mr-4 (box "title c" "box")]]))
```

Using the same benchmark setup as above this still gets us decent performance of

```
;; omitting the first 9 runs from now on, the pattern is always the same
"Elapsed time: 0.055000 msecs"
```

The above however starts to get kind of ugly once you factor in that the `box` body may contain more HTML and not just an actual string.

```clojure
(defn example-grove3 []
  (<< [:div.flex
       [:div.mr-4
        (box "title a"
          (<< [:div.font-bold "box"]))]
       [:div.mr-8
        (box "title b"
          (<< [:div.p-8 "box"]))]
       [:div.mr-4
        (box "title c"
          (<< [:div.text-2xl "box"]))]]))
```

This gets even worse when you account for the title being HTML and not just a plain string.

```clojure
(defn example-grove4 []
  (<< [:div.flex
       [:div.mr-4
        (box
          (<< "title a" [:sup "1"])
          (<< [:div.font-bold "box"]))]
       [:div.mr-8
        (box
          (<< "title " [:span.font-bold "b"])
          (<< [:div.p-8 "box"]))]
       [:div.mr-4
        (box
          "title c"
          (<< [:div.text-2xl "box"]))]]))
```

Given how `shadow-arborist` operates replacing a string with the `<<` fragment is completely fine and the `box` implementation didn't change. Performance is still quite nice and just got slower because a few more DOM nodes need to be allocated. Update will still be basically free since the parts of still mostly static and don't actually need to update anything.

```
"Elapsed time: 0.080000 msecs"
```

I do however think that this is starting to suffer on the readability front and want to improve it. I'm just not sure how. Or maybe I'm overthinking it and this is fine. The problem that needs to be solved is passing arguments to components which may contain other DOM structures as well as regular data.

`react` does this by taking at most one `props` object and then a variable amount of "children". This abstraction mostly failed in my view and isn't worth doing. Too often you'll have two or more separate things like the above `title` and `body`. The abstraction works fine for regular DOM nodes but not for reusable components. Of course you can just put things into `props` even in `react` but that has the same readability issues and introduces and extra `props` object that needs to be allocated.

It doesn't exactly make things easier to read in my view. So instead `shadow-arborist` has two abstractions for this. Regular DOM fragments that just use hiccup notation and the almighty function call for "components". They are actual function calls, the `<<` macro does not do any magic here. The question is only how to you pass fragments "down". You can use the `props` way and put them into a map or you can use multiple function arguments like `box` currently does.

In our case that would be
```clojure
(box
 {:title (<< "title a" [:sup "1"])
  :body (<< [:div.font-bold "box"])})

;; vs.
(box
  (<< "title a" [:sup "1"])
  (<< [:div.font-bold "box"]))
```

Reagent also allows multiple arguments and is not like `react` in that regard.

In the Web Component world there is the concept of "slots" where the web component provides specific DOM marker nodes (eg. `<slot name="title">`) that the contents will be placed into by the outer structure.

So you could have
```html
<my-box>
    <span slot="title">title a<sup>1</sup></span>
    <div slot="body" class="font-bold">box</div>
</my-box>
```

This looks fairly decent but the `my-box` component will never actually receive the `title` or `body` as arguments and instead just provide the engine with place to put them. This isn't possible in `react` so there is no direct translation. This is possible in `shadow-arborist` or at least I started the groundwork but I'm somewhat undecided and think that this may not be worth doing.

```clojure
(<< [:into (box)
     [:slot/title "title a" [:sup "1"]]
     [:slot/body [:div.font-bold "box"]]])
```

Keeping the components-are-just-function-calls but introducing a new way to handle slots. There might be better ways to handle this syntax wise, but this is the best I came up with so far (but didn't actually implement). What this saves is all the extra `<<` noise but under the hood would emit basically that without actually passing it to the `box` however. On mount the `box` would just create the proper placeholders and the engine would then place those nodes accordingly.

This indirection complicates the rendering process a fair bit and does not solve any actual problems. At least I cannot think of anything other than syntax issues. It is important to note that `<<` does NOT actually create the DOM nodes. It just creates a simple value with a reference to a function that will handle the actual nodes "later". So passing the result of `<<` around is completely fine and cheap. Performance wise I don't expect any difference between the slot and unslotted variants.

I have never built an actual project that made heavy use of web components with slots so the concept may just look somewhat unappealing since it is "foreign". The `box` component changes in that is places a slot marker instead of the actual value and becomes an actual component. Can't do this for plain `defn` but `defc` is meant to look almost identical in form. The empty `[]` is still the argument vector but `box` no longer has any since they became slots.

```clojure
(defc box []
  (render
    (<< [:div.border.shadow-lg
         [:div (sg/slot :title)]
         (sg/slot :body)])))
```

## The Missing Piece

The experienced `react` or `reagent` developer however may notice that all of this is missing one thing that may actually be important for reusable components. The ability to modify "children" before they are rendered. Many of those reusable components may want to add a CSS class, add or change attributes, add event handlers and so on.

Doesn't matter whether we use the slotted variant or not, currently the `<<` macro does not return data that you can modify. You could in theory just use plain hiccup style vectors but that throws all optimizations out the window.

`re-com` also makes heavy use of this. CLJS in fact does win in this regard since we are used to rewriting CLJS datastructures like vectors and maps anyways. In plain `react` cloning and modifying the `children` is actually kinda tedious but it is used heavily in many libraries.

Web Components also struggle with this since they can't really know what is in the slots. They can access those nodes but aren't notified when they change so this gets kinda difficult to do. Possible but rather ugly, at least I haven't seen a clean approach for this.

My current thinking is that it isn't actually much of a problem in real apps however since components can and most often will introduce extra DOM elements anyways which they can do those modifications for and just place the other stuff into.

There is also always the option of falling back to regular vectors and paying the performance tax for the extra flexibility. You also just pass a function "down" to the component if the child wants to affect the rendering of the nodes it is supposed to place. Of course that pattern of a render function is also common in `react`.


```clojure
(defn box [title body]
  (<< [:div.border.shadow-lg
       [:div.p-4 (title {:data "yes"})]
       body]))

(box
  (fn [{:keys [data]}]
    (<< "title a" [:sup data]))
  "body")
```

This example is a bit stupid but being able to pass data that way is actually kinda important in certain places for more complex components. I don't know what would be done with slots. I do think it is better to use a function instead of trying to rewrite the children.

Functions however introduce a whole other problem with regards to performance since they do not have identity and you cannot "skip" over rendering them. Given how fast everything is however that is probably not an issue.

## The Protocol Way

Since all of this is built on top of protocols you can actually just implement reusable components using those. Currently there are several implementations for this and I expect many libraries to provide those in cases where the component abstraction doesn't fit. Apps will likely just use components though.

The implementation for those protocols will be a little more involved but they gain a lot of power that way and can do things that `react` can't really do as is basically directly changes the "reconciler" implementation.

I think this overall strikes a good balance and achieves promising performance while staying developer friendly.

In fact since this is based on protocols we can actually just implement `reagent` style hiccup support and take the previous `re-com` output and render that with any modifications. This obviously won't work as soon as it wants to do something react specific but for the simple example from above it works just fine.

Performance is decent and can probably achieve pure `react` levels. I didn't do any performance tuning for this yet, but it already beats the `reagent+react` combo easily. It also interfaces seamlessly with the rest so in performance critical code you can take the faster fragment macro and things that don't need the performance can stay interpreted.

```
"Elapsed time: 0.590000 msecs"
```
