# GroveKV

`shadow.grove.kv` is the new data model in `shadow-grove`. It replaces the older `shadow.grove.db` and Idents.

**This will break everything. The old implementation is gone entirely.** I do not have the time or energy to maintain two competing data management solutions, and `shadow.grove.kv` fixes many issues `shadow.grove.db` used to have. It should be a straightforward migration and things should be simpler in the end.

## What is KV?

Basic concept of a key-value store, otherwise known as a map. A table is a regular CLJS map.

Grove will however expose a wrapped type instead to facilitate its data management. In `query` operations you'll get an `ObservedData` type, which will record which keys were accessed. In "write/transaction" (e.g. `reg-event` functions) operations you'll receive a `TransactedData` type, which will record which keys where added/removed/updated.

This is an implementation detail, as both of these still act like regular maps, so all operations you'd normally use continue to work.

## Differences

### Idents are gone entirely

Idents were necessary only for one reason: Dealing with possible ID conflicts. I mostly work on CMS type systems with SQL-type backends. There it is very common to have a couple DB tables with small-ish incrementing numbers as IDs. So, it is very common you'd have one thing with ID=512 and another thing from another table also with ID=512. In `shadow.grove.db` the entire DB consisted of a normalized "flat" map, but since we can only `(assoc db 512 <one-thing-or-the-other>)` it was necessary to "wrap" all identifiers with the "type/table" they belong to. So, instead of just `512` we get `[:product 512]` and `[:user 512]`, which started as a vector, but later become their own record type, since that was much easier to work with and identify.

That solved the problem, but at the expense of developer ergonomics. Pretty much everyone that tried Grove complained about them. I thought it was an ok tradeoff, but in the end it wasn't worth all the trouble. Especially in combination with "table-less" backends such as Datomic which uses UUIDs are primary keys, which will never conflict. So, idents were an entirely useless abstraction there and just pure downside.

### Multiple KV Tables

Instead of trying to put everything into one big `:db` map, there is now a first class abstraction to mirror something like SQL tables. Multiple KV tables (aka. maps) can be used and grove knows how to deal with them. It is fine to still just have one, but more are available when needed.

Other solutions would nest those maps inside ONE `app-db` (e.g. `re-frame`), so you'd have a structure like

```
{:products
 {512
  {...}}
 :users
 {512
  {...}}}
```

Essentially it still looks like this, only the containing map is not accessible and top-level keys cannot be modified. Only the defined KV tables live there. You work with it in the exact same way, and they act just like regular CLJS maps. More on that later in the code examples.

## API Walkthrough

### get-runtime

```clojure
(def rt-ref (sg/get-runtime ::ui))
```

*This replaces the older manual `sg/prepare` and manual `data-ref` management.*

`shadow.grove/get-runtime` takes one keyword argument defining the application id. It can be called however often you want, but the same keyword will always get the same runtime. A runtime holds all the application state and is required if you want to modify the application in any way. You can `defonce` this, but it is not required. The runtime is preserved for hot-reloads regardless.


### kv-lookup

`shadow.grove/kv-lookup` is the primary way for components to get data they didn't already receive via arguments, when they know the place to get it from.

Translating the above component, we now get to

```clojure
(defc build-card [build-id]
  (bind {::m/keys [build-status build-target build-worker-active] :as data}
    (sg/kv-lookup ::m/build build-id))

  (bind build-warnings-count
    (count (:warnings build-status)))
  
  ...)
```

Instead of receiving the wrapped ident it just gets the plain `build-id`. `kv-lookup` gets this from the `::m/build` kv table/map. The reason for `kv-lookup`, vs. just `get-in` or so, is that it also does the necessary setup so that it gets notified whenever the data changes.

The "computed" `build-warnings-count` the component just does directly. No need for the [EQL attribute](https://github.com/thheller/shadow-cljs/blob/adcf8d7f5cd3df312099d9a0c8cacd9e967cbc0f/src/main/shadow/cljs/ui/db/builds.cljs#L41-L44) to do that.

`kv-lookup` also takes additional arguments to dig deeper into the returned data if needed. `(sg/kv-lookup ::m/build build-id ::m/build-status)` is slightly prettier than `(::m/build-status (sg/kv-lookup ::m/build build-id))`, but the result is identical.

### add-kv-table

During initialization of the application the KV tables need to be defined. Generally this will be done in `:init-fn`.

By default, no kv tables are defined, so we couldn't store any data. If you'd want to get back to the previous `:db` that is basically

```clojure
(sg/add-kv-table rt-ref
  :db ;; kv-table id
  {}  ;; options
  {}) ;; initial data 
```

`rt-ref` is the grove application defined via `(def rt-ref (sg/get-runtime :app))`. `:db` is the kv table id, which will be used to reference it later.

The options maps allows providing some extra utilities such as a `:validate-fn`, or other helpers for normalization and so on. It is fine to not provide anything at first and add them later.

The initial data map is just that. The data this table is supposed to have initially as a regular CLJS map.

It is entirely up to the developer whether one or more tables are used. I find it logically useful to create one per "type", but this is not necessary. Unless your IDs potentially conflict of course.

To create more tables `add-kv-table` is just called again with different arguments. In the above `kv-lookup` examples `::m/build` is a defined table.

### query

If a component needs more data than just one `kv-lookup` can provide then functions can be used to do exactly that. I recommend using a regular `defn`, which makes them easier to test later.

This is from the grove devtools. I'm still experimenting with the naming, so that query function are easily identifiable, so excuse the `?`, not sure if I end up liking this. It is of course just a regular CLJS `defn` with no special meaning.

```clojure
(defn ?suitable-targets [env]
  (->> (::m/target env)
       (vals)
       (remove :disconnected)
       (filter #(contains? (:supported-ops %) ::m/take-snapshot))
       (vec)))

(defc ui-root []
  (bind targets
    (sg/query ?suitable-targets))
  
  ...)
```

`env` is a map, which contains all the defined kv tables. It should be somewhat evident what the query does, since it uses all the common CLJS function with regular CLJS data. `::m/target` is again a defined table. EQL is no longer the default, but can be used manually from within the query functions.

`sg/query` optionally takes additional arguments, which will just be passed to the function when called.

```clojure
(defn my-query [env some-param]
  ...)

(sg/query my-query 1)
;; will end up calling
(my-query env 1)
```

If it looks more familiar you could also use `(defn my-query [db some-param] ...)`, it may also include some other query related things and a reference back to the application `rt-ref`.

### reg-event / tx functions

Transaction functions are the primary way the data is updated. This is unchanged to previously, so the only difference is that there is no longer just one predefined `:db` key, but instead the user defined KV tables.

```clojure
(sg/reg-event rt-ref :my-event!
  (fn [tx-env {:keys [product-id] :as ev}]
    ;; suppose the developer defined the :products table
    (assoc-in tx-env [:products product-id :foo] "bar")
    ;; instead of previously
    (assoc-in tx-env [:db (db/make-ident :products product-id) :foo] "bar")
    ;; or without idents
    (assoc-in tx-env [:db :products product-id :foo] "bar")
    ))
```

Again, if it looks more familiar using `db` instead of `tx-env` is fine.

```clojure
(sg/reg-event rt-ref :my-event!
  (fn [db {:keys [product-id] :as ev}]
    (assoc-in db [:products product-id :foo] "bar")
    ))
```

Looks and works basically like `re-frame/reg-event-db`. `tx-env` again is a map, which contains a few more transaction related things, but the gist is that you are supposed to return the updated version, as otherwise no actual updates will occur.


To be continued ...