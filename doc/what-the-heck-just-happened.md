# What The Heck Just Happened?

This seems to be the main question to answer when building web applications in the `react` style virtual DOM model. The promise was to just run a function over your data and that gets you your UI. Just let the "framework" worry about diffing and making the necessary changes on the page. In practice however this is rarely fast enough so it needs all sorts of tweaks to make it faster. [Rich Harris](https://www.youtube.com/watch?v=AdNJ3fydeao) explains this much better than I ever could.

I did implement most of the optimizations that removed most of the VDOM diffing, but the question is still asked in many different other places. How do we get the rest? Just optimizing the DOM bits is not enough.

Svelte achieves this by processing the JS of each component and wiring up the reactivity directly in the code. That of course only works when the compiler can actually see the code. The "Store" abstraction is used in places where you want to share state between components but still react to changes. I haven't written actual Svelte apps, so I cannot say how well this scales.

We could maybe achieve the same using some macro magic but I wanted to explore ways that are less "magic" first.

Ideally when writing an app I want to have a normalized DB queried by EQL. `fulcro` implements and documents those ideas very thouroughly so I recommend going over that if you don't yet think that this is the best way to build apps. It is however very challenging to get that level of convenience and still keeping the performance goals I set for myself. `fulcro` is most certainly fast enough to write most apps, but I wanted to see how much faster we can get. I obsess way too much about performance that way unfortunately.

So ultimately this comes down to answering "What The Heck Just Happened?". You have `before` and `after` and you need to figure out what changed efficiently. Offloading this to the VDOM is not practical since it involves generating way too much VDOM which ultimately answers this question way too often. Even without VDOM however we still need to answer this question too often.

"Normalizing" the data we work with is the first problem this needs to be solved I'm not sure what the most efficient way to handle this is currently. One way is to build and entire "Database" like `datascript` around it and another is to build it just on top of regular CLJS datastructures. There are many more of course each with their own set of trade-offs and performance charateristics. Not solving this problem properly first results in data being replicated in many different places leading to inconsistencies and other issues that get increasingly hard to maintain over time. That might be totally worth the trade-off though so doing this always is definitely not the best approach for all apps.

While databases like `datascript` are useful they carry too much overhead in my view. Getting the data into and out of the DB and into a shape usable by the UI might get clunky and the query performance isn't that great either. The performance overall is good enough but doesn't fit my goals.

Instead `shadow-grove` exploits the protocol driven way the CLJS datastructures work and giving you something that looks like a map but actually just delegates to one while observing what you did.

So you get a `db` that you can just `assoc` or `update` data into like a regular map but the implementation records those actions.

In the framework this looks something like

```clojure
(let [tx-data (db/transacted @data-ref)
      tx-result (do-work tx-data)
      {:keys [data keys-changed keys-removed keys-inserted] :as tx-info}
      (db/commit! tx-result)]  
  (reset! data-ref (:data tx-info))
  ...)
```

After the work is completed we know which keys were changed and can greatly reduce the number of times we have to ask "What The Heck Just Happened?". If you have thousands of keys that becomes very significant, and the overhead of collecting this extra data is minimal in comparison.

The user code just treats the `db` as a regular map with some constraints. Similar to a key/value store you shouldn't iterate over all keys and so on. In practice you never do that anyways.

On the read side we also need to observe which keys are accessed. Otherwise what good is knowing what keys were modified? We need to know what to update. This again is done by given a thing that looks like a map but isn't to the "reader". `(db/observed data)` instead of just `data`. The user code doesn't change but the framework can later check which keys were used.

Another layer on top is the EQL abstraction which lets components be declarative about the data they need. This is beautiful to work with but it comes with a significant cost currently. My naive implementation is limited to basic EQL and doesn't have any of the more advanced features `pathom` has. It is however many times faster than `pathom` but still too slow in my view.

The theory was that comparing the EQL query result was simple and you could easily know whether a DOM update needs to be performed or not. In practice however updating the DOM is now the least expensive part of all of this, and the whole query machinery is still way too slow. This hasn't been optimized much yet and I do think this can get to a reasonable level though.

Removing EQL from the equation might already give a considerable performance boost. Just a `(get data key)` is guaranteed to be faster. EQL was introduced as an abstraction for server access after all where it definitely matters not getting a gazillion extra keys in your data you didn't ask for. For UI purposes when getting the data from memory it doesn't matter if we get a map with 2 keys we want or it having extra keys. `(:foo data)` is always faster than `(:foo (select-keys data [:foo]))`. By observing (cheap) which keys were actually accessed we also don't later have to guess what needs updating after answering "What The Heck Just Happened?".

Frankly, I feel like there must be a better way to do this.

[o'doyle rules](https://www.youtube.com/watch?v=XONRaJJAhpA) looks intriguing for sure but its performance is a lot worse than the above and I'm not sure this can ever reach the level of fast I'm looking for in a dynamic UI.

I also tried moving all data processing into webworkers but that isn't practical for many things. It doesn't block the UI as much but ultimately does a lot more work.

What I have now is good enough, but the performance keeps bugging me. I don't see any possible 10x improvements which this would require to be truly fast enough. I'm likely also just over optimizing a problem that isn't really a problem.

Maybe we should go back to the old ways and "push" updates directly to the place they need to go instead of having some abstraction "pull" it out of a hat for us.