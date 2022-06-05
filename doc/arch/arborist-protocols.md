 # shadow.arborist.protocols
 
This is a technical summary of what these low-level protocols are meant to achieve. For most this should be considered and implementation detail and **end users will not interact with these directly**. The shadow.arborist core functions will call these. Implementations of these protocols may call them. End users must not.

Any implementor is expected to be familiar with these concepts as well as having a strong understanding of how the DOM works.
 
```clojure
(defprotocol IManaged
  (^boolean supports? [this next])
  (dom-sync! [this next])

  (dom-insert [this parent anchor])
  (dom-first [this])

  (dom-entered! [this])

  (destroy! [this ^boolean dom-remove?]))
```

```clojure
(defprotocol IConstruct
  :extend-via-metadata true
  (as-managed [this env]))
```


# Overall Concept

The goal for these protocols is creating an abstraction around managing the lifecycle (create, update, destroy) of one or more DOM nodes.

They are the basis for forming the arborist tree, which in turn manages DOM nodes. Their structure however may differ and one arborist tree may indeed cover multiple DOM roots  (eg. via "Portals").

Fundamentally these two protocols create a similar model to the common "Virtual DOM" other libraries use. Although there is no expectation to have a 1:1 mapping for every DOM element. In fact many `IManaged` implementations will represent multiple DOM nodes.

First a lightweight "blueprint" or "constructable" element is created. Basically meant to describe what the DOM should look like. It in itself should be though of as opaque but data-like structures. It should be safe to pass around via arguments and cheap to create and throw away.

These elements are then turned into managed elements on first render, and then later used to update them.

*FIXME: these really needs proper names.*

## The Tree Environment: env

`env` is just a clojure map representing the environment passed down from the root. Managed elements may modify this map before passing it down to their children. This is used to pass information down the entire tree without the end user having to pass this around manually. Anything can be passed down here but the immutable nature is crucial here. This is not meant to pass down changeable data and is not a source of "updates". The goal for this is also to avoid all global state, so anything needing access to anything should use the `env` to get it. As such this is similar in concept to "Context" from other libraries but more restricted in its use (no update).

To make this document easier to follow I'm just going to use an empty map in examples for now.

# DOM and DOM-only

This is only a minimal abstraction over the actual [DOM](https://developer.mozilla.org/en-US/docs/Web/API/Document_Object_Model/Introduction). Direct access and manipulation is allowed and encouraged, as long as the rules are observed. Anything working with the DOM can be integrated and used. The overhead to get to the actual DOM should be as minimal as possible.

## Non-Goal: General Purpose

Just to emphasize this again: This is not a general purpose abstraction like `react` aims to be. There is no intent for any of this to work outside a DOM environment. There will be no arborist-native.

## Non-Goal: Server Rendering

As such these protocols are not intended to be used for any kind of server rendering. Technically something like [jsdom](https://github.com/jsdom/jsdom) could be used to create a fake DOM and turn that into a String. This might be useful for testing purposes but should not be used for anything beyond that. The assumption of a live DOM that can be manipulated over time just doesn't fit into a server rendered model and is better served by a separate abstraction.

Although many might consider this an essential feature nowadays I don't believe it is. A Server renderer has very different concerns and can also be substantially simpler since it doesn't have to deal with an update cycle. I'll probably cover my ideas for dealing with this at a later date.

# IConstruct

```clojure
(defprotocol IConstruct
  :extend-via-metadata true
  (as-managed [this env]))
```

For every render first a "constructable" element is created. This can be anything implementing the `IConstruct` protocol. Its purpose is to represent the "blueprint" we are going to use to construct the actual "managed" element later, or update an existing "managed" element if supported.

A `"hello"` String can be such a constructable element. It in itself is just data and somehow we need to make it represent a DOM node.

Every constructable element is also expected to be comparable to other instances of itself but more on that later.


## Blueprint -> Managed

```clojure
(def x (as-managed "hello" {}))
```

Once constructed every managed element will be part of a controlled lifecycle and can be asked to update or be destroyed. This basically wraps all mutable DOM operations that may occur during its lifetime.

During construction each managed element is also meant to construct managed child elements it may want to create. The expectation here is to create everything (including DOM nodes) as eagerly as possible. If something cannot be created eagerly and needs to go async a visual layout shift may occur which are undesirable.

Although there are mechanism for dealing with this described in later documents the decision here was to stick with a proper synchronous API. Going async for even basic DOM interop would make everything substantially more complicated.


### dom-insert

Every Managed instance must have at least one DOM node it can insert into the DOM after creation. If the desired DOM node was not yet constructable it may create an empty `document.createText("")` or `document.createComment("foo")` node to insert as a placeholder instead. These aren't visible and can later be replaced if necessary. In fact many implementations will use such marker nodes to make their own life easier. As far as I can tell there is no noticeable performance concerns with these.

```clojure
;; example for just appending "hello" to the document.body
(dom-insert x js/document.body nil)
```

This operation mirrors the DOM [parent.insertBefore(newNode, anchor)](https://developer.mozilla.org/en-US/docs/Web/API/Node/insertBefore) operation.

- `parent` is the actual DOM parent element.
- `anchor` may be the actual DOM node the new node is supposed to be inserted in front of. This might be `nil` in which case the operation behaves identical to a `parent.appendChild(newNode)`.

Each managed element is expected to also call `dom-insert` for every managed child elements it may have. Depending on *where* the insert shall occur it may provide its own `parent` and `anchor` arguments.

Implementations MUST NOT hold on to the `parent` or `anchor` arguments. Doing so will result in undefined and broken behavior. The DOM `parentElement` can always be obtained from an `element` managed by the implementation. `anchor` can possibly be obtained later via `dom-first` if relevant.

Any implementation must accept `dom-insert` to be called multiple times. A parent collection element may decide to re-order elements and may do so by inserting them in a different order. Destroying and re-creating would be inefficient, so instead `dom-insert` may be called multiple times. Implementations should limit the work done here to only actually doing the minimum DOM operations needed. Other work should be done elsewhere.

### dom-entered!

Note that `as-managed` and `dom-insert` do not imply that the DOM element is part of the `document`. In fact, it very often will not be. There must be no assumption of this being so until the `dom-entered!` protocol method is called.

If the implementation needs to perform any DOM access on its own DOM elements (eg. measuring styles) it should do so in this callback and not before.

```
(dom-entered! x)
```

If the implementation doesn't need to do anything this can be a noop. Each implementation however is expected to also call this for every managed child elements it maintains. Or may create at a later time. 

It is recommended for each element to also keep track of whether it is in the DOM or not. This can also be checked with the help of a managed DOM element (via [Node.isConnected](https://developer.mozilla.org/en-US/docs/Web/API/Node/isConnected)) in case the element does not wish to maintain this state itself.

`IManaged` implementations should make no assumption about when `dom-entered!` is going to be called. It is perfectly valid for an element to be constructed and get destroyed before ever making it into the actual DOM element. Most often it will be called some time after `dom-insert` though.

After `dom-entered!` the managed element is expected to be part of the live `document`. Each element may modify its managed DOM nodes however it chooses to. It should however not touch other nodes and only interact with DOM elements of other elements via the `IManaged` protocol methods.

## supports? & dom-sync!

These are part of a full update cycle for a managed node. Their purpose is to synchronize the actual DOM tree with the new constructable element received.

This happens in three steps.

- First the `next` blueprint is "rendered". All implementation can do this at any time they want to update the DOM in some way.
- Second the managed element is asked whether it supports this new blueprint

```clojure
(supports? [this next])

;; sample implementation for a String

(deftype ManagedText [...]
  ... 
  p/IManaged
  (supports? [this next]
    (string? next)))
```

The is meant to simplify development of Managed nodes, since they should only need to handle updating compatible blueprints and let arborist itself manage the replacement procedures.

If the update is supported the `dom-sync!` method is called with `next` being the new desired "blueprint"

```clojure
;; first create a managed element for the "hello" string
(def x (as-managed "hello" env))
;; it supports changing a string to another string
(supports? x "world") ;; => true
;; then tell it to update to the "world" string instead
(dom-sync! x "world")
```

How the DOM is updated is entirely up to the implementation but the expectation is that this is done in a synchronous manner and properly destroying obsolete elements and upgrading new elements (ie. `as-managed`) and properly inserting them into the DOM.

### dom-first

The less-commonly used but required protocol method is `dom-first`. This is used to ask a managed element for its first current DOM element.

The only context this should be used for is a `dom-insert` call. If a collection for example wants to re-order and needs to insert a managed element before another. It can only do so by having the proper `anchor` element which is the returned `dom-first` node. This MUST not be `nil` as that would represent a managed element that forgot its place in the DOM.

Implementations asking for `dom-first` must not store this result and instead must use it immediately or forget it and ask again later. Each managed element is allowed to change what is returned here over time but must always return the current live DOM node properly.

### destroy!

`(destroy! [this ^boolean dom-remove?])` represents the controlled destruction of a managed element. The `dom-remove?` boolean signals the implementation whether it needs to remove DOM elements itself or not.

One implementation may remove a DOM element which it had rendered its children into. As such it can pass a `false` here since the children don't need to remove any DOM nodes anymore since they are already disconnected from the live `document`.

Any implementation should however properly clean up after itself and release any resources it may have in use. The DOM cleanup being optional is just there for performance reasons.

A destroyed element must be discarded and no further use is valid.

