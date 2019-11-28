## Thoughts on DOM Events

In React all events are handled by functions. Creating these functions in "render" is problematic because the engine can only see that they are different and accordingly must update the DOM to apply a new handler.


```
[:div {:onClick (fn [e] ...)} ...]
```

To make things a bit cleaner in React you'd move them to a hook or class-bound fn so the engine can "see" that fns didn't change and skip the DOM update. But that makes handling these functions a bit harder since without a class we can't have the bound fn and hooks need to manage captured state properly (easy to miss).

```
<div onClick={this.handleClick}>...</div>
```

This makes it impossible to pass arguments to the event handler. It must manage that via state/props or component attributes. Thats not necessarily a bad thing but may require creating multiple event fns that then dispatch to some common implementation.

```
<div onClick={this.handleClickA}>...</div>
<div onClick={this.handleClickB}>...</div>
```


In `shadow.arborist` I want to discourage the inline-fn as much as possible since its the worst case and breaks the "declarative" nature of hiccup completely.

One approach I currently prefer is using a vector and a keyword with additional optional args.

```
[:div {:on-click [::foo! 1 2 3]} ...]
```

If the arguments are completely static the fragment macro detects that and never needs to update the event listener on the actual DOM node.

Args however would still cause an update in each render though.

```
[:div {:on-click [::foo! some-arg]} ...]
```

Not worth worrying about since its cheap enough BUT there may be cases where render can be skipped completely if only `some-arg` changed.

```
(defc ui-example [{:keys [some-arg] :as props}]
  [::foo!
   (fn [env e] (js/console.log e some-arg))]

 (<< [:div {:on-click [::foo!]} ...]))
```

In this the hooks would trigger the update but since the body is not using the changed `some-arg` the render and DOM update is skipped completely. Not sure how common this would be. Probably unlikely.

```
(defc ui-example [{:keys [some-arg] :as props}]
  [foo! (some-hook-creating-event-fn)]

 (<< [:div {:on-click [foo!]} ...]))
```

This is currently also allowed. Not a fan but makes it easier to pass around event handlers to child components and so on.

Overall this should have all the power of inline functions without the "mess". Keyword events also make it a bit easier to move events out of the component into some kind of multi-method or general "transaction" system. The only reason to process events in the components is to extract the required data out of the DOM event `e` (eg. `e.target.value`). 