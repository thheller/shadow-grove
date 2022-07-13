# shadow.grove.db

Probably the most essential piece of the entire system is the database implementation. It must fulfil these basic requirements

- fast to read
- fast to update
- easy to work with

Because of these requirements the database is a ClojureScript persistent map, with some metadata (schema) attached. It'll usually live in an `atom`, constructed once per app.

Using some helper functions, and the attached metadata, this data is normalized; keeping the structure flat and avoiding repetition.

A few things are required to fulfil our requirements.

## Data Normalization

Suppose the following example:

```clojure
{:products
 [{:product-id 1
   :product-name "Foo"
   :manufacturer {:manufacturer-id 1
                  :manufacturer-name "ACME"}}
  {:product-id 2
   :product-name "Bar"
   :manufacturer {:manufacturer-id 1
                  :manufacturer-name "ACME"}}]}
```

Storing this shape in our database would end up with duplicated data since both products are from the same manufacturer. This becomes messy and it's hard to work with. Additionally want efficient access to data without traversing a nested structure.

Normalizing this db, we instead end up with

```clojure
{:products
 [#gdb/ident [:product 1]
  #gdb/ident [:product 2]]
 
 #gdb/ident [:product 1]
 {:product-id 1
  :product-name "Foo"
  :manufacturer #gdb/ident [:manufacturer 1]}

 #gdb/ident [:product 2]
 {:product-id 2
  :product-name "Bar"
  :manufacturer #gdb/ident [:manufacturer 1]}
 
 #gdb/ident [:manufacturer 1]
 {:manufacturer-id 1
  :manufacturer-name "ACME"}}
```

All entities are pulled to the top leve (flat) and are assigned an "ident", The duplication is removed. We can now access the manufacturer directly and quickly update it (eg. `(update db manufacturer-ident assoc :manufacturer-name "Foo Inc.")`) without having to find all the places it was stored first.

## Idents

Idents represent the combination of an `entity-type` and a generic `id`.

- `entity-type` must be a keyword (ideally namespaced)
- `id` can be anything. Often they'll be UUIDs, numbers, or strings

This combination is necessary since many DB system (eg. SQL) store data in different tables. `entity-type` would represent the `table` and `id` would identify a specific row in that table. If your data model requirements don't need "tables" it's fine to use the same `entity-type` everywhere.

Idents are their own `deftype` so they are easily identifiable by the normalization, query mechanism, and humans of course.

They are usually constructed by the normalizing functions which use the supplied schema to extract a `:primary-key` and use that as the `id`. They can be constructed easily via `(db/make-ident entity-type id)`.

## Read Tracking

It is not sufficient to just have a map that we can read from. Often we will also need to keep track of what was read.

This is done via the `db/observed` implementation. It wraps ClojureScript persistent map and acts as just a map would. It's read-only; it cannot be modified.

```clojure
(require '[shadow.grove.db :as db])

(def data {:x 1 :y 1})

(def observed (db/observed data))

(:x observed)

(assoc observed :z 1) ;; throws

(db/observed-keys observed)
```

This will give us `#{:x}`. This is very cheap to collect and essential later.

The relevant implementations that actually read from the database (eg. `sg/query-ident` and `sg/query-root`) will keep track of this info, so they can be notified when they need to update.

## Write Tracking

```clojure
(require '[shadow.grove.db :as db])

(-> {:x 1 :y 1} ;; the actual database
    (db/configure {}) ;; schema not needed for this example
    (db/transacted) ;; wrap it
    (update :x inc) ;; user code modifying a value
    (dissoc :y) ;; user code removing
    (assoc :z 1) ;; user code adding
    (db/commit!)) ;; the final step
```

This will yield the following

```clojure
{:data {:x 2 :z 1}
 :keys-new #{:z}
 :keys-updated #{:x}
 :keys-removed #{:y}}
```

The "transaction" collected which keys where added, removed or updated. This lets us notify the things that read from the db very efficiently.

The user will  work with the transacted instance, which acts like a map and should be treated as such. The event system will take care of providing the `transacted` instance of properly managing the `commit!` results.

Note here that this is only tracking the top level keys. It does not look at nested structures at all. Since we normalized the data this is not a problem. Tracking nested data is certainly possible but also much more expensive.

## Schema

A database schema is required for everything the import functions are supposed to normalize. It needs to be able to create Idents for each entity, which is done by extracting the `:primary-key`. It may also need to know which attributes are actual joins to different entities.

```clojure
(require '[shadow.grove.db :as db])

(def products
  [{:product-id 1
     :product-name "Foo"
     :manufacturer {:manufacturer-id 1
                    :manufacturer-name "ACME"}}
    {:product-id 2
     :product-name "Bar"
     :manufacturer {:manufacturer-id 1
                    :manufacturer-name "ACME"}}])

(def schema
  {:product
   {:type :entity
    :primary-key :product-id
    :joins {:manufacturer [:one :manufacturer]}}
   :manufacturer
   {:type :entity
    :primary-key :manufacturer-id}})

(-> {}
    (db/configure schema)
    (db/transacted)
    ;; need to provide the initial type, since the impl cannot yet figure that out
    (db/merge-seq :product products [:products])
    (db/commit!))
```

Here we imported the regular products vector and normalized it using the `schema`.

```clojure
{:data
 {#gdb/ident[:manufacturer 1]
  {:manufacturer-id 1,
   :manufacturer-name "ACME",
   :db/ident #gdb/ident[:manufacturer 1]},
        
  #gdb/ident[:product 1]
  {:product-id 1,
   :product-name "Foo",
   :manufacturer #gdb/ident[:manufacturer 1],
   :db/ident #gdb/ident[:product 1]},

  #gdb/ident[:product 2]
  {:product-id 2,
   :product-name "Bar",
   :manufacturer #gdb/ident[:manufacturer 1],
   :db/ident #gdb/ident[:product 2]},
  
  [:shadow.grove.db/all :product]
  #{#gdb/ident[:product 1]
    #gdb/ident[:product 2]},

  [:shadow.grove.db/all :manufacturer]
  #{#gdb/ident[:manufacturer 1]},

  :products
  [#gdb/ident[:product 1]
   #gdb/ident[:product 2]]},
        
 :keys-new ...
 :keys-updated ...
 :keys-removed ...}
```

Note that the `transacted` helper maintains a few extra collections, therefore we can easily traverse all `:product` or `:manufacturer` instances later without having to traverse the entire db.

The `db/merge-seq` call stored the products it imported under the `:products` key, that part is optional and can be omitted.

`db/add` would add a single item.


# Performance Considerations

Most data access in UI code will be simple lookups, which are basically constant time. Insignificant difference if there are 50 or 50.000 keys in the database.

The overhead of constructing `db/observed` and `db/transacted` is minimal. Far cheaper than any alternate method of read/write tracking I'm aware of.

# Query Considerations

In `db/observed` every read is tracked. If a query accesses everything in the database, it will also be invalidated when anything in the database is modified. This is exactly what is needed, but in turn queries should try to minimize what they access. Luckily that is the case most often anyways.

However, you may have queries that actually want a list of something based on some conditions. Suppose you add a `:stock` number to each `:product` indicating how many of each are in stock. You might use an EQL attribute to fetch all products in stock.

```clojure
(defmethod eql/attr :products-in-stock [env db _ _]
  (->> (db/all-of :product)
       (filter #(pos? (:stock %)))
       (mapv :db/ident)))

;; in the component

(defc ui-product-into [product-ident]
  (bind data
    (sg/query-ident product-ident))

  (render
    (pr-str data)))

(defc ui-homepage []
  (bind data
    (sg/query-root
      [:products-in-stock]))

  (render
    (sg/keyed-seq (:products-in-stock data) identity ui-product-info)))
```

In this example the `ui-homepage` would re-render every time any `:product` is updated. Technically it only needs to change if any `:stock` changes from or to `0`. 

It would be neat if the system could figure this out on its own, but so far that implementation has eluded me. Note that this is still very cheap overall, just something to be aware of.

Note that the `:products-in-stock` query returned a vector of idents. This is preferable over returning the actual product map. The `ui-product-info` component can get that data itself, but by supplying it less info initially there is less data to compare later.

Depending on the query complexity and frequency of affected updates it may be more efficient to store the result of that query in an actual `:products-in-stock` vector, and manually recomputing it whenever you change `:stock`.

# API Considerations

Systems such as Fulcro keep the data in nested collections, so you'd have `{:product {1 ... 2 ...} :manufacturer {1 ...}}` instead. This also sort of requires Idents to be vectors, which is of course why they are stored that way in the first place. This is of course perfectly viable, but makes the read/write tracking much more complicated.

I also happen to prefer `(assoc-in db [product-ident :stock] new-stock)` over `(assoc-in db (conj product-ident :stock) new-stock)`. Of course Fulcro has many more utility functions for this, so this ident modification is hidden. I prefer to skip the nesting.

Overall I consider the flat map to provide a nicer API overall while also making things look like an actual key/value store. Given that we already have read/write tracking we can also easily implement sync mechanisms that only transfer actually changed keys without having to find them first.

It is also perfectly fine to store non-ident things in the database root. You might want to have some "global" state such as `:current-user {:name "thheller"}` or top level collection such as the `:products` added above.

This DB implementation is also pretty generic and not actually coupled to any internals of other shadow.grove parts. It may actually be suitable to use in a `re-frame` app?

# UI Data Considerations

**This DB is meant to represent your UI state.** It is not required to be a mirror of your backend DB. The schema does not need to match your actual DB schema.

It might make sense to introduce UI specific entities/attributes, that your backend DB may not otherwise have. The schema is easy to change, and you don't need to worry about migrations or whatever.

