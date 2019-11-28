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