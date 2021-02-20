# Async: When, Where and How?

Another Braindump to get this out of my head. Mostly written for myself but might be of interest to others.

Async rendering is somewhat the Holy Grail for UI programming and coming up with a proper abstraction for this is incredibly difficult.

First maybe some background about why "async" rendering is important. It is a fact of UI development that there may be times when you do not have all the data available that you are supposed to display. A webapp may need to load some data from the server or you have some expensive calculation done in a webworker that you want to show the results for. I'm going to stick with web examples but the problem exists in all UIs and on all platforms (including native apps, games, etc.). It may be something "low level" such as loading a texture from disk into the GPU memory or "high level" as loading some data via XHR in the browser. At some point there is going to be a barrier where you can't complete a "render" just yet.

One strategy for dealing with this is some form of the common loading placeholder. You can get absolutely [creative](https://loading.io/spinner/) with those, and they are certainly better than showing nothing, but they are not a solution. An additional challenge here is showing them at the correct granularity level. You might have a "component" loading one thing and displaying the loading placeholder, when loaded it'll render another component that also needs to load more and again showing a loading placeholder and so on. This may cascade many levels down and will result in a horrible user experience. This may even happen in "parallel" leading to multiple different loading spinners visible at the same time.

To avoid the above it might be better to delay rendering until all data is available and only then actually rendering the full "picture". You still get one loading placeholder but not the cascade of delayed layout shifts you'd otherwise get. Games typically do this where you see a long loading screen while everything the game needs is loading into the proper places and once that is done the game actually starts. This can get quite annoying (especially on lower end hardware). It is still a million times better than the alternatives where the game has to freeze in the middle of gameplay to load something it didn't yet have or showing a loading placeholder.

Doing this however can become very difficult since you need to know what data exactly you are going to need. You don't want to load too much since that would take too long, but you also can't afford to miss anything. In the web world there are a few frameworks/libraries that attempt to do this in a somewhat straightforward way. In CLJS there is `om` or its "successor" `fulcro` built on the same ideas that originated from the `react` world. Sorry forgot the names of those. They are mostly old ideas recycled in a modern context. It sounds nice in theory but in practice this suffers too.

Suppose you have a traditional website with a layout organized into 3 columns: left, center, right. In a naive setup of the above you collect all the data you are going to need at the top and fire of a request to the backend. In the meantime the frontend just shows a friendly loading placeholder. All data arrives and the user gets to see the page. However the data for the "center" column took 2 seconds to load for some reason. Even if the backend was "smart" and got the data for the 3 columns in parallel it doesn't matter that left and right took 5msec to load. The final response comes in after 2 seconds since the center took that long. So the users sees nothing for 2 seconds instead of seeing left and right immediately, and the center a bit later.

Of course that is the layout cascade I described just before that we want to avoid but in certain situations that is actually the better thing to do. It is impossible to know how long each bit of data needs to load and thus loading everything together is undesirable in many applications.

As you might have gathered this is why the problem is so extremely difficult. There is no one size-fits-all solution for this. Each Application will have its own unique requirements and solving this at a library level seems almost impossible.

Writing a library that allows the developer to handle both cases in useful way has occupied my thoughts for way too long. I admit defeat. I have some partial solutions for the most common things, but that seems to be the best I can do. The `react` team has been at the forefront of this for a long time too and in fact motivated many of the same decisions I made. I wonder what "Concurrent Mode" will bring, but I'm no longer certain this can actually be solved at the library level.

I also believe that these problems are generic enough that they are completely independent of language and platform and exist everywhere. I'd love to see actual solutions but given the websites and apps I interact with this problem seems to remain unsolved. Some handle it better than others, so you can get it to a decent point but almost everything falls apart everywhere as soon as something unexpected happens. Handling all the things that can go wrong properly is of course extremely difficult. Adding a library that abstracts this too much might actually make it impossible to handle some cases so that makes writing such a library even more challenging.

## How It Works Now

For the most part `shadow.arborist` and `shadow.grove` don't handle it at all. They just provide enough "hooks" so the user can decide what to do. If a component doesn't have all the data it needs available it'll render nothing and the content will appear later. The developer will have to decide whether a loading placeholder should be shown or not.

One provided solution is based on the same concept as `react` [Suspense](https://reactjs.org/docs/react-api.html#reactsuspense). The library will attempt to render a given subtree but when a component "signals" that it can't finish the placeholder is shown. This is done by inserting special "suspense" markers into the component tree and it can monitor the rendering of the children. I do not know how "Suspense" is actually implemented in `react`. I can only explain how `shadow.grove` handles it.

Instead of just rendering a component the call is wrapped into `sg/suspense`.

```clojure
;; before
(defn page []
  (<< [:body
       [:div.flex
        (nav)
        (content)
        (sidebar)]]))

;; after
(defn page []
  (<< [:body
       [:div.flex
        (nav)
        (sg/suspense
          {:fallback "Loading"
           :timeout 500}
          (content))
        (sidebar)]]))
```

Now if anything rendered by `(content)` "suspends" on the initial render the `suspense` component will instead render the fallback. This could be any kind of HTML via `(<< [:div.loading-spinner])` or so. The point is that the developer decides what to display at this exact point in the document. To avoid layout shift it would probably render a proper `:div` with the desired dimensions or so. `shadow.grove` can't know this, so the developer has to provide it.

One important aspect is that this is only handled ONCE. Suspense only ever displays the fallback if its second argument changes and on initial render. If something inside the `(content)` area changes after the initial render that requires async work it'll not trigger this suspense point again. I believe `react` handles this differently here but I'm not sure.

Suspense however will attempt to be smart about whenever its content changes. For example in the `shadow-cljs` UI (the current testbed for all of arborist/grove) this is used for "routing" and rendering the different pages. See [this snippet](https://github.com/thheller/shadow-cljs/blob/9fe223139dab0387f72a5d1905836b37d7be12a7/src/main/shadow/cljs/ui/main.cljs#L128-L156) which I'll use a shorter version of here:

```clojure
(sg/suspense
  {:fallback "Loading ..."
   :timeout 500}
  (case (:id current-page)
    :builds
    (builds/ui-builds-page)

    :dashboard
    (dashboard/ui-page)

    ...))
```

The second argument to `sg/suspense` changes based on the `current-page` data. Again the developer is responsible for this but the `case` here works just fine. Suppose the user clicked a link to the `:builds` page and was on the `:dashboard` page. On re-render the `suspense` will notice that the new "child" is a different component and as such will try to render this new component. Meaning it will construct the DOM but only insert it into the document once it is ready. The current `:dashboard` will remain visible and active on the page until either the `:timeout` is hit or the new "page" is ready. The `:timeout` lets the developer control the user experience. It feels weird if you click something and seemingly nothing happens so using a long timeout is bad. It is however not any better if you display the "Loading" immediately and the then displaying the actual page 50ms later. We can't know how long this will take but this logic is decent enough. If the new page renders within 500msec the `"Loading ..."` placeholder is not shown. If the user clicks on something else to get a different page while one is loading the old one is discard and the new one takes its place.

In practice `suspense` should be used sparingly but if you know when something might not be immediately available when first rendering it will be "good practice" to think about where to place them. They may be nested within each other. The components can freely suspend without having to worry about what to display while loading. They still can but offloading this makes things a little simpler. Of course the developer is responsible for handling failures and updating data accordingly so `suspense` doesn't end up showing `"Loading ..."` indefinitely. `suspense` dose not handle any errors since it is not its responsibility.

I won't go into how components can actually "suspend" here. I wrote about [components](https://github.com/thheller/shadow-experiments/blob/master/doc/components.md) here and it is based on those hooks and a special "scheduler".

## Work Scheduling

This topic is a science in itself. React's ambitious "Concurrent Mode" attempts to tackle this as well but `shadow.grove` only has a very basic abstraction for this to enable `suspense`. The basic idea here is that you want to be able to control how much work the "rendering" is going to do and maybe intermittently yielding control back to the browser so it can respond to user events and so on. Currently there isn't really a way to do this in the browser properly but new things like [isInputPending](https://github.com/WICG/is-input-pending) attempt to give libraries/frameworks enough control so they can "cooperate" better in the async world the browser is.

While specific components may use such API's to control when they do work the underlying framework will not. Maybe if "Concurrent Mode" comes out and they solve some really hard problems it might be worth reconsidering. For now I believe that this is somewhat over-engineering the problem at the wrong level. If the "rendering" process is fast enough you don't really need to schedule the work. The underlying problem here I believe to the basic nature of the `react` algorithm. Diffing every single DOM node and its props just incurs so much work that is often not necessary. Especially when rendering a specific node involves passing through 10 higher-order-components first. Getting a `div` into the page should not involve hundreds of function calls. Just doing less work is the overall goal of all of this.

I do believe that CLJS is uniquely powerful here and the `shadow.arborist` macros implement those ideas. `Svelte` and other JS frameworks function along the same lines but require specialized compilers.

I do however acknowledge that I'm punting on this topic because it is so complex. Solving this properly is immensely useful, but I don't know how. It is too low level, and you might be able to solve it if you build and entire language and runtime around it. Doing this in the Browser where everything is already fighting over resources without much coordination seems impossible to me. At some point you have to do the work and constantly checking if you should "yield" will makes the code almost impossible to reason about. IMHO, YMMV.

## Other Considerations

To enable `suspense` and other DOM specific stuff the `shadow.arborist` implementation currently uses two distinct phases. One is the construction of the actual DOM nodes. This happens entirely in memory and the DOM nodes they construct may not yet be in the document. Once completed they "enter" the document and a second `dom-entered!` traversal is done over the tree. The second traversal is required because certain components may want to measure DOM elements or similar things that require the element actually being in the document.

I did spend a fair amount of time thinking about making the initial construction of the DOM nodes async. It could be promise based and once the promise resolves the library would know that the subtree is ready. I did however decide not to do this since MOST operations can be completed sequentially and don't actually need to go async. Layering `suspense` on top later is already possible by having distinct "construct" and "enter/mount" phases. Making everything async makes the code horrible to work with in the algorithms themselves.

Animations and Transitions are however still a somewhat sketchy topic. I do like how `Svelte` integrates those directly into the runtime. For this to work properly however the underlying implementation kinda needs to be promise based, at least partially. I gave up on this for now since I'm by no means an expert in Animations or Transitions and don't actually know what they would require. Basic CSS Transitions are supported and usable and you can always fall back to working on the DOM nodes directly if needed. It should be possible to do pretty much everything, but I don't know how to make it simpler.








