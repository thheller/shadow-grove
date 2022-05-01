# Collections

As previously described the core Arborist protocol only covers how elements are created, updated and destroyed. There was no mention how collections of elements are handled. The core implementation indeed doesn't cover collections of elements at all. Instead this is left to specialized implementations of the core Arborist protocol. All other implementations as such only handle a single node in the tree and they look just like any other node.

Other react-like implementations usually only have one algorithm for dealing with collections. So end users create an array of elements and the library handles dealing with them in a uniform way. As far as I can tell there is no way to teach these libraries about new algos.

In Arborist there are 2 core implementations and a couple more specialized ones for certain situations.


## simple-seq

`simple-seq` is a very basic implementation rendering elements in order. It makes no attempts to minimize the DOM operations in any way. This is by far the fastest method of rendering a collection of elements that very rarely change.

Say you want to get this piece of DOM from a simple `["a" "b" "c"]` vector.

```html
<ul>
  <li>a</li>
  <li>b</li>
  <li>c</li>
</ul>
```
With `simple-seq` that becomes
```clojure
(<< [:ul
     (sg/simple-seq ["a" "b" "c"]
       (fn [item]
         (<< [:li item])))])
```

Append only collections can also be very efficient using this but anything that changes the order or deletes items at the start or middle will end up doing a lot more DOM operation than its more optimized variant the `keyed-seq`.

## keyed-seq

This is basically the more commonly key-based algorithm many other implemenations such as react use. Each element in the collection needs to supply a key and that key is used to re-order DOM elements instead of rendering over them. When Element #5 was moved to #2 the actual DOM element is just moved, instead of rendering the contents of #5 into what was previously #2.

However instead of the developer providing elements with a key, the `keyed-seq` instead will just take the regular collection and a key-fn to it can construct the necessary keys itself. It also happens to safe one iteration over all the elements, which saves a bit of time.

Collections such as `["a" "b" "c"]` that don't have a natural key are probably better handled by `simple-seq`. However many collections you may end up working with might have a natural key already, so we just use them.

```clojure
(def data
  [{:id 1 :text "a"}
   {:id 2 :text "b"}
   {:id 3 :text "c"}])

(<< [:ul
     (sg/keyed-seq data :id
       (fn [item]
         (<< [:li (:text item)])))])
```

`keyed-seq` here takes the additional `:id` arguments which in this case is the `key-fn`. Since keywords implement the core clojure IFn protocol they can just be called as a function, so essentially this will extract the `:id` from each item in the collection and use that for `keyed-seq` purposes. And function taking one argument (the collection item) is valid here. `identity` is fine too.

Note that the above example is misleading. `data` is fixed and cannot change, so even though the collection has a natural key using `simple-seq` here would still be more efficient. Just imagine for a sec that `data` isn't actually fixed and may be changing while visible on screen.

## simple-seq vs keyed-seq

It is absolutely fine to only use `simple-seq` and only used `keyeq-seq` to optimize certain places.

Whether the overhead of `keyed-seq` is actually worth it largely depends on how often the collection is actually modified. The more items are added, removed or re-ordered the more relevant it becomes.

## reagent/react comparison

In react-based libraries such as reagent very commonly `for` or `map` are used to render collections.

```clojure
;; grove
(<< [:ul
     (sg/simple-seq ["a" "b" "c"]
       (fn [item]
         (<< [:li item])))])

;; reagent
[:ul
 (for [item ["a" "b" "c"]]
   [:li item])]
```

While this looks a little shorter syntax wise this has a couple of problems. First we really can't have laziness here at all. All collections must be forced in the render phase. If react for example would delay forcing this collection, maybe due to concurrent mode, things will get hairy very quickly.

Secondly react will yell at you since no key is provided. So often you need to invent a key here. I have seen apps that either just use the `item` itself, using the index together with `map-indexed` or something worse such as `(random-uuid)`. These defeat the purpose of keys completely and nullify all they are meant to optimize. I have also seen the alternative of using `into`, such as

```clojure
(into [:ul]
  (for [item ["a" "b" "c"]]
    [:li item]))
```

This is reasonable since it makes react happy. As far as it is concerned it is no longer seeing a collection, just some elements. However, given how reagent works this is much less efficient and leads to many more iterations of the collection than necessary.

# Conclusion

I think `simple-seq` and `keyed-seq` provide a reasonable alternative that still look friendly enough as to not miss `for` too much. An alternative macro might be useful.

The burden is on the developer to pick the best variant but using either is fine for most cases. This is fine and also leaves the door open for more specialized implementations such as virtual lists. Or maybe collections that can be sorted via drag&drop. They can also do what is best for them and can re-use the existing implementations or just to something entirely custom.