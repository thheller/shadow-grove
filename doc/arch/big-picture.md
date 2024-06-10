# How it all fits together

Everything in `shadow-grove` is done so that each piece can work together as efficiently as possible while still providing a reasonable developer experience and a good UX, basically measured in performance. The goal is creating and updating a possibly large tree-like structure. Each Node in the Tree can only have one parent, but as many leaf nodes as needed. Each Node may represent zero or more actual DOM nodes. There is no 1:1 "Virtual DOM" correlation as there would be in a system such as React. The "application root" generally sits at the top, and the tree goes down. It seems like it should be the other way, but this is the more common way to think about things.

The core of everything is data and how it is handled.

I'll try to explain everything based on the common TodoMVC example. A List of Todos that can be toggled completed or not. Expressed as EDN, this might start with layout such as:

```clojure
(def data
  {:todos
   [{:todo-id 1
     :text "do a"}
    {:todo-id 2
     :text "do b"
     :completed? true}
    {:todo-id 3
     :text "do c"}]})
```

We follow the "data flows down, events flow up" rendering model (similar to React). So one naive way to do all this would be just calling `(ui-root data)`. Pass in all data at the root and go from there. This does work and is reasonably fast. However, in my experience, this does not scale to larger apps. Scale as in the performance characteristics, but also from the developer ergonomics having to create a structure that contains all data from the root.

Instead, a few techniques can be used to make that more reasonable at scale.

## Components

Essentially, they take control over their "branch" of the entire tree. They manage their own lifecycle and events. Instead of having one place that handles all possible events, each component handles its subset of those events with the option to pass them further up.

In addition, they may introduce new data into the tree that was not passed in from the root. They can also control which shape this data is in, without other places needing to know that.

Components are created using the `defc` macro, which is covered elsewhere.

## Normalizing The Data

The goal of normalizing data is basically the same as for any other database system (e.g. SQL), which basically comes down to removing duplication and gaining direct access for reads and writes.

For example in the above data example, we might want to toggle the `:completed?` state of `:todo-id 2`. We could do this directly via `(update-in data [:todos 1 :completed?] not)`, but this requires knowing the index you are manipulating. Notice the `1` there. That index may change if a todo is added before that, or if they are re-ordered in any other way. Handling indexes is cumbersome, especially if data is organized in many different shapes.

Instead, we organize the data to be accessible by its unique id, `:todo-id` in this case.

```clojure
(def data
  {:todos
   {1 {:todo-id 1
       :text "do a"}
    2 {:todo-id 2
       :text "do b"
       :completed? true}
    3 {:todo-id 3
       :text "do c"}}})
```

Now, there is a clear path to each individual todo. Regardless of the place they might be rendered in. Grove calls this a table, but the actual implementation is just a regular map, with some extra rules we'll cover elsewhere.

Components can either directly access a path in that data, similar to `(get-in data [:todos 2])`, or still receive the full `data` and get whatever they need in other ways. From the developer's perspective, everything is just regular EDN data.

## Handling Events

We also need to handle updating data in some way. Events are often triggered by some user event, such as clicking a button. They may also happen without user action, such as receiving data from the network.

Events are expressed as data. Component/User events as a general rule will travel up in the tree from their source. Components get the first chance to handle their own events. If they don't, the parent is tried, continuing all the way to the root of the application.

### Component Events

Suppose we want to add a button the user can click to complete a certain todo.

```clojure
(defc ui-todo [todo-id]
  (bind {:keys [text]}
    (sg/kv-lookup :todos todo-id))

  (render
    (<< [:li text
         [:button {:on-click {:e ::complete! :todo-id todo-id}} "complete me"]])))
```

We could handle this event directly in the component by declaring an event handler.

```clojure
(defc ui-todo  [todo-id]
  (bind {:keys [text completed?]}
    (sg/kv-lookup :todos todo-id))

  (event :complete! [env ev e]
    ;; handle complete!
    )
  
  (render
    (<< [:li text
         [:button {:on-click {:e :complete! :todo-id todo-id}} "complete me"]])))
```

However, it is not the job of the component to handle database concerns. It should focus on only updating the DOM. So we don't need to declare this event handler in the component and instead just let it bubble up for this case.

The arguments received by the event handler are

- `env` for the component environment, more on that later
- `ev` is a event map, e.g. `{:e :complete! :todo-id 2}`. `:e` is the reserved keyword to identify the event.
- `e` is the actual DOM `click` event.

The purpose of these component declared event handlers is to give it a chance to extract data they may need out of the DOM before passing it along. In a proper UI, we might instead use a checkbox and would want to get the checked state out if the DOM 

```clojure
(defc ui-todo  [todo-id]
  (bind {:keys [text completed?]}
    (sg/kv-lookup :todos todo-id))

  (event :toggle-complete! [env ev e]
    (sg/dispatch-up! env
      (assoc ev
        :todo-id todo-id
        :checked (.. e -target -checked))))
  
  (render
    (<< [:li
         [:label
          [:input {:type "checkbox" :on-change :toggle-complete!}]
          text]])))
```

`sg/dispatch-up!` is the helper function which will make the event continue its travel up the tree, giving other components a chance to react as well. This also adds the `:todo-id` to the event map, which saves having to do that during the DOM construction. `:on-click :toggle-complete!` is syntax sugar for `:on-click {:e :toggle-complete!}`, the event triggered is still that map. This is optional. Given that the event is just a map, the component could assoc another `:e` so that an internal component event continues its travel as a domain-specific event further up.

### Application Events

Once the event has traversed the Component tree and reaches the top unhandled, or re-dispatched, the actual useful handling begins. Now data can be updated, and after that is done the components will update to render the changed data. Very much like the [re-frame event loop](https://day8.github.io/re-frame/a-loop/#the-data-loop) would.

Event handlers can be registered by the `sg/reg-event` function.

```clojure
(sg/reg-event rt-ref :toggle-complete!
  (fn [tx-env ev]
    ...))
```

When the event is handled by the root first a transaction is started. The event handler fn will receive two arguments, first the `tx-env` and second the `ev` event map. `tx-env` is the map events are going to change and return. It contains all the data managed by grove. Grove organizes data into tables, and using our previous `data` examples `:todos` would be such a table. A simple event handler would just assoc the data we need.

```clojure
(sg/reg-event rt-ref :toggle-complete!
  (fn [tx-env {:keys [todo-id checked] :as ev}]
    (assoc-in tx-env [:todos todo-id :completed?] checked)))
```

Just regular CLJS functions working with data. Nothing special from the developer perspective.

The implementation will actually track what was updated, so it knows that `:todo-id 2` was changed cheaply without having to check all the others. With this information, the runtime can trigger a direct update of the components referencing this todo without having to re-render from the root.

## Where to go from here?

I hope you got a broad overview of how the system works. Each Thing is covered in more detail elsewhere.

- Grove Application, represented by `rt-ref` in the examples above, short for "runtime ref". Basically the `atom` holding all application state.
- Components, via `defc` and their `env`
- Grove KV
- Data Transactions via `reg-event` + `tx-env`.