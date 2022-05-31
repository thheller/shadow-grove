# Composability 

One major goal the arborist protocols are designed for is that everything composes effortlessly together. Let me use an example to show what I mean by that.

Suppose there is a button in your UI that you want to use in several places.
```clojure
(defn ui-fancy-button [ev label]
  (<< [:button {:class "fancy-button" :on-click ev} label]))
```

`:on-click` it should trigger an event and it should use a text `label`. I'm trying to keep this example as simple as possible. You can make buttons much more complicated if you want to.

One might use it like

```clojure
(defn ui-example []
  (<< [:div "My UI"]
      [:div "look at my fancy button: "
       (ui-fancy-button ::click! "Click me!")]))
```

Simple enough. Events are allowed to be keywords or maps with an `:e` keyword.


```clojure
(ui-fancy-button {:e ::click! :some "data"}
  "Click me!")
```

Since events are just data they compose just fine.

The label however is a different story. Naively you might restrict this to be a `string?` only, which on the surface certainly looks reasonable. However, since everything is meant to compose this is also valid.

```clojure
(ui-fancy-button {:e ::click! :some "data"}
  (<< "You should " [:b "Click me!"]))
```

So, now this is no longer just a string but "actual" HTML, yielding the expected DOM structure. (`:on-click` omitted)

```html
<button class="fancy-button">You should <b>Click me!</b></button>
```

So, the fragments compose nicely with each other and can be passed as arguments just fine. Of course this isn't limited to fragments. You could pass in a component, `sg/keyed-seq` and all the other arborist protocol implementations as well.

Since fragments also cover passing multiple children it is never necessary to fall back to CLJS varargs and splicing children in somehow. Each function/component can just take one "child" argument and that covers zero or more actual children being passed.