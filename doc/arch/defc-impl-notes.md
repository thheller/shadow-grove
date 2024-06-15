# defc Implementation Notes

Some notes for myself and other interested parties. None of this is of concern for users of shadow-grove.

Just wanted to write down some notes on the internal structure of components and what I may change about it.

Using a `defc` component as it currently exists in the [shadow-cljs UI codebase](https://github.com/thheller/shadow-cljs/blob/f78a265979c7e1fe6585039c3c6a012a302bdf65/src/main/shadow/cljs/ui/components/inspect.cljs#L424-L473).

```clojure
(defc ui-tap-stream-item [oid {:keys [idx focus]}]
  (bind {:keys [summary] :as object}
    (sg/kv-lookup ::m/object oid))

  (bind runtime
    (sg/kv-lookup ::m/runtime (:runtime-id object)))

  (effect :mount [env]
    (db/maybe-load-summary env object))

  (render
    ...))
```

## Internal View

Put into a table this looks like this:

| Index    | Slot Name | Uses      | Slot Code                                                               |
|----------|-----------|-----------|-------------------------------------------------------------------------|
| `0`      | `idx`     | `#{arg1}` | `(get arg1 :idx)`                                                       |
| `1`      | `focus`   | `#{arg1}` | `(get arg1 :focus)`                                                     |
| `2`      | `object`  | `#{arg0}` | `(sg/kv-lookup ::m/object oid)`                                         |
| `3`      | `summary` | `#{2}`    | `(get object :summary)`                                                 |
| `4`      | `runtime` | `#{2}`    | `(sg/kv-lookup ::m/runtime (:runtime-id object))`                       |
| `5`      | `_`       | `#{2}`    | `(sg/slot-effect :mount (fn [env] (db/maybe-load-summary env object)))` |
| `render` |           | `#{...}`  | `...`                                                                   |

The thing of note here is that `effect` currently uses a Slot. I did this because Slots are flexible enough to do this, but also technically don't need to be slots as they never have any actually usable return value. Slots already encapsulate all the required functionality, since `(effect :auto ...)` needs to run wherever the used bindings change. I'm uncertain whether it would be worth moving this out of Slots now that macros support `(effect ...)` directly. Previously there was only bind, but that looked ugly from the user's perspective.

## Actual Generated JS

This is going to be a bit verbose, so I'll remove some less relevant bits.

```js
shadow.cljs.ui.components.inspect.ui_tap_stream_item =
  shadow.grove.components.make_component_config(
    "shadow.cljs.ui.components.inspect/ui-tap-stream-item",
    [ shadow.grove.components.make_slot_config( 0, 0,
        function (comp56407) {
          comp56407 = shadow.grove.components.get_arg(comp56407, 1);
          return cljs.core.get.cljs$core$IFn$_invoke$arity$2(
            comp56407,
            cljs$cst$253$idx
          ); } ),
      shadow.grove.components.make_slot_config( 0, 0,
        function (comp56407) {
          comp56407 = shadow.grove.components.get_arg(comp56407, 1);
          return cljs.core.get.cljs$core$IFn$_invoke$arity$2(
            comp56407,
            cljs$cst$697$focus
          ); } ),
      shadow.grove.components.make_slot_config( 0, 56,
        function (comp56407) {
          comp56407 = shadow.grove.components.get_arg(comp56407, 0);
          return shadow.grove.kv_lookup.cljs$core$IFn$_invoke$arity$2(
            cljs$cst$558$shadow_DOT_cljs_SLASH_object,
            comp56407
          ); } ),
      shadow.grove.components.make_slot_config( 4, 0,
        function (comp56407) {
          comp56407 = shadow.grove.components.get_slot_value(comp56407, 2);
          return cljs.core.get.cljs$core$IFn$_invoke$arity$2(
            comp56407,
            cljs$cst$557$summary
          ); } ),
      shadow.grove.components.make_slot_config( 4, 0,
        function (comp56407) {
          comp56407 = shadow.grove.components.get_slot_value(comp56407, 2);
          return shadow.grove.kv_lookup.cljs$core$IFn$_invoke$arity$2(
            cljs$cst$502$shadow_DOT_cljs_SLASH_runtime,
            cljs$cst$505$runtime_id.cljs$core$IFn$_invoke$arity$1(comp56407)
          ); } ),
      shadow.grove.components.make_slot_config( 4, 0,
        function (comp56407) {
          var object = shadow.grove.components.get_slot_value(comp56407, 2);
          return shadow.grove.components.slot_effect(
            cljs$cst$283$mount,
            function (env) {
              return shadow.cljs.ui.db.inspect.maybe_load_summary(env, object);
            } ); } ),
    ],
    63,
    cljs.core.PersistentArrayMap.EMPTY,
    function thisFunctionDirtyChecksTheArguments(comp56407, old56408, new56409) {
      shadow.grove.components.check_args_BANG_(comp56407, new56409, 2);
      cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2(
        old56408.cljs$core$IIndexed$_nth$arity$2(null, 0),
        new56409.cljs$core$IIndexed$_nth$arity$2(null, 0)
      ) &&
        (shadow.grove.components.arg_triggers_render_BANG_(comp56407, 0),
        shadow.grove.components.arg_triggers_slots_BANG_(comp56407, 0, 4));
      cljs.core.not_EQ_.cljs$core$IFn$_invoke$arity$2(
        old56408.cljs$core$IIndexed$_nth$arity$2(null, 1),
        new56409.cljs$core$IIndexed$_nth$arity$2(null, 1)
      ) && shadow.grove.components.arg_triggers_slots_BANG_(comp56407, 1, 3);
    },
    26,
    function thisIsTheRenderFunction (comp56407) {
      var summary = shadow.grove.components.get_slot_value(comp56407, 3),
        oid = shadow.grove.components.get_arg(comp56407, 0),
        runtime = shadow.grove.components.get_slot_value(comp56407, 4);
      ... 
    },
    cljs.core.PersistentArrayMap.EMPTY
  );
```

`:advanced` shrinks it a lot more but makes it somewhat gibberish to humans, which the computer doesn't care about.

```js
  xG = rA(
    "shadow.cljs.ui.components.inspect/ui-tap-stream-item",
    [
      qA(0, function (a) {
        a = sA(a, 1);
        return w(a, Ts);
      }),
      qA(0, function (a) {
        a = sA(a, 1);
        return w(a, Ou);
      }),
      qA(56, function (a) {
        a = sA(a, 0);
        return YA(Is, a);
      }),
      qA(0, function (a) {
        a = a.get_slot_value(2);
        return w(a, Eu);
      }),
      qA(0, function (a) {
        a = a.get_slot_value(2);
        return GF(sq, fv.g(a));
      }),
      qA(0, function (a) {
        var b = a.get_slot_value(2);
        return Oy($x, function (c) {
          return jB(c, b);
        });
      }),
    ],
    63,
    I,
    function (a, b, c) {
      kk(b.D(null, 0), c.D(null, 0)) &&
        (nG(a), a.mark_dirty_from_args_BANG_(4));
      kk(b.D(null, 1), c.D(null, 1)) && a.mark_dirty_from_args_BANG_(3);
    },
    26,
    function(a) { ... the-render-code ... },
    I);
```

`:advanced` will also remove all whitespace characters. I just pretty printed it, so the structure is at least somewhat comparable to the above `:simple`. `:advanced` also used to remove the component name entirely. I need to check why it no longer does, it should only be relevant in development code. Overall, I'm not too worried about this being too much code. For what it does, it is pretty compact.

Basically `shadow.grove.components.make_component_config` is called with an array representing the Slots, some extra bitmasks, an argument checking function and a render function. `make-component_config` returns a `ComponentConfig` deftype instance. This implements the CLJS `IFn` protocol, making them usable like functions. There is only one component type, used by all components. There is no `class` per component.

The few numbers sprinkled in there, which are the bitmasks (e.g. `2r0100`) and will be used by the implementation to check what work needs to be done. Internally each component maintains a `dirty-slots` integer and if a Slot changes it is updated to `(bit-or dirty-slots (.-affects slot-config))`. Each Slot will check `(bit-and dirty-slots (.-depends-on slot-config))`, to check whether it needs to run. Using bits for efficiency. The first iteration used CLJS sets and was substantially slower.

This currently limits the components to a maximum of 32 slots, since JS numbers can only do 32 bits safely. So far, this hasn't been an issue for me, but others have run into that limit with large numbers of destructured names. This could easily be switched to use a JS bigint instead. I have not yet investigated how that may impact performance. I doubt it does and is likely a no-brainer change. Should it be a performance problem there is the alternate possibility of using a generic `BitSet` implementation that can grow beyond 32 bits. I could also drop the destructuring getting its own slot per name, but it nicely encapsulates the dirty check by using code that already exists.

Each function above only ever receives one argument, which represents the current component. With that each function starts first getting out the locals, it needs to run via `get_slot_value` and `get_arg`. This is essentially an array lookup, so I'm not worried about each slot doing that on its own. This looks a bit weird above since that is `:simple` optimized code and the Closure Compiler is re-using names to save space.

One thing I'm uncertain about is whether this whole function-per-slot abstraction is worth doing. The component runs a `loop` incrementing the index representing the slot it is currently processing. Then each iteration checks whether the current index is dirty and needs to run. This seemed like the most straightforward thing to do and works well.

A nagging thought is whether it would be worth to unroll this loop and instead only pass a single function that the component calls. JS is better optimizing single functions, so ultimately that may lead to better performance. Unsure how much difference it makes in the amount of code generated. It could potentially also be better to use a `class` per component that just inherits from the base component. Overall, this hasn't interested me enough to try. There are bigger fish to fry first.





