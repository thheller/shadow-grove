# defc Deep Dive

Components in shadow-grove are defined using the `defc` macro. It is a somewhat complex macro, and I'll try to cover why it looks the way it does in this doc. This is not about explaining how to use `defc`, but rather explaining how it works internally.

Components are meant to provide an abstraction to manage state. Either application state or their own local state. They can receive data from the parent via arguments, or introduce new data into the tree via Slots.

Coming up with reasonable examples is quite hard, so I'll start with a very basic example and then go from there.

```clojure
(defc product-info [product-id]
  (bind {:keys [title] :as data}
    (sg/kv-lookup :products product-id))
  
  (render
    ;; imagine actual HTML here
    (<< [:div title])))
```

The first line is pretty much just like `defn`, defining a component with the name `product-info` taking one argument `product-id`. The component can be used by calling it like any function, e.g. `(product-info 1)`. `bind` is the first Slot. `render` is what will turn the data into actual DOM structures.

Conceptually, this is comparable to this bit of regular Clojure code:

```clojure
(defn product-info [product-id]
  (let [{:keys [title] :as data}
        (sg/kv-lookup :products product-id)]
    (<< [:div title])))
```

# Slots

The Slot abstraction is meant to break the component into smaller chunks of code that can be executed when needed. In my head, each component looks like a table, where each Row represents a Slot.

Bind always has the structure of `(bind <name> <&code>)`. The `<name>` may use destructuring and the created names may be used later in the code. The code block may do anything it wants, it has no access to its own created names, just like `let`.

| Index  | Slot Name | Uses    | Slot Code                            |
|--------|--------|---------|--------------------------------------|
| `0`      | `data`  | `#{arg0}` | `(sg/kv-lookup :products product-id)` |
| `1`      | `title` | `#{0}`    | `(get data :title)`                  |
| `render` |         | `#{1}`    |`(<< [:div title])`                   |

Internally, each Slot is referenced by its index. The developer never needs to care about the actual index as the macro completely hides that. I'll use indexes here since it makes things a bit easier to explain. The macro created a function for each Slot with some additional data to optimize some things. `render` is not a Slot, but it is its own block of code that will run once all the Slots have been processed.

## First Mount

When the component is first mounted, the function associated with each Slot is called. It is just a regular function with a return value. It does have access to some extra bindings that the running code can use. These bindings allow the code to store some additional data as well as later inform the component that its data has changed and cause an update.

`Slot#0` uses `sg/kv-lookup`, which makes use of those bindings and will basically do a `(get-in app-db [:products product-id])`. In addition, it will register itself with the `shadow.grove.kv` implementation so that it gets notified should the data change. More on that elsewhere.

`Slot#1` is a taking care of the destructuring. This technically doesn't need to be its own Slot, but it made sense in the implementation and works out well.

`render` then runs and the component will take the return value and merge it into the DOM tree.

## Updates

So far the `defn` alternate could have done the exact same thing. To understand why Slots are useful, we need to consider the update cycle. Updates can occur for two reasons. The parent might have updated and called the `(product-info <possibly-changed-argument>)` component function again, or any of the component slots might have "invalidated" themselves.

### Update Via Parent

If the parent calls the component again, it first checks whether its arguments have changed. This is basically a `(= old new)` comparison for each argument. If nothing has changed, nothing happens and Slots do not run again.

Should the `product-id` argument change, the component knows that `Slot#0` needs to be executed again. The macro extracted that bit of information during compilation and stored it in the form of a bitmask. `Slot#1` and `render` so far are still clean, since they didn't use the `product-id` argument.

After checking all the arguments, the component will work off the Slots that have been marked as dirty.

### Update Via Slot

A Slot can be marked dirty by changed arguments, or by invalidating itself. The result is basically the same, which is that its associated function is called again. Here `sg/kv-lookup` again basically just does a `(get-in app-db [:products product-id])` and returns it. Additionally, it'll update the db notification should the `product-id` have changed.

The component will then use the return value to decide what to do next. It again checks `(= old new)` and is done if nothing has changed. If it has changed, it will mark all the slots using the return value as dirty and run them as well.

## Rendering

Once all the Slots have been updated, the component may render. The macro extracted the information which data the `render` block used. In this case it is only `title` created by `Slot#1`. It will therefore not render, even if `product-id` or `data` have changed. It only cares about `title`. If no relevant data has changed, the entire `render` is **skipped**.

## Unmounting

The component will unmount if the parent is unmounted, or it decided to not render the child again. The code run in each Slot had the opportunity to register a cleanup function, which is called when unmounting. `sg/kv-lookup` did this and will remove its db notification since it is no longer interested whether that piece of data changes.

All the managed DOM structure is cleaned up as well.

# Work Scheduling

One important factor is **when** and in what **order** work happens. When a component Slot invalidates it does not immediately render the owning component. Instead, the component only marks itself as needing "work" and tells the parent about it. The parent will do the same until it ultimately arrives at the root. Once the root is reached, it will start working its way down again.

This is important since there is no use in updating a Child Component and then potentially unmounting it when the Parent is rendered/unmounted. This occurs very frequently. If a component is clean and doesn't need any work itself, it will not run its slots or render again and instead just continues down. Overall, this extra up/down trip is cheaper than the alternative.

# Summary

I hope it became somewhat clear how the `defc` macro breaks up the component code into multiple chunks that can be executed when needed. I'll go over the exact details of Slots in a different post, but overall they provide much of the same functionality that React Hooks do.

The internal state of the component could be visualized as a table.

| Index   | Dirty?  | Affects     | Value                              |
|---------|---------|-------------|------------------------------------|
| `0`       | `false` | `#{1}`      | `{:product-id 1 :title "foo" ...}` |
| `1`      | `false` | `#{render}` | `"foo"`                            |
| `render`  | `false`  | The DOM!    | `<div>foo</div>`                   |

Each Slot may flip its dirty bit, which will cause its code to run again when the components update. If the returned value changes, the affected other Slots/render will also run again. This repeats for every update.

# Comparisons

There are many different component designs, which all look somewhat different from `defc`.

I'll use some well-known CLJS+React implementations to compare to here, since they all sort of informed the design of `defc` in some way. The obvious start is re-frame+reagent:

```clojure
(defn product-info [product-id]
  (let [{:keys [title] :as data} @(rf/subscribe [:products product-id])]
    [:div title]))
```

This simple implementation has no chance to do a partial update. The entire function has to run again, which is not exactly efficient. There exists a separate form to at least split some setup code, so that that part runs only once.

```clojure
(defn product-info [product-id]
  (let [data-ref (rf/subscribe [:products product-id])]
    (fn [product-id]
      [:div (:title @data-ref)]
      )))
```

This always bothered me a bit, since the exact signature needs to be repeated in the inner function. IMHO it becomes a bit iffy once arguments actually change, since the outer `let` never runs again. I'll skip the even more verbose Reagent component style, since you'd never use it for this.

With React Hooks, this basically looks like the function I used earlier.

```clojure
(defn product-info [product-id]
  (let [{:keys [title] :as data}
        (sg/use-kv-lookup :products product-id)]

    [:div title]))
```

The downside with this is again that the entire function has to run again. It cannot exit early or skip rendering. It has to rely on memoization and other tricks to make the resulting render diff as efficient as possible. Hooks also have certain rules in that they cannot be within conditional, e.g. `if`, branches or within loops. With regular code, this can't be exactly enforced, so the runtime or linters have to check. It also leads to the creation of many anonymous fns to further reduce the amount of code that runs.

It also led to the creation of the [React Compiler](https://clojureverse.org/t/cljs-and-the-react-compiler/10774). Which essentially does the same work the `defc` macro did. It has to figure out what was used where and affected what. Still uncertain if this will work for generated CLJS code at all.


# Conclusion

`defc` ticks all the boxes for me. The syntax is concise. Performance is good. Custom Slot Functions are easy to implement without adjustments in the core library. I went with this route so that the analysis done by the macro stays simple. It only tracks which names are used where. It does not try to do any complex AST expansion/analysis/rewrites. The data flow is clear and only travels one way. It may start at 0 or later. It may skip intermediate steps.

It is not perfect, but so far I haven't come across anything I like more. I have tried to write "smarter" macros and failed miserably. This at least is predictable and automatically enforces the proper rules just from the syntax alone.