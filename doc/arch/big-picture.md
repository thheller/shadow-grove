# How it all fits together

So far most posts have only covered certain aspects such as the core arborist protocol, components, the normalized db, the scheduler and so on. While each piece individually is interesting they all need to work together to achieve the bigger picture.

Everything is done so that each piece can work together as efficiently as possible while still providing a reasonable developer experience and a good UX (basically measured in performance).

I'll try to explain everything based on the common TodoMVC example. Using a data layout such as

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

We follow the "props flow down, events bubble up" rendering model (similar to `react`). So one naive way to do all this would just be calling `(ui-root data)`. Pass in all data at the root and go from there. This quickly becomes a nightmare to maintain and also very inefficient since any update will need to be re-rendered from the root completely. No precise targeted updates possible.

`om` tried to optimize this approach using `cursors`. Which alleviates some of the pain but also makes other things much harder. You really want the option to gain access to all data everywhere. You UI may not match your data layout exactly so we need to decouple this.

## Normalizing the DB

Since the core of all of this is the data we need to bring this into a somewhat reasonable shape. It can be argued that the above is already the best shape. In some way it is. From the library perspective this however has certain issues. Say we want to mark `:todo-id 3` as completed. We first need to somehow find its index in the `:todos` vector, and then update the todo item. In turn, we also update the `:todos` vector itself. When it is time to update the UI we need to find what changed again, essentially checking everything.

So we normalize this data first to remove depth and get everything as flat as reasonable but not flatter.

```clojure
;; data normalized becomes

(def data
  {:todos
   [[:todo 1]
    [:todo 2]
    [:todo 3]]

   [:todo 1]
   {:todo-id 1
    :text "do a"}
   
   [:todo 2]
   {:todo-id 2
    :text "do b"
    :completed? true}
   
   [:todo 3]
   {:todo-id 3
    :text "do c"}})
```

Now we can update `[:todo 3]` directly without having to touch `:todos` at all. In fact `:todos` remains `identical?` and very efficient to check if it needs to update. We avoid the "What The Heck Just Happened?" problem entirely by tracking which parts of the `db` were updated.

## Components

With "props flow down, events bubble up" we could still just pass the entire db at the root. Just by normalizing this would actually be more efficient than before. The burden is still on the developer passing this down manually everywhere though.

Components however provide a controlled way to inject data into the tree. They can manage their lifecycle in the DOM and as such can properly handle changes to the data and update accordingly.

## EQL Queries

Components themselves however don't actually manage any data. They only provide a generic abstraction for hooking into their lifecycle and handling data is left to more specializing implementations.

The default abstraction here is based on EQL (popularized by fulcro, pathom, etc.). It provides a good way for components to express their data needs while also making a reasonable remote interface.

It also happens to provide a way to turn the normalized data back into something tree-like.

```clojure
(defc ui-todo [ident]
  (bind {:keys [text]})
    (sg/query-ident ident)

  (render
    (<< [:li text])))

(defc ui-root []
  (bind {:keys [todos]}
    (sg/query-root [:todos]))
    
  (render
    (<< [:h1 "todos"]
        [:ul
         (sg/keyed-seq todos identity ui-todo)])))

(sg/render ... (ui-root))
```

In this we provide no data via the `ui-root` at all. Instead, it queries the "root" of the database for the `:todos` attribute. They are then rendered as a collection using the `ui-todo` component. Each `ui-todo` will receive the ident of the todo it is supposed to render. It will use this to query the data again from the DB. It doesn't specify the EQL attributes it wants here, which is just short for `(get db ident)` but `(sg/query-ident ident [:text])` would be valid and only provide the `{:text ...}` map instead of the complete one.

What we end up with is 4 mounted queries. The first read the `:todos` key and nothing else. It will only update if `:todos` changes. The other three  just read their ident. So updating `[:todo 3]` will not cause `[:todo 2]` to update.

Queries manage their data needs and signal components when they need to update.

## Handling Events

The above all handles data flowing down the component tree. Everything can access the part of the data they need when they need it. Queries maintain who accessed what and can surgically trigger updates.

Those updates can come from many places but most often they will be triggered by something the user does in the UI -- clicking a button.

Events are expressed as data. Keeping with "props flow down, events bubble up" each component in the path is given a chance to handle it.

Suppose we want to add a button you can click to complete a certain todo.

```clojure
(defc ui-todo [ident]
  (bind {:keys [text]})
    (sg/query-ident ident)

  (render
    (<< [:li text [:button {:on-click ::complete!}]])))
```

We could handle this event directly in the component by declaring an event handler.

```clojure
(defc ui-todo [ident]
  (bind {:keys [text]})
    (sg/query-ident ident)

  (event ::complete! [env ev e]
    ;; handle complete!
    )
  
  (render
    (<< [:li text
         [:button {:on-click ::complete!} "complete me"]])))
```

However, it is not the job of the component to handle database concerns. It should strictly focus on only updating the DOM. It will already receive updates it may need from the query. So instead we don't declare this event handler in the component and instead just let it bubble up.

`ev` is a event map. `:on-click ::complete!` basically is just short for `:on-click {:e ::complete!}`. They are maps so you can easily provide more data in events. You could provide `:on-click {:e ::complete! :todo ident}` here to the `ev` map will contain the ident of the todo we want to update.

The purpose of these component declared event handlers is essentially to give it a chance to extract data they may need out of the DOM before passing it along. `e` is the actual DOM `click` event.

So in a proper UI we might instead use a checkbox and would want to get the checked state out if the DOM 

```clojure
(defc ui-todo [ident]
  (bind {:keys [text]})
    (sg/query-ident ident)

  (event ::complete! [env ev e]
    (sg/dispatch-up! env
      (assoc ev
        :ident ident
        :checked (.. e -target -checked))))
  
  (render
    (<< [:li
         [:label
          [:input {:type "checkbox" :on-change ::complete!}]
          text]])))
```

So here we get the `:checked` boolean from the target DOM element via `e`. Since we handled this event in the component it would stop there. However via `dispatch-up!` we let the event continue up the tree with some extra data we added. Whether you add `ident` there or on the `:on-click` definition doesn't really matter much and is up to personal taste.

The goal should be that events speak for themselves without any further context needed.

## Event Subsystem

Once the event is done traversing the Component tree and reaches the top unhandled (or re-dispatched) the actual useful handling begins. At this point the DOM event is dropped. The event is pure data and could just be passed to a server and handled there.

The event subsystem is very similar to `re-frame`. Events are maps of data instead of vectors though.

When the event is handled by the root first a transaction is started. The event handler will receive two arguments, first the `tx-env` and second the `ev` event map.

Event handlers can be registered by `(ev/reg-event rt-ref ::complete! (fn [tx-env ev] ...))` or via the metadata tools if setup properly.

```clojure
(defn complete!
  {::ev/handle ::complete!}
  [tx-env ev]
  ...)
```

As far as event handling is concerned these are identical. I just happen to like the metadata approach since it makes these kinds of functions easily callable from the REPL and much more composable as well.

So, the `tx-env` argument will have a `:db` attribute which contains a "transacted" db instance. Basically just the regular clojure map with some added modification tracking. You work with it like any other regular clojure map (eg. `assoc`, `update`, `dissoc`) but at the end of the transaction the system cheaply recorded what was actually done.

As a general rule all event handlers must return a potentially updated `tx-env`. In a `re-frame` system you would return a new map with a updated `:db`. By returning `tx-env` however it becomes easier to compose events, since you always pass around the entire context.

So a simple event handle could just assoc what we need into the database.

```clojure
(defn complete!
  {::ev/handle ::complete!}
  [tx-env {:keys [checked ident] :as ev}]
  (assoc-in tx-env [:db ident :completed?] checked))
```

Once the event completes, the transaction sees that `ident` was updated. It'll then check which queries used that ident, when rendering, and signal that they need to be updated.

The scheduler coordinates that everything updates in the proper order and any potential changes are propagated to the actual live DOM.

TBD:
- history/routing
- fx/remote
- suspense
- keyboard
