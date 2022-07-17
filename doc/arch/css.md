# shadow.grove.css

**This is unfinished and not even ALPHA status!!!**

I expect this will all change a lot, this is basically just a draft.

A simple way to generate CSS in CLJ(S).

A few caveats right up front: I'm a programmer, I'm not a designer. This is written for programmers, no clue if designers would feel comfortable with this. There is also absolutely no attempt at hiding CSS. To make anything useful with this one is required to be familiar and comfortable with CSS.

## Why?

Writing CSS files manually requires coming up with classnames all over the place and can get very frustrating to organize over time. Constantly context switching between files is no fun either.

Using other systems such as [Tailwind CSS](https://tailwindcss.com/) can be nice, but also have other tradeoffs. Since it all based on a single class string it can get rather long. Using components somewhat addresses this but not enough. Things are also unrecognizable since the entire HTML is littered with huge `<div class="some really long strings that are basically gibberish">`. It's also quite impossible to remember all the names Tailwind uses for this. I still know much more CSS than I do Tailwind. I have used Tailwind for quite a while now, but still wasn't happy.

My previous attempt at CSS-in-CLJS was [shadow.markup](https://github.com/thheller/shadow/wiki/shadow.markup) and it suffered the problem that it had too many names. Every element needed a name. That is painfully annoying over time for really simple elements. It was however really neat sometimes during development since the class told you exactly where it came from. It also used `react` and porting it 1:1 to grove makes no sense.

Many other solutions exists for CLJS of course, but none of them really fit what I'm after. So, taking the lessons learned from prior things I started this.

## Goal

Generate CSS entirely from CLJ(S) sources, with zero code running on the client side to actually generate the CSS. It just outputs a CSS file, that can be combined with others if needed. It should also be usable together with server side generated HTML, so not limited to CLJS only.

## How?

One `css` macro that supports only a subset of Clojure/EDN to define CSS classes. One class per `css` call, with optionally more specific selectors and support for media queries and so on.

For CLJS the `css` macro ONLY returns a reference to the classname it'll generate. A shadow-cljs build hook will then generate the actual CSS.

```clojure
(ns my.app.views
  (:require [shadow.grove :as sg :refer (<< defc css)]))

(def some-class
  (css
    :px-4
    {:color "green"}
    ["&:hover" {:color "red"}]))
```

After macroexpansion this will yield `(def some-class shadow.grove.css.defs.my_app_views__L4_C3)` and nothing else. The build hook will created the supporting "defs" file that for now is just a mapping to the classname used.

So, basically only

```js
goog.provide("shadow.grove.css.defs");
shadow.grove.css.defs.my_app_views__L4_C3 = "my_app_views__L4_C3";
```

This only acts a dictionary lookup basically, so the `css` macro doesn't have to generate classnames directly. This will allow later implementations to emit optimized classnames and do tweaks like Atomic CSS and other things do.

After `:advanced` compilation this ns will also completely disappear and just a string with classnames will remain in the proper places.

`css` can be used anywhere CSS is needed. It does not need to go in a `def` and many times won't, although it is useful for reusable classes.

```clojure
(defc ui-component []
  (bind ...)
  (render
    (<< [:div {:class (css :px-4 {:color "red"} ["&:hover" {:color "green"}])}
         "hello World"])))
```

The actual CSS for this in the first preview is something like

```css
.my_app_views__Lx_Cx {
    padding-bottom: 1rem;
    padding-top: 1rem;
    color: red;
}
.my_app_views__Lx_Cx:hover {
    color: green;
}
```


## The `css` macro

The syntax is a subset of Clojure. There is no dynamic code or interpretation allowed whatsoever. It basically supports these basic constructs and are combined sequentially.

```clojure
(css
  :px-4 :my-2 :text-sm        ;; 1
  {:color "green"}            ;; 2
  :a/variable                 ;; 3
  ["&:hover" {:color "red"}]  ;; 4
  [:ui/lg :px-8 :text-lg])    ;; 5
```

1) Simple Keywords (eg. `:px-4`) referencing pre-defined aliases. This is basically like Tailwind and meant to reduce the amount of code one has to write for very commonly used things. Usually this involves stuff for margins, paddings, etc.
2) Maps of keywords to CSS values. For things that don't have aliases, or just free form definitions. Values should be string or will be converted to strings based on some rules.
3) Namespaced Keywords are user definable aliases. Not actually sure yet how or where they'll be defined.
4) A Vector denotes a new selector tied to the original class. The `&` is used to place the actual classname. This is an easy way to use pseudo classes or even reference nested structures such as `["& > div" :px-4]`]. Media Queries also go here.
5) Aliases are also allowed as the first element for sub selectors. The alias will just be looked up and could be used to reference commonly used media queries. So that example could be equiv to `["@media (min-width: 1024px)" :px-8 :text-lg]`, but much shorter and easier to remember.

There is one more special form that would allow for string concatenations using variables. I'm not sure about this one yet. It may not be necessary. 

That is it. It doesn't allow any logic, symbols and is never interpreted at runtime. The macro will just store the form so some other part of the code can generate the CSS properly.

### Is this enough?

Not sure yet.

Truly dynamic things should be set via style attributes. Also, given that the only output of the macro is basically a string it can be easily combined and reused

```clojure
(def button-base (css :px-4 :font-bold :text-lg))
(def button-normal (str button-base " " (css :color/normal)))
(def button-selected (str button-base " " (css :color/selected)))

(defn ui-button [selected?]
  (<< [:div
       {:class (if selected? button-selected button-normal)}
       "Hello World"]))

;; or just
{:class button-base
 :style/color (if selected? "red" "green")}
```

## Optimization to be done

It is great for Development to have one classname for each `css`. They are easy to find and with source maps may even allow you to "click" directly to their definition. However, this can also get very verbose and may lead to larger than necessary CSS files.

Suppose you have `(css :px-4)` and `(css :px-4 :flex)`, instead of

```css
.some_ns_L1_C3 {
  padding-left: 1rem;
  padding-right: 1rem;
}
.some_other_ns_L5_C2 {
  padding-left: 1rem;
  padding-right: 1rem;
  display: flex;
}
```
It can and should generate a utility class, similar to Tailwind.
```
.px-4 {
  padding-left: 1rem;
  padding-right: 1rem;
}
.flex {
  display: flex;
}
```

It is just important to only emit classes that were actually used, similar to Tailwind JIT.

Given that the macro generates a reference instead of the string directly changing the actual value is easy.

The length of classnames doesn't really matter though. GZIP takes care of that for the most part, but it may still be useful to shorten them in some way. Even just for obfuscation, sometimes it may be undesirable to leak the internal namespace structure.

I expect that the end result will be a mix of utility classes and actual named classes.

The `shadow.arborist` fragment macro may just turn hiccup style things like `[:div.px-4]` into the proper `[:div {:class (css :px-4)}]` call on its own, since it is conveniently short.