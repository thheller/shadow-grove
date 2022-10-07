# Data and where it comes from

One very key aspect of shadow-grove is that data lives in a normalized DB (just a map), so that we can query and efficiently track what was read and written. This makes precise updates possible and working with data straightforward, since you work with it like any other CLJS map.

However, one open question is where this data comes from in the first place. In addition to the "where" we also need to answer "when". What is the best time to create/load/derive data?

# Example

To make this more concrete lets suppose you build a simple shop-type "app". You have a product listing and the user clicks a specific product which you then want to show a detailed "page" for. At the point of the click you do not have all data available to fully show the product page. What do you do?

My current thinking is that the event should trigger all necessary steps and load all "missing" data directly. So in an actual app this could look something like this

```clojure
;; view parts
(defc ui-product-detail [ident]
  (bind data (sg/query-ident ident))
  (render
    ...))

(defc ui-product-list-item [ident]
  (bind data (sg/query-ident ident))
  (render
    (<< [:div {:on-click {:e ::show-product! :product ident}}
         ...])))

(defc ui-root []
  (bind {:keys [current-product products]}
    (sg/query-root [:current-product :products]))

  (render
    (if current-product
      (ui-product-detail current-product)
      ;; render product listing, not a component to make example shorter
      (sg/keyed-seq products identity ui-product-list-item))))


;; data parts
(defn has-detail-data? [env product]
  (contains? (get-in env [:db product]) :detail))

(defn load-detail-data [env product]
  ;; maybe set some loading indicator that is then later cleared
  (sg/queue-fx env :remote-eql
    {:request {:body [{product [:detail]}]}
     :on-success {:e :detail-load-success :product product}}))

(defn show-product
  [env {:keys [product]}]
  (if-not (has-detail-data? env product)
    (load-detail-data env product)
    (assoc-in env [:db :current-product] product)))

(defn detail-load-success
  [env {:keys [product result] :as e}]
  (-> env
      (assoc-in [:db product :detail] (get-in result [product :detail]))
      (assoc-in [:db :current-product] product)
      ;; or just, we now have the data, so it'll do what we want
      (show-product e)))
```

This works fine but has certain problems, which make everything harder than it maybe needs to be.

- What if there are multiple places that want to use `ui-product-detail`? Any modification of the db that may end up rendering this needs to do the logic to load everything first.
- `load-detail-data` needs to know what exactly `ui-product-detail` needs. This is just a simple `:detail` field in this example, but actual apps will have more complicated requirements. Managing them in two places is not ideal.
- It is also kind of repetitive. An abstraction would help here.
- Not to forget about transitions. Sometimes things shouldn't just "switch" they should "transition" visually.

### Prior Art: Fulcro / om.next

In Fulcro (om.next and other JS based things) the idea is that you declare and compose a query at the root. That query is then loaded and in theory provides all the data you need. The components just declare what they need and "magic" handles the rest.

However, for me (subjectively) in practice this often didn't work or just comes with its own set of trade-offs. The query composition is limited and cannot do anything conditional (eg. only render X when Y). Sometimes you may need to introduce a new component just to adjust the query, or use other "workarounds".

It often does work, but I often felt constrained too much. It also doesn't cover many things you might want to do when you are performance sensitive. What if you composed "root" query loads a lot of data? Suppose you load 3 different "branches". I want to start rendering when the first branch is loaded, so I can show something to the user earlier. Why delay showing the important "product listing" while still waiting some not-so-important "sidebar" data? Since parents pass the query data "down" to the children the "precise and efficient updates" part also becomes harder, not impossible but harder. Rendering from the root is not desirable and too expensive.

## Derived Data / Component Local State

Sometimes, you just may want some temporary UI-specific data. You create it when a component mounts and throw it away when it unmounts. My current strategy is again to handle it the event handler, but it moves the code away from the component which makes harder to keep a coherent picture about what is happening in your head.

## Data Retention and GC

Often also there situations where you are "done" with some data and no longer need it. There should be some way, ideally automatically, that cleans up in idle periods. I see this in the shadow-cljs UI. Sometimes you may end up with thousands of Inspect taps, often the vast majority of them aren't even reachable in the UI anymore and should no longer be in the db either. Extra DB stuff doesn't hurt too much, but it may lead to extensive memory use if not careful.

# Solutions?

TBD