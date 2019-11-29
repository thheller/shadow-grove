## Thoughts on Fragments

Fragments are "optimized" Hiccup so that it doesn't have to allocate the entire structure each render and can also skip over the diffing as much as possible.


```
(defn ui-fragment [v]
  (<< [:div.card
       [:div.card-title "title"]
       [:div.card-body v]
       (when v
         (<< [:div.card-footer
              [:div.card-actions
               [:button "ok" v]
               [:button "cancel"]]]))]))
```

I kinda like `<<` but `<>` or `html` would also work. Naming is hard.


```
(defn ui-hiccup [v]
  [:div.card
   [:div.card-title "title"]
   [:div.card-body v]
    (when v
      [:div.card-footer
       [:div.card-actions
       [:button "ok" v]
       [:button "cancel"]]])])
```

"Interpreted" hiccup won't be enabled by default but it is easy to support. Just 2-10x slower for simple example and lots more for complex examples using actual maps as props. It just has to allocate too much and diff too much in each render. Same as React. With the fragment macro it can detect most of it at compile time and emit optimized create functions and update functions that skip over the "static" things. Similar to Svelte.

The question is how smart should be macro be. I prefer it to be kinda dumb in that it only analyzes vectors and as soon as it encounters anything else it backs off and treats everything as normal code. In the fragment example however that means then `(when v ...)` stops the fragment processing so the body needs to use `<<` again. For me this is natural already but may confuse people used to Hiccup/Reagent. With a good runtime error this should be easy enough to adapt to.

There could be a "strict" variant of the fragment macro that only allows a basic subset of clojure code inside the fragment itself. So that it doesn't allow most forms but knows how to process `if` and `when` and maybe `for` or so. Similar to other directives in vue/svelte/etc. So that fragments don't allow ALL clojure code since that is too hard to actually analyze at compile time. We also need `<<` anyways when passing fragments as args and so on. Easy to detect `[:div {:attr (some-code) ...]` not so easy to detect

```
[:div
  (some-code-that-has-a-nested-fragment
    [:div ...])]
```


## Components in Fragments

Hiccup and JSX have special syntax for Components too look like regular DOM elements. That is not a pattern I want to repeat. For one there should be no difference between a regular function call (returning anything renderable) and a component (stateful, with lifecycle).

```
(<< [:div "before" [some-component foo] "after"])
```

This is ambigious to parse since we can't know if `foo` is a map of props or something renderable like a string.

```
(<< [:div "before" (some-component foo) "after"])
```

works just as well and makes it clearer that hiccup notation is for DOM elements only and everything else is actual "code".

The only area where allowing dynamic "tags" is interesting is for passing nested elements.

```
[:div
  [some-component {:static "props}
    [:div ...]]]
```

React doesn't support this at all and requires passing functions. I think that is fine but makes diffing problematic again. I actually prefer the web components "slot" method that could be easily adapted.

```
[:div
 [:> (some-component {:static "props"})
  [:div ...]]]
```

`:>` for components and nested elements for slot content where the component itself just leaves a marker where that content should go similar to web components.

```
(defc some-components [props]
  []
  (<< [:div "component-content" (sc/slot)]))
```

Slots could also be named to allow multiple. Without shadow/light DOM separation however I'm not sure how useful this actually is. Not sure how useful this is but I do like the idea. Maybe it just feels weird because React doesn't have it. I can't think of a situation where I'd use it because of using React too much? It just feels better to pass data to the actual component and let it render stuff however it needs?
