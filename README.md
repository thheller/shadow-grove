# shadow-grove

[![Clojars Project](https://img.shields.io/clojars/v/com.thheller/shadow-grove.svg)](https://clojars.org/com.thheller/shadow-grove)

`shadow-grove` is a combination of pieces required to build performant DOM-driven user interfaces, scaling from small to very large. The goal is to have something written in ClojureScript with no extra dependencies and excellent performance out of the box.

The core pieces are

- `shadow.arborist`: Core Abstraction to create and update DOM segments
- `shadow.grove.db`: Normalized simplistic DB, using just CLJS maps
- `shadow.grove.events`: Event system to handle changes to the system
- `shadow.grove.components`: The component system providing the basis to connect the pieces: dispatching events, reading from the DB (via EQL queries) and updating the DOM.

### Prior Art

Pretty much everything here borrows ideas from other libraries in the JS/CLJS space.

- The DOM related parts borrow ideas from JS systems such as `react`, `svelte`, `vue`.
- The event system is pretty similar to [re-frame](https://github.com/day8/re-frame) but uses event maps instead of vectors. Subscriptions are also replaced by EQL queries.
- The normalized db and EQL query concepts were inspired by [fulcro](https://github.com/fulcrologic/fulcro)

However, all things here are written from scratch. Mostly since we are not using `react` which the other CLJS libs require.

### Current Status

`shadow.grove` is far from finished but usable. I have been using this for a few years now. Documentation is still in an terrible state though.

The [shadow-cljs UI](https://github.com/thheller/shadow-cljs/tree/master/src/main/shadow/cljs/ui) is sort of a reference application for all of this. You can experience it live at http://localhost:9630 if you have shadow-cljs running locally. You can also use the [grove-todo](https://github.com/thheller/grove-todo) example.

Other `react` based CLJS libraries have far more features that `shadow.grove` is still missing. If you are using a lot of 3rd party `react` components you probably shouldn't be looking at this.

## Quickstart

Using the `shadow.grove` implementation of [TodoMVC](https://todomvc.com/) ([source here](https://github.com/thheller/grove-todo)) as an example for getting started. You can just clone the [template repo](https://github.com/thheller/grove-todo) or copy what you need. Explanations of the different parts will follow.

The core structures in `shadow.grove` are modular so each piece needs to be setup separately. You only initialize what you need when you need it. The minimum we need to create is the database and the runtime holding out our other "state". I recommend creating this in a [dedicated namespace](https://github.com/thheller/grove-todo/blob/main/src/main/todo/ui/env.cljs).

```clojure
(ns todo.ui.env
  (:require
    [shadow.grove.db :as db]
    [shadow.grove.runtime :as rt]
    [todo.model :as-alias m]))

(def schema
  {::m/todo
   {:type :entity
    :primary-key ::m/todo-id
    :attrs {}
    :joins {}}
   })

(defonce data-ref
  (-> {::m/id-seq 0
       ::m/editing nil}
      (db/configure schema)
      (atom)))

(defonce rt-ref
  (-> {}
      (rt/prepare data-ref :todo)))
```

This namespace can then potentially be used by everything else that may require access to `env/rt-ref`. It basically just holds all our application state.

In our main namespace we then initialize everything and render our views.

```clojure
(ns todo.ui
  (:require
    [shadow.grove :as sg]
    [shadow.grove.local :as local]
    [shadow.grove.history :as history]
    [todo.ui.env :as env]
    [todo.ui.views :as views]
    [todo.ui.db]))

(defonce root-el
  (js/document.getElementById "app"))

(defn render []
  (sg/render env/rt-ref root-el
    (views/ui-root)))

(defn init []
  ;; useful for debugging until there are actual tools for this
  (when ^boolean js/goog.DEBUG
    (swap! env/rt-ref assoc :shadow.grove.events/tx-reporter
      (fn [{:keys [event] :as report}]
        ;; alternatively use tap> and the shadow-cljs UI
        (js/console.log (:e event) event report))))

  (local/init! env/rt-ref)

  (history/init! env/rt-ref
    {:use-fragment true
     :start-token "/all"})

  (render))

(defn ^:dev/after-load reload! []
  (render))
```

The [views namespace](https://github.com/thheller/grove-todo/blob/main/src/main/todo/ui/views.cljs) contains all of our component code. Going over all of them here would get too long. Feel free to explore them though.

The significant two pieces are the `defc` and `<<` macros.

- `<<` creates arborist fragments, which represent one or more elements for our tree.
- `defc` creates components which manage data and events

## Fragments

Fragments use the hiccup notation but are compiled down to JS code. You'll be using these a lot to represent DOM elements.

```clojure
(<< [:h1 "Hello World"])

(defn snippet [foo bar]
  (<< [:div foo]
      [:div bar]))

(<< [:div "before"]
    (some-component 1 2 3)
    [:div "after"])
```

More on fragments here (TBD).

## Components

Components are responsible for managing and rendering data. They also are the default target for events.

```clojure
(defc ui-example [arg]
  (bind data
    (sg/query-root
      [:foo
       :bar]))

  (event ::foo! [env ev e]
    (sg/dispatch-up! env (assoc ev :data-taken-from-e ...)))
  
  (render
    (<< [:div arg]
        [:div {:on-click ::foo!} "click foo"]
        [:div (pr-str data)])))
```

- `bind` represents a hook that injects data into the component. The signature is `(bind <binding-form> &body-creating-hook)`.
- `event` creates an event handler. Events in fragments are declared as just maps of data. `:on-click ::foo!` is short but equivalent to `:on-click {:e ::foo!}`. You may add additional key/value pairs as needed. All events are by default dispatched to the nearest component first and then bubble up the component tree.
- `render` ultimately takes the data hooks injected and render it. The return value is expected to implement the `shadow.arborist` protocols (eg. Fragments).

More on components here (TBD).

## Events

The event subsystem takes the event maps and effects changes to our runtime/db.

The event handler inside Components are really just meant to give you a point to extract DOM related data from its 3rd (ie. `e` above) argument, which is the native DOM event. They are not actually allowed to modify our DB otherwise. Once the data is extracted they can just pass the event along to be processed by the actual handler.

This isn't always necessary, so most of the time events will just bubble up to the runtime and the `(event ...)` handler in the component can be omitted.

You can register an event handler via the `shadow.grove.events/reg-event` function.

```clojure
(ev/reg-event env/rt-ref ::foo!
  (fn [tx-env ev]
    (js/console.log ::foo! env ev)
    env))
```

`tx-env` represents the transaction environment. It does contain a `:db` key which represents our database. It can be modified using the usual clojure functions (eg. `assoc`, `assoc-in` `update`, `update-in`, etc).

The event handlers are expected to return the modified `tx-env`.

More on events here (TBD).

## Database

The last essential piece is our normalized database, handled by a flat persistent map. Basically a key/value store that you `assoc`, `dissoc` and `update` values in. It is normalized to we avoid duplication of UI data and to keep access efficient.

The TodoMVC example from above doesn't really need a schema so let's assume we have a shop of some kind listing products. Each product has a manufacturer.

```clojure
{:products
 [{:product-id 1
   :product-name "Foo"
   :manufacturer {:manufacturer-id 1
                  :manufacturer-name "ACME"}}
  {:product-id 2
   :product-name "Bar"
   :manufacturer {:manufacturer-id 1
                  :manufacturer-name "ACME"}}]}
```

If we were to store this shape in our database we would end up with duplicated data since both products are from the same manufacturer. This becomes messy and hard to work with over time. We also want more efficient access to data without having to traverse deeply into some nested datastructure.

Normalizing this db, we instead end up with

```clojure
{:products
 [[:product 1]
  [:product 2]]
 
 [:product 1]
 {:product-id 1
  :product-name "Foo"
  :manufacturer [:manufacturer 1]}

 [:product 2]
 {:product-id 2
  :product-name "Bar"
  :manufacturer [:manufacturer 1]}
 
 [:manufacturer 1]
 {:manufacturer-id 1
  :manufacturer-name "ACME"}}
```

Each entity is represented by an ident, using a vector here. It is used as a reference and can be used to get the actual value from the DB. This is common in EQL like systems (eg. fulcro, om.next, pathom). Each ident is composed of `[entity-type id]`. You may also think of this as `[table-name id]` when using a SQL database in the backend. Note that having multiple entity types is entirely optional, but convenient depending on the shape of your actual data requirements.

More on the database here (TBD).

## Other useful pieces

- Routing
- Virtual Lists
- Suspense

TBD.

