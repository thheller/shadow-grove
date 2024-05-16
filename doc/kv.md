# GroveKV

`shadow.grove.kv` is the new data model in `shadow-grove`. It replaces the older `shadow.grove.db`, Idents and the somewhat related `shadow.grove.eql-query`.

## What is KV?

Basic concept of a key-value store, otherwise known as a map. Essentially all they are is a regular CLJS map, but they monitor updates made to them while in a transaction, so that we can easily get the modified values after the transaction concludes. This is identical to how `shadow.grove.db` used to do it, so nothing new there.

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

### EQL is gone too

I still see EQL as a fantastic query language for remote data, however in a frontend context I no longer see it as useful. It just has way too much overhead for what it provided and was always overly verbose.

Taking [this example from the shadow-cljs UI](https://github.com/thheller/shadow-cljs/blob/adcf8d7f5cd3df312099d9a0c8cacd9e967cbc0f/src/main/shadow/cljs/ui/components/builds.cljs#L39-L47) makes this somewhat apparent.

```clojure
(defc build-card [ident]
  (bind {::m/keys [build-status build-id build-target build-warnings-count build-worker-active] :as data}
    (sg/query-ident ident
      [::m/build-id
       ::m/build-target
       ::m/build-worker-active
       ::m/build-warnings-count
       ::m/build-status
       ::m/build-config-raw]))
  ...)
```

All the component wanted was to get the data from the app DB for the `[:build :app]` ident. Essentially a `(get db ident)`, but since this also used a computed field, it had to go with the overly verbose EQL query. What bothered me the most repetition of the names with the keywords and destructured symbols. This was just bad.

Where EQL was nice was joins, but they were used too rarely that in the end it is just better to do a separate `kv-lookup` when necessary. Also, one less concept to explain, since EQL joins are a bit mind bendy. EQL might still come back in a different form, but not as the primary way components get their data.

## API Walkthrough

### kv-lookup

`shadow.grove/kv-lookup` is the primary way for components to get data they didn't already receive via arguments.

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

```clojure
(sg/add-kv-table rt-ref ::m/build
  {:primary-key ::m/build-id
   :validate-key keyword?
   :validate-val map?})
```

`rt-ref` is the grove application defined via `(def rt-ref (sg/get-runtime :app))`. `::m/build` is the kv table id, which we already used above. `:validate-key` is an optional function to check keys that are being added. `:validate-val` does the same just for values. You could use spec/malli/etc here, that is just ouf of scope for this document.

By default, no kv tables are defined, so we couldn't store any data. If you'd want to get back to the previous `:db` that is basically

```clojure
(sg/add-kv-table rt-ref :db
  {:validate-key any?
   :validate-val any?})
```

Or just not specifying `:validate-key/:validate-val` at all, since those are pointless. You end up with a regular CLJS map that you can `assoc` anything in.

It is entirely up to the developer whether one or more tables are used. I find it logically useful to create one per "type", but this is not necessary. Unless your IDs potentially conflict of course.

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

It should be somewhat evident what the query does, since it uses all the common CLJS function with regular CLJS data. So all the typical functions work. We of course need the query abstraction, so that grove can ensure to run this again should any used data change.

`sg/query` optionally takes additional arguments, which will just be passed to the function when called.

```clojure
(defn my-query [env some-param]
  ...)

(sg/query my-query 1)
;; will end up calling
(my-query env 1)
```

`env` is a map, which contains all the defined kv tables. So, if it looks more familiar you could also use `(defn my-query [db some-param] ...)`, it may also include some other query related things and a reference back to the application `rt-ref`.

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