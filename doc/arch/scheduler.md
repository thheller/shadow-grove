# Scheduler

## Motivation

Scheduling Work (eg. DOM updates) is a hugely important aspect to get everything in a client-rendered UI to work properly and efficiently

The naive strategy is to not schedule at all and just always do all the work right away. That can absolutely work but can also lead to very inefficient or plain out broken UIs.

Scheduling work is therefore mandatory, yet ideally completely invisible to the developer using all of shadow-grove. At most the developer should have the option to annotate certain work as critial or low-priority. While that part is pending the rest is in working condition until I can think of something better.

### Example: Unnecessary Work

In the common [TodoMVC](https://code.thheller.com/demos/todomvc) demo application there is a listing of todos. You can switch that listing to only show "Active" (ie. uncompleted) todo items. Toggling an active item here to be completed will also remove it from the active listing.

So depending on how events are handled you may now end up first re-rendering the todo item to reflect its completed state. Might just be a trivial thing such as swapping a css class or style. As a second step the listing of todos re-renders and the item we just updated is removed.

So the first update was entirely unnecessary and should have been skipped.

### Yet, Maybe Necessary?

We do need to maintain the possibility of this work occurring,  if for example the item should visually animate "out" in some way instead of just disappearing instantly. So the work may to be queued to happen at the correct time.


# Current Implementation

```clojure
(defprotocol IScheduleWork
  (schedule-work! [this task trigger])
  (unschedule! [this task])
  (run-now! [this action trigger])

  (did-suspend! [this target])
  (did-finish! [this target]))

(defprotocol IWork
  (work! [this]))
```

The Browser currently doesn't have a proper way to do any kind of cooperative scheduling or prioritizing work. There are a few efforts underway to implement these as a standard, but as of writing this that is still upcoming.

So instead shadow-grove uses its own tree-based scheduling mechanism. Components are deeply integrated into that system and do all their work accordingly.

## Component Intro

```clojure
(defc ui-example [a b]
  #_1 (bind x (+ a 1))
  #_2 (bind y (+ b 1))
  #_3 (render
        (<< [:div "x: " x " y: " y])))
```

Components are designed to split work they need to do in hooks and a final `render`. When a component is first rendered the steps 1,2,3 run in sequence. If however a mounted component is re-rendered only the minimal necessary work should happen.

If it received the same `a` and `b` arguments no work needs to happen at all. Since nothing has changed there is no need to update the UI.

If for example `a` changed we need to run Step 1 and 3 again but can skip 2 since its input `b` didn't change. Similarly, if `b` changes, or both change the proper work needs to happen in order.

Contrast this with a simpler regular function.
```clojure
(defn ui-example [a b]
  (let [x (+ a 1)
        y (+ b 1)]
   (<< [:div "x: " x " y: " y])))
```

This is just as valid and will yield the same "UI". However, it cannot stop early and the resulting fragment will always have to check if it needs to update.  I picked a trivial example here so in a real app the `defn` variant is totally fine. However, if you swap `+` with `expensive-calculation` the switch over to `defc` may noticeably improve your UI performance.

More commonly however actual hooks may be used to integrate data from other sources into the UI tree. And they need a proper way to signal the component to update while also avoiding unnecessary work as described above.

## Query Example

```clojure
(defc ui-example [ident]
  #_1 (bind {:keys [foo bar]}
        (sg/query-ident ident [:foo :bar]))
  #_2 (bind baz (+ foo 1))
  #_3 (render
        (<< [:div "foo: " foo " baz: " baz])))
```

In this the query hooks into the lifecycle of the component and may have a new value for `foo` or `bar` at any time. If so, it will notify the component that it should update. The component will not actually run its query again until it is its turn to update. Other hooks may behave differently but query by will wait for its turn.  The component will then signal its parent scheduler (most likely another component) that it needs to update. Going up all the way to the root in the same manner.

Once it reaches the root the actual work starts. Basically it just goes all the way back down until it reaches the query that wanted to update in the first place. Each scheduler (eg. component) does its own work first (eg. updating its own hooks and potentially rendering). Then it continues down and the next does the same.

This first going up the tree and then going down for several reasons.

- It solves the issue described in the intro. A Child component may end up getting destroyed but to know that we first may need to re-render the parent. If however the parent decides it doesn't need to do any work we can safely proceed with the child.

- It also happens to fit nicely into the event system where events also always bubble up to the root until handled.
- It also covers cases where a change in data may actually incur an update in several branches of the tree, and they should happen from top to bottom and not in undefined random order.
- It also of course just fits into the natural order of stuff. A parent is after all responsible for rendering its children. So we naturally traverse in that order anyways, just have the option to skip work wherever possible. Also the parent re-rendering may incur more work for the children, which is all accounted for due to traveling this way.

"Work" is covered by a generic protocol so everything else may participate in this work scheduling, just the most common implementation for now will be components.

The implementation is also fairly lightweight and cheap, so the overhead for this tree scheduler is fairly minimal.

### What about animations then?

That is yet to be determined. For now all scheduled work will happen as soon as possible. Developers may defer certain events manually (eg. `setTimeout` or event `fx`). I have not yet found a way to abstract this out otherwise. It may also be entirely fine to keep this manual with some helper functions. Once I find something it'll probably be its own post.


